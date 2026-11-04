package com.carddemo.posting;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.List;

public final class CobolFormat {
    private static final String POSITIVE_OVERPUNCH = "{ABCDEFGHI";
    private static final String NEGATIVE_OVERPUNCH = "}JKLMNOPQR";

    private CobolFormat() {
    }

    public static BigDecimal truncateHighOrder(BigDecimal value, int integerDigits) {
        BigDecimal limit = BigDecimal.TEN.pow(integerDigits);
        return value.remainder(limit).setScale(2, RoundingMode.DOWN);
    }

    public static String zeroPad(BigDecimal value, int width) {
        String digits = value.toBigInteger().toString();
        return "0".repeat(Math.max(0, width - digits.length())) + digits;
    }

    public static String rightPad(String value, int width) {
        String text = value == null ? "" : value;
        return (text + " ".repeat(width)).substring(0, width);
    }

    public static String db2Timestamp(String cobolCurrentDate) {
        return cobolCurrentDate.substring(0, 4) + "-" + cobolCurrentDate.substring(4, 6) + "-"
                + cobolCurrentDate.substring(6, 8) + "-" + cobolCurrentDate.substring(8, 10) + "."
                + cobolCurrentDate.substring(10, 12) + "." + cobolCurrentDate.substring(12, 14) + "."
                + cobolCurrentDate.substring(14, 16) + "0000";
    }

    public static String displaySigned(BigDecimal value) {
        BigDecimal amount = value.setScale(2, RoundingMode.DOWN);
        String digits = amount.abs().movePointRight(2).toBigInteger().toString();
        digits = "0".repeat(Math.max(0, 11 - digits.length())) + digits;
        if (digits.length() > 11) {
            digits = digits.substring(digits.length() - 11);
        }
        return digits + (amount.signum() < 0 ? "-" : "+");
    }

    public static String editMinus(BigDecimal value) {
        return editAmount(value, false);
    }

    public static String editSigned(BigDecimal value) {
        return editAmount(value, true);
    }

    public static String fileStatusLine(String status) {
        byte[] bytes = status.getBytes(StandardCharsets.ISO_8859_1);
        if (status.length() >= 2 && status.substring(0, 2).matches("\\d{2}")
                && status.charAt(0) != '9') {
            return "FILE STATUS IS: NNNN00" + status.substring(0, 2);
        }
        char first = bytes.length == 0 ? ' ' : (char) (bytes[0] & 0xff);
        int second = bytes.length < 2 ? 0 : bytes[1] & 0xff;
        return "FILE STATUS IS: NNNN" + first + String.format("%03d", second);
    }

    public static List<String> abendLines(String message, String status) {
        return List.of(message, fileStatusLine(status), "ABENDING PROGRAM",
                "CEE3ABD: USER ABEND U+0000000999");
    }

    public static List<String> abendLines(String message) {
        return List.of(message, "ABENDING PROGRAM", "CEE3ABD: USER ABEND U+0000000999");
    }

    private static String editAmount(BigDecimal value, boolean leadingPlus) {
        BigDecimal amount = value.setScale(2, RoundingMode.DOWN);
        if (amount.signum() == 0) {
            return " ".repeat(15);
        }

        BigInteger integer = amount.abs().toBigInteger();
        String digits = "0".repeat(Math.max(0, 9 - integer.toString().length())) + integer;
        if (digits.length() > 9) {
            digits = digits.substring(digits.length() - 9);
        }
        int firstSignificant = 0;
        while (firstSignificant < digits.length() && digits.charAt(firstSignificant) == '0') {
            firstSignificant++;
        }
        StringBuilder formatted = new StringBuilder(14);
        for (int index = 0; index < digits.length(); index++) {
            formatted.append(index < firstSignificant ? ' ' : digits.charAt(index));
            if (index == 2 || index == 5) {
                formatted.append(index < firstSignificant ? ' ' : ',');
            }
        }
        formatted.append('.').append(String.format("%02d", amount.abs().remainder(BigDecimal.ONE)
                .movePointRight(2).intValue()));
        char sign = amount.signum() < 0 ? '-' : leadingPlus ? '+' : ' ';
        return sign + formatted.toString();
    }

    public static String overpunch(BigDecimal value, int integerDigits, int scale) {
        BigDecimal scaled = value.setScale(scale, RoundingMode.DOWN);
        String digits = scaled.abs().movePointRight(scale).toBigInteger().toString();
        int width = integerDigits + scale;
        digits = "0".repeat(Math.max(0, width - digits.length())) + digits;
        if (digits.length() > width) {
            digits = digits.substring(digits.length() - width);
        }
        int last = Character.digit(digits.charAt(width - 1), 10);
        String table = scaled.signum() < 0 ? NEGATIVE_OVERPUNCH : POSITIVE_OVERPUNCH;
        return digits.substring(0, width - 1) + table.charAt(last);
    }
}
