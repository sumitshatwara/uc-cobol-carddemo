package com.carddemo.online;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** COBOL storage helpers: PIC X padding, LOW-VALUES, SPACES, NUMERIC class test, NUMVAL-C. */
public final class Pic {
    private Pic() {}

    public static final char LOW = '\0';

    /** MOVE to PIC X(n): left-justify, pad with spaces, truncate on the right. */
    public static String x(String s, int n) {
        String v = s == null ? "" : s;
        return v.length() >= n ? v.substring(0, n) : v + " ".repeat(n - v.length());
    }

    public static String low(int n) {
        return String.valueOf(LOW).repeat(n);
    }

    public static boolean isLow(String s) {
        return s != null && !s.isEmpty() && s.chars().allMatch(c -> c == LOW);
    }

    public static boolean isSpaces(String s) {
        return s == null || s.chars().allMatch(c -> c == ' ');
    }

    public static boolean isLowOrSpaces(String s) {
        return isLow(s) || isSpaces(s);
    }

    /** IS NUMERIC on PIC X / unsigned 9: every character a digit. */
    public static boolean numeric(String s) {
        return s != null && !s.isEmpty() && s.chars().allMatch(c -> c >= '0' && c <= '9');
    }

    /** All characters '0'. */
    public static boolean zeros(String s) {
        return s != null && !s.isEmpty() && s.chars().allMatch(c -> c == '0');
    }

    /** FUNCTION TRIM (leading and trailing spaces; LOW-VALUES are not trimmed by IBM COBOL). */
    public static String trim(String s) {
        if (s == null) {
            return "";
        }
        int b = 0;
        int e = s.length();
        while (b < e && s.charAt(b) == ' ') {
            b++;
        }
        while (e > b && s.charAt(e - 1) == ' ') {
            e--;
        }
        return s.substring(b, e);
    }

    /** Trailing-space trim for display (a BMS field shows its content without trailing blanks). */
    public static String rtrim(String s) {
        if (s == null) {
            return "";
        }
        int e = s.length();
        while (e > 0 && (s.charAt(e - 1) == ' ' || s.charAt(e - 1) == LOW)) {
            e--;
        }
        return s.substring(0, e);
    }

    private static final java.util.regex.Pattern NUMVAL_C = java.util.regex.Pattern.compile(
            " *(?:(?<ls>[+-]) *)?(?:\\$ *)?(?<ls2>[+-])? *(?<num>(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d*)?|\\.\\d+)"
                    + " *(?<ts>[+-]|CR|DB|cr|db)? *");

    /** FUNCTION TEST-NUMVAL-C(x) = 0: x is a valid currency-numeric string. */
    public static boolean testNumvalC(String s) {
        if (s == null) {
            return false;
        }
        java.util.regex.Matcher m = NUMVAL_C.matcher(s);
        if (!m.matches()) {
            return false;
        }
        int signs = (m.group("ls") != null ? 1 : 0) + (m.group("ls2") != null ? 1 : 0) + (m.group("ts") != null ? 1 : 0);
        return signs <= 1;
    }

    /** FUNCTION NUMVAL-C(x); caller has checked {@link #testNumvalC}. */
    public static BigDecimal numvalC(String s) {
        java.util.regex.Matcher m = NUMVAL_C.matcher(s);
        if (!m.matches()) {
            throw new IllegalArgumentException("not NUMVAL-C: " + s);
        }
        BigDecimal v = new BigDecimal(m.group("num").replace(",", ""));
        String sign = m.group("ls") != null ? m.group("ls") : m.group("ls2") != null ? m.group("ls2") : m.group("ts");
        if (sign != null && (sign.equals("-") || sign.equalsIgnoreCase("CR") || sign.equalsIgnoreCase("DB"))) {
            v = v.negate();
        }
        return v;
    }

    /** COMPUTE into S9(10)V99 without ROUNDED: truncate decimals, drop high-order digits. */
    public static BigDecimal s9v2(BigDecimal v) {
        BigDecimal t = v.setScale(2, RoundingMode.DOWN);
        BigDecimal mod = BigDecimal.TEN.pow(10);
        BigDecimal intPart = t.abs().setScale(0, RoundingMode.DOWN);
        if (intPart.compareTo(mod) >= 0) {
            BigDecimal frac = t.abs().subtract(intPart);
            t = intPart.remainder(mod).add(frac).multiply(BigDecimal.valueOf(t.signum()));
        }
        return t;
    }

    /**
     * Edited picture {@code +ZZZ,ZZZ,ZZZ.99} (WS-EDIT-CURRENCY-9-2-F and the BMS PICOUT): fixed
     * leading sign, 9 integer digits with zero suppression and comma insertion, 2 decimals.
     * S9(10)V99 values of 1,000,000,000.00 or more lose their leading digit (F-08).
     */
    public static String currency(BigDecimal v) {
        BigDecimal t = v.setScale(2, RoundingMode.DOWN);
        String digits = String.format("%011d", t.abs().unscaledValue().longValue() % 100_000_000_000L);
        String intDigits = digits.substring(0, 9);
        String frac = digits.substring(9);
        StringBuilder out = new StringBuilder();
        boolean significant = false;
        for (int i = 0; i < 9; i++) {
            char c = intDigits.charAt(i);
            if (i == 3 || i == 6) {
                out.append(significant ? ',' : ' '); // comma only after a significant digit
            }
            if (c != '0') {
                significant = true;
            }
            out.append(significant ? c : ' ');
        }
        String sign = t.signum() < 0 ? "-" : "+";
        return sign + out + "." + frac;
    }
}
