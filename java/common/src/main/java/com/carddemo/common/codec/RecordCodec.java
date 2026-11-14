package com.carddemo.common.codec;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Fixed-record codec, the Java twin of {@code tools/parity/copybook.py}
 * ({@code decode_record} / {@code encode_record}).
 * <ul>
 *   <li>TEXT: latin-1 (or cp037 for EBCDIC), right-trimmed on decode, space padded on encode;</li>
 *   <li>DISPLAY: zoned decimal; signed fields carry the sign in the last byte as an
 *       EBCDIC-style overpunch ({@code {ABCDEFGHI} positive, {@code }JKLMNOPQR} negative),
 *       which is what {@code cobc -fsign=EBCDIC} writes;</li>
 *   <li>COMP-3: packed decimal, sign nibble C/F positive, D/B negative;</li>
 *   <li>COMP: big-endian two's complement.</li>
 * </ul>
 * Numeric values decode to {@link BigDecimal} at the field's scale.  Encoding is
 * deliberately strict: a value with more decimals than the PIC allows throws
 * instead of being rounded, so every truncation in a slice is explicit
 * ({@code setScale(n, RoundingMode.DOWN)}); excess high-order digits are dropped
 * like a COBOL MOVE.
 */
public final class RecordCodec {

    public enum Encoding { ASCII, EBCDIC }

    static final String POSITIVE = "{ABCDEFGHI";
    static final String NEGATIVE = "}JKLMNOPQR";
    private static final Charset CP037 = Charset.forName("IBM037");

    private RecordCodec() {}

    // ------------------------------------------------------------------ decode

    public static Map<String, Object> decode(Copybook copybook, byte[] data) {
        return decode(copybook, data, Encoding.ASCII);
    }

    /** Decodes every non-FILLER field; short records are treated as space padded. */
    public static Map<String, Object> decode(Copybook copybook, byte[] data, Encoding encoding) {
        Charset cs = encoding == Encoding.EBCDIC ? CP037 : StandardCharsets.ISO_8859_1;
        byte[] rec = data.length >= copybook.recordLength() ? data : pad(data, copybook.recordLength(), encoding);
        Map<String, Object> values = new LinkedHashMap<>();
        for (CopybookField f : copybook.fields()) {
            if (f.isFiller()) {
                continue;
            }
            byte[] raw = Arrays.copyOfRange(rec, f.offset(), f.offset() + f.length());
            values.put(f.name(), switch (f.kind()) {
                case TEXT -> rstrip(new String(raw, cs));
                case COMP3 -> decodePacked(raw, f.scale());
                case COMP -> decodeComp(raw, f.scale(), f.signed());
                case DISPLAY -> decodeDisplay(raw, f.scale(), cs, encoding);
            });
        }
        return values;
    }

    /** Decodes one line of a fixture / dataset text file (latin-1, one record per line). */
    public static Map<String, Object> decodeLine(Copybook copybook, String line) {
        return decode(copybook, line.getBytes(StandardCharsets.ISO_8859_1));
    }

    private static byte[] pad(byte[] data, int len, Encoding encoding) {
        byte[] out = new byte[len];
        Arrays.fill(out, encoding == Encoding.EBCDIC ? (byte) 0x40 : (byte) ' ');
        System.arraycopy(data, 0, out, 0, data.length);
        return out;
    }

    /** Python {@code str.rstrip()} (strips all whitespace characters latin-1 knows). */
    static String rstrip(String s) {
        int end = s.length();
        while (end > 0 && isPyWhitespace(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(0, end);
    }

    private static boolean isPyWhitespace(char c) {
        return c == ' ' || (c >= '\t' && c <= '\r') || (c >= 0x1c && c <= 0x1f) || c == 0x85 || c == 0xa0;
    }

    static Object decodeDisplay(byte[] raw, int scale, Charset cs, Encoding encoding) {
        String text = new String(raw, cs);
        if (text.strip().isEmpty()) {
            return BigDecimal.ZERO;
        }
        int sign = 1;
        char last = text.charAt(text.length() - 1);
        int pos = POSITIVE.indexOf(last);
        int neg = NEGATIVE.indexOf(last);
        if (pos >= 0 || neg >= 0) {
            sign = neg >= 0 ? -1 : 1;
            text = text.substring(0, text.length() - 1) + (neg >= 0 ? neg : pos);
        } else if (encoding == Encoding.EBCDIC && !Character.isDigit(last)) {
            int rawLast = raw[raw.length - 1] & 0xFF;
            sign = (rawLast >> 4) == 0xD ? -1 : 1;
            text = text.substring(0, text.length() - 1) + (rawLast & 0x0F);
        }
        String digits = text.replace(' ', '0');
        if (!digits.chars().allMatch(ch -> ch >= '0' && ch <= '9')) {
            return new String(raw, cs); // not a valid zoned number: keep the raw text, as the Python codec does
        }
        BigDecimal v = new BigDecimal(new BigInteger(digits).multiply(BigInteger.valueOf(sign)));
        return scale == 0 ? v : v.movePointLeft(scale);
    }

    static BigDecimal decodePacked(byte[] raw, int scale) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.length; i++) {
            int b = raw[i] & 0xFF;
            sb.append((char) ('0' + (b >> 4)));
            if (i < raw.length - 1) {
                sb.append((char) ('0' + (b & 0x0F)));
            }
        }
        int signNibble = raw[raw.length - 1] & 0x0F;
        BigInteger v = new BigInteger(sb.isEmpty() ? "0" : sb.toString());
        if (signNibble == 0xB || signNibble == 0xD) {
            v = v.negate();
        }
        BigDecimal d = new BigDecimal(v);
        return scale == 0 ? d : d.movePointLeft(scale);
    }

