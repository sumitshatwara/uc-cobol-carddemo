package com.carddemo.posting;

import com.carddemo.common.cobol.CobolNumeric;
import com.carddemo.common.codec.RecordCodec;
import com.carddemo.common.run.SysoutWriter;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;

/** Small COBOL helpers shared by the CBTRN02C and CBTRN03C ports (zoned decimals, DISPLAY forms, abends). */
public final class Cobol {

    private static final String POSITIVE = "{ABCDEFGHI";
    private static final String NEGATIVE = "}JKLMNOPQR";

    private Cobol() {}

    /** Value of a zoned-decimal field (EBCDIC overpunch on the last digit, or a plain digit). Blank = 0. */
    public static BigDecimal zoned(String raw, int scale) {
        if (raw.isBlank()) {
            return BigDecimal.ZERO.setScale(scale);
        }
        char last = raw.charAt(raw.length() - 1);
        int sign = 1;
        String digits = raw.substring(0, raw.length() - 1);
        if (POSITIVE.indexOf(last) >= 0) {
            digits += POSITIVE.indexOf(last);
        } else if (NEGATIVE.indexOf(last) >= 0) {
            digits += NEGATIVE.indexOf(last);
            sign = -1;
        } else {
            digits += last;
        }
        BigInteger v = new BigInteger(digits.replace(' ', '0'));
        return new BigDecimal(sign < 0 ? v.negate() : v, scale);
    }

    /** Raw storage of a signed zoned field (what a record carries), e.g. {@code 0000001250{}. */
    public static String zonedStorage(BigDecimal value, int intDigits, int scale) {
        return new String(RecordCodec.encodeDisplay(CobolNumeric.fit(value, intDigits, scale), intDigits + scale, scale, true),
                StandardCharsets.ISO_8859_1);
    }

    /**
     * GnuCOBOL {@code DISPLAY} of a signed numeric DISPLAY item on its own: all digits followed by a
     * trailing sign character, e.g. {@code 00000001575-} for S9(09)V99 = -15.75 (as in the golden SYSOUT).
     */
    public static String displaySigned(BigDecimal value, int intDigits, int scale) {
        BigDecimal f = CobolNumeric.fit(value, intDigits, scale);
        String digits = f.abs().unscaledValue().toString();
        digits = "0".repeat(Math.max(0, intDigits + scale - digits.length())) + digits;
        return digits + (f.signum() < 0 ? '-' : '+');
    }

    /** Unsigned PIC 9(n) as DISPLAYed: zero padded. */
    public static String nines(long value, int digits) {
        String s = Long.toString(Math.abs(value));
        if (s.length() > digits) {
            s = s.substring(s.length() - digits);
        }
        return "0".repeat(digits - s.length()) + s;
    }

    /** {@code MOVE} of an alphanumeric into PIC X(n): pad with spaces or cut. */
    public static String pic(String value, int length) {
        String v = value == null ? "" : value;
        return v.length() >= length ? v.substring(0, length) : v + " ".repeat(length - v.length());
    }

    public static String rstrip(String s) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == ' ') {
            end--;
        }
        return s.substring(0, end);
    }

    /**
     * {@code 9910-DISPLAY-IO-STATUS} (numeric status) followed by {@code 9999-ABEND-PROGRAM}:
     * {@code FILE STATUS IS: NNNN00ss}, {@code ABENDING PROGRAM}, then CEE3ABD (RC 12).
     */
    public static CobolAbend abend(SysoutWriter sysout, String ioStatus) {
        sysout.display("FILE STATUS IS: NNNN", "00" + pic(ioStatus, 2));
        sysout.display("ABENDING PROGRAM");
        return new CobolAbend("abend 999, file status " + ioStatus);
    }
}
