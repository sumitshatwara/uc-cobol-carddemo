package com.carddemo.online.commarea;

public final class CardDemoCommarea {
    public String fromTranId = "";
    public String fromProgram = "";
    public String toTranId = "";
    public String toProgram = "";
    public String userId = "";
    public String userType = "";
    public int pgmContext;
    public String custId = "";
    public String custFname = "";
    public String custMname = "";
    public String custLname = "";
    public String acctId = "";
    public String acctStatus = "";
    public String cardNum = "";
    public String lastMap = "";
    public String lastMapset = "";

    public String encode() {
        return text(fromTranId, 4) + text(fromProgram, 8) + text(toTranId, 4)
                + text(toProgram, 8) + text(userId, 8) + text(userType, 1)
                + digits(Integer.toString(pgmContext), 1) + digits(custId, 9)
                + text(custFname, 25) + text(custMname, 25) + text(custLname, 25)
                + digits(acctId, 11) + text(acctStatus, 1) + digits(cardNum, 16)
                + text(lastMap, 7) + text(lastMapset, 7);
    }

    public static CardDemoCommarea decode(String value) {
        if (value == null || value.length() < 160) {
            throw new IllegalArgumentException("COMMAREA must contain at least 160 characters");
        }
        CardDemoCommarea result = new CardDemoCommarea();
        int offset = 0;
        result.fromTranId = slice(value, offset, 4); offset += 4;
        result.fromProgram = slice(value, offset, 8); offset += 8;
        result.toTranId = slice(value, offset, 4); offset += 4;
        result.toProgram = slice(value, offset, 8); offset += 8;
        result.userId = slice(value, offset, 8); offset += 8;
        result.userType = slice(value, offset, 1); offset += 1;
        result.pgmContext = Integer.parseInt(slice(value, offset, 1)); offset += 1;
        result.custId = slice(value, offset, 9); offset += 9;
        result.custFname = slice(value, offset, 25); offset += 25;
        result.custMname = slice(value, offset, 25); offset += 25;
        result.custLname = slice(value, offset, 25); offset += 25;
        result.acctId = slice(value, offset, 11); offset += 11;
        result.acctStatus = slice(value, offset, 1); offset += 1;
        result.cardNum = slice(value, offset, 16); offset += 16;
        result.lastMap = slice(value, offset, 7); offset += 7;
        result.lastMapset = slice(value, offset, 7);
        return result;
    }

    private static String slice(String value, int offset, int width) {
        return value.substring(offset, offset + width);
    }

    private static String text(String value, int width) {
        String source = value == null ? "" : value;
        return source.length() >= width ? source.substring(0, width)
                : source + " ".repeat(width - source.length());
    }

    private static String digits(String value, int width) {
        String source = value == null ? "" : value.trim();
        if (!source.isEmpty() && !source.chars().allMatch(Character::isDigit)) {
            throw new IllegalArgumentException("numeric COMMAREA field contains non-digits");
        }
        if (source.length() > width) {
            source = source.substring(source.length() - width);
        }
        return "0".repeat(width - source.length()) + source;
    }
}
