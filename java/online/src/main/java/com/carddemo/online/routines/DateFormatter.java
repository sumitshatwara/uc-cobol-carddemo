package com.carddemo.online.routines;

public class DateFormatter {
    public int convert(CodatecnRecord record) {
        String input = fixed(record.inDate, 20);
        if ("1".equals(record.inType)) {
            if (!"2".equals(record.outType)) {
                record.errMsg = overlay(record.errMsg, "INVALID INPUT", 38);
                return 0;
            }
            // Finding: the 20-byte CLC does not match its one-byte hyphen literal.
            record.outDate = overlay(record.outDate,
                    input.substring(0, 4) + "-" + input.substring(4, 6) + "-"
                            + input.substring(6, 8), 20);
        } else if ("2".equals(record.inType)) {
            if ("1".equals(record.outType)) {
                record.errMsg = overlay(record.errMsg, "INVALID INPUT", 38);
                return 0;
            }
            record.outDate = overlay(record.outDate,
                    input.substring(0, 4) + input.substring(5, 7) + input.substring(8, 10), 20);
        } else {
            record.errMsg = overlay(record.errMsg, "INVALID INPUT", 38);
        }
        return 0;
    }

    private static String overlay(String original, String replacement, int width) {
        String target = fixed(original, width);
        StringBuilder result = new StringBuilder(target);
        for (int i = 0; i < replacement.length() && i < width; i++) {
            result.setCharAt(i, replacement.charAt(i));
        }
        return result.toString();
    }

    private static String fixed(String value, int width) {
        String source = value == null ? "" : value;
        return source.length() >= width ? source.substring(0, width)
                : source + " ".repeat(width - source.length());
    }

    public static class CodatecnRecord {
        public String inType = " ";
        public String inDate = " ".repeat(20);
        public String outType = " ";
        public String outDate = " ".repeat(20);
        public String errMsg = " ".repeat(38);
    }
}
