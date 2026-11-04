package com.carddemo.common.codec;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class RecordCodec {
    private static final String POSITIVE = "{ABCDEFGHI";
    private static final String NEGATIVE = "}JKLMNOPQR";

    private RecordCodec() {
    }

    public static LinkedHashMap<String, Object> decode(String copybook, byte[] record) {
        LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        for (CopybookField field : CopybookParser.parse(copybook)) {
            if (field.isFiller()) {
                continue;
            }
            int end = Math.min(record.length, field.offset() + field.length());
            byte[] raw = new byte[Math.max(0, end - field.offset())];
            if (field.offset() < record.length) {
                System.arraycopy(record, field.offset(), raw, 0, raw.length);
            }
            Object value = switch (field.kind()) {
                case TEXT -> new String(raw, StandardCharsets.ISO_8859_1).stripTrailing();
                case COMP3 -> decodePacked(raw, field.scale());
                case COMP -> decodeComp(raw, field.scale(), field.signed());
                case DISPLAY -> decodeDisplay(raw, field.scale(), field.signed());
            };
            values.put(field.name(), value);
        }
        return values;
    }

    public static byte[] encode(String copybook, Map<String, ?> values) {
        List<CopybookField> fields = CopybookParser.parse(copybook);
        byte[] output = new byte[CopybookParser.recordLength(fields)];
        java.util.Arrays.fill(output, (byte) ' ');
        for (CopybookField field : fields) {
            byte[] encoded;
            if (field.isFiller()) {
                if (field.kind() == CopybookField.Kind.TEXT) {
                    continue;
                }
                encoded = encodeDisplay(BigDecimal.ZERO, field.length(), 0, false);
            } else {
                Object value = values.containsKey(field.name())
                        ? values.get(field.name()) : field.kind() == CopybookField.Kind.TEXT ? "" : 0;
                encoded = switch (field.kind()) {
                    case TEXT -> encodeText(value, field.length());
                    case COMP3 -> encodePacked(value, field.length(), field.scale(), field.signed());
                    case COMP -> encodeComp(value, field.length(), field.scale(), field.signed());
                    case DISPLAY -> encodeDisplay(value, field.length(), field.scale(), field.signed());
                };
            }
            System.arraycopy(encoded, 0, output, field.offset(), field.length());
        }
        return output;
    }

    private static Object decodeDisplay(byte[] raw, int scale, boolean signed) {
        String text = new String(raw, StandardCharsets.ISO_8859_1);
        int sign = 1;
        if (text.isBlank()) {
            return BigDecimal.ZERO;
        }
        char last = text.charAt(text.length() - 1);
        int positive = POSITIVE.indexOf(last);
        int negative = NEGATIVE.indexOf(last);
        if (positive >= 0 || negative >= 0) {
            int digit = positive >= 0 ? positive : negative;
            sign = positive >= 0 ? 1 : -1;
            text = text.substring(0, text.length() - 1) + digit;
        }
        String digits = text.replace(' ', '0');
        if (!digits.matches("\\d+")) {
            return new String(raw, StandardCharsets.ISO_8859_1);
        }
        BigDecimal result = new BigDecimal(new java.math.BigInteger(digits), scale);
        return sign < 0 ? result.negate() : result;
    }

    private static BigDecimal decodePacked(byte[] raw, int scale) {
        StringBuilder digits = new StringBuilder();
        int signNibble = 0;
        for (byte value : raw) {
            digits.append((value >>> 4) & 0xf).append(value & 0xf);
        }
        if (!digits.isEmpty()) {
            signNibble = Character.digit(digits.charAt(digits.length() - 1), 16);
            digits.setLength(digits.length() - 1);
        }
        BigDecimal result = new BigDecimal(
                new java.math.BigInteger(digits.isEmpty() ? "0" : digits.toString()), scale);
        return signNibble == 0xb || signNibble == 0xd ? result.negate() : result;
    }

    private static BigDecimal decodeComp(byte[] raw, int scale, boolean signed) {
        if (raw.length == 0) {
            return BigDecimal.ZERO.setScale(scale);
        }
        java.math.BigInteger value = new java.math.BigInteger(signed ? raw : withPositiveSign(raw));
        return new BigDecimal(value).movePointLeft(scale).setScale(scale);
    }

    private static byte[] withPositiveSign(byte[] raw) {
        byte[] positive = new byte[raw.length + 1];
        System.arraycopy(raw, 0, positive, 1, raw.length);
        return positive;
    }

    private static byte[] encodeText(Object value, int length) {
        byte[] bytes = String.valueOf(value).getBytes(StandardCharsets.ISO_8859_1);
        byte[] result = new byte[length];
        java.util.Arrays.fill(result, (byte) ' ');
        System.arraycopy(bytes, 0, result, 0, Math.min(bytes.length, length));
        return result;
    }

    private static byte[] encodeDisplay(Object value, int length, int scale, boolean signed) {
        BigDecimal decimal = decimal(value);
        BigDecimal unitsDecimal = decimal.abs().movePointRight(scale).setScale(0, RoundingMode.DOWN);
        String digits = leftPad(unitsDecimal.toBigInteger().toString(), length);
        if (signed) {
            int digit = Character.digit(digits.charAt(length - 1), 10);
            String table = decimal.signum() < 0 ? NEGATIVE : POSITIVE;
            digits = digits.substring(0, length - 1) + table.charAt(digit);
        }
        return digits.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] encodePacked(Object value, int length, int scale, boolean signed) {
        BigDecimal decimal = decimal(value);
        int digitCount = length * 2 - 1;
        String digits = leftPad(decimal.abs().movePointRight(scale).setScale(0, RoundingMode.DOWN)
                .toBigInteger().toString(), digitCount);
        int[] nibbles = new int[length * 2];
        for (int i = 0; i < digitCount; i++) {
            nibbles[i] = Character.digit(digits.charAt(i), 10);
        }
        nibbles[nibbles.length - 1] = signed && decimal.signum() < 0 ? 0xd : signed ? 0xc : 0xf;
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = (byte) ((nibbles[i * 2] << 4) | nibbles[i * 2 + 1]);
        }
        return bytes;
    }

    private static byte[] encodeComp(Object value, int length, int scale, boolean signed) {
        BigIntegerValue scaled = new BigIntegerValue(decimal(value).movePointRight(scale)
                .setScale(0, RoundingMode.DOWN).toBigInteger());
        byte[] raw = scaled.value().toByteArray();
        byte[] bytes = new byte[length];
        int sourceOffset = Math.max(0, raw.length - length);
        int copied = Math.min(raw.length, length);
        System.arraycopy(raw, sourceOffset, bytes, length - copied, copied);
        if (!signed && bytes.length > 0) {
            bytes[0] &= 0x7f;
        }
        return bytes;
    }

    private static String leftPad(String value, int length) {
        return "0".repeat(Math.max(0, length - value.length())) + value.substring(Math.max(0, value.length() - length));
    }

    private static BigDecimal decimal(Object value) {
        return value instanceof BigDecimal decimal ? decimal : new BigDecimal(String.valueOf(value));
    }

    private record BigIntegerValue(java.math.BigInteger value) {
    }
}
