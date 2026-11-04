package com.carddemo.trantype;

import java.sql.SQLException;

public final class SqlCodeTranslator {
    private SqlCodeTranslator() {
    }

    public static int sqlcode(SQLException exception) {
        return "23505".equals(exception.getSQLState()) ? -403 : -400;
    }

    public static String displaySqlca(int sqlcode) {
        return String.format("%+011d", sqlcode);
    }

    public static String editMinus4(int sqlcode) {
        long magnitude = Math.abs((long) sqlcode) % 10_000;
        String number = (sqlcode < 0 ? "-" : "") + magnitude;
        return String.format("%5s", number);
    }
}