    static BigDecimal decodeComp(byte[] raw, int scale, boolean signed) {
        BigInteger v = signed ? new BigInteger(raw) : new BigInteger(1, raw);
        BigDecimal d = new BigDecimal(v);
        return scale == 0 ? d : d.movePointLeft(scale);
    }

    // ------------------------------------------------------------------ encode

    /** Encodes a full record; missing text fields are spaces, missing numerics zero. */
    public static byte[] encode(Copybook copybook, Map<String, ?> values) {
        byte[] out = new byte[copybook.recordLength()];
        Arrays.fill(out, (byte) ' ');
        for (CopybookField f : copybook.fields()) {
            byte[] enc;
            if (f.isFiller()) {
                if (f.kind() == FieldKind.TEXT) {
                    continue;
                }
                enc = encodeDisplay(BigDecimal.ZERO, f.length(), 0, false);
            } else {
                Object value = values.get(f.name());
                enc = switch (f.kind()) {
                    case TEXT -> encodeText(value == null ? "" : value.toString(), f.length());
                    case COMP3 -> encodePacked(toDecimal(value), f.length(), f.scale(), f.signed());
                    case COMP -> encodeComp(toDecimal(value), f.length(), f.scale(), f.signed());
                    case DISPLAY -> encodeDisplay(toDecimal(value), f.length(), f.scale(), f.signed());
                };
            }
            System.arraycopy(enc, 0, out, f.offset(), f.length());
        }
        return out;
    }

    /** Encodes a record and returns it as a latin-1 string (one dataset line, not yet trimmed). */
    public static String encodeLine(Copybook copybook, Map<String, ?> values) {
        return new String(encode(copybook, values), StandardCharsets.ISO_8859_1);
    }

    static BigDecimal toDecimal(Object value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        if (value instanceof BigDecimal d) {
            return d;
        }
        if (value instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        String s = value.toString().strip();
        return s.isEmpty() ? BigDecimal.ZERO : new BigDecimal(s);
    }

    static byte[] encodeText(String value, int length) {
        String s = value.length() >= length ? value.substring(0, length) : value + " ".repeat(length - value.length());
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static BigInteger units(BigDecimal value, int scale) {
        try {
            return value.abs().setScale(scale, RoundingMode.UNNECESSARY).unscaledValue();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("value " + value.toPlainString() + " has more than " + scale
                    + " decimals; truncate explicitly (RoundingMode.DOWN) before encoding", e);
        }
    }

    /** Zoned decimal with high-order truncation and an overpunched sign when {@code signed}. */
    public static byte[] encodeDisplay(BigDecimal value, int length, int scale, boolean signed) {
        String digits = units(value, scale).toString();
        String text = digits.length() >= length ? digits.substring(digits.length() - length) : "0".repeat(length - digits.length()) + digits;
        if (signed) {
            String table = value.signum() < 0 ? NEGATIVE : POSITIVE;
            text = text.substring(0, length - 1) + table.charAt(text.charAt(length - 1) - '0');
        }
        return text.getBytes(StandardCharsets.ISO_8859_1);
    }

    public static byte[] encodePacked(BigDecimal value, int length, int scale, boolean signed) {
        int nd = length * 2 - 1;
        String digits = units(value, scale).toString();
        digits = digits.length() >= nd ? digits.substring(digits.length() - nd) : "0".repeat(nd - digits.length()) + digits;
        int[] nib = new int[nd + 1];
        for (int i = 0; i < nd; i++) {
            nib[i] = digits.charAt(i) - '0';
        }
        nib[nd] = signed && value.signum() < 0 ? 0xD : signed ? 0xC : 0xF;
        byte[] out = new byte[length];
        for (int i = 0; i < length; i++) {
            out[i] = (byte) ((nib[2 * i] << 4) | nib[2 * i + 1]);
        }
        return out;
    }

    public static byte[] encodeComp(BigDecimal value, int length, int scale, boolean signed) {
        BigInteger u = units(value, scale);
        if (value.signum() < 0) {
            u = u.negate();
        }
        byte[] full = u.toByteArray();
        if (full.length > length + (signed ? 0 : 1) || (!signed && u.signum() < 0)) {
            throw new IllegalArgumentException("value " + value + " does not fit COMP length " + length);
        }
        byte[] out = new byte[length];
        byte fill = (byte) (u.signum() < 0 ? 0xFF : 0x00);
        Arrays.fill(out, fill);
        int copy = Math.min(full.length, length);
        System.arraycopy(full, full.length - copy, out, length - copy, copy);
        return out;
    }
}
