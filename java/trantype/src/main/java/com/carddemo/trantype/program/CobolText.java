package com.carddemo.trantype.program;

/** Small COBOL data-movement helpers used by the ported programs. */
public final class CobolText {

    private CobolText() {}

    /** {@code MOVE x TO PIC X(n)}: left-justified, space-padded or truncated on the right. */
    public static String picX(String value, int n) {
        String v = value == null ? "" : value;
        return v.length() >= n ? v.substring(0, n) : v + " ".repeat(n - v.length());
    }

    /**
     * {@code MOVE SQLCODE TO WS-VAR-SQLCODE} with {@code PIC ----9}: five positions, floating
     * minus sign, at most four digits (high-order digits are lost).
     */
    public static String editMinus4(int value) {
        int digits = Math.abs(value) % 10000;
        String d = Integer.toString(digits);
        String s = value < 0 ? "-" + d : d;
        return s.length() >= 5 ? s.substring(s.length() - 5) : " ".repeat(5 - s.length()) + s;
    }

    /** {@code DISPLAY SQLCODE}: sign followed by ten digits, e.g. {@code -0000000400}. */
    public static String signed10(int value) {
        return (value < 0 ? "-" : "+") + String.format("%010d", Math.abs((long) value));
    }

    /** {@code ADD 1 TO PIC 9(02)} without ON SIZE ERROR: the high-order digit is lost. */
    public static int addPic99(int value, int add) {
        return (value + add) % 100;
    }
}
