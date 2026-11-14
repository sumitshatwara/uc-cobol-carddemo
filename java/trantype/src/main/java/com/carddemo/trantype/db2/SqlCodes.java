package com.carddemo.trantype.db2;

import java.sql.SQLException;
import java.util.Map;

/**
 * SQLCODE values as Open COBOL ESQL 1.4 reports them for PostgreSQL ({@code dblib/ocpgsql.c}):
 * a handful of SQLSTATEs have their own code, everything else is -400.  These are the values
 * recorded in the golden SYSOUT, not Db2's (Db2 would give -803 for a duplicate key and -206
 * for an unknown column).  A successful INSERT/UPDATE/DELETE is 0 even when no row qualified
 * (the driver never reports +100 for a searched UPDATE or DELETE).
 *
 * @see "BR-TT-014, BR-TT-018, BR-TT-024"
 */
public final class SqlCodes {

    public static final int OK = 0;
    public static final int NOT_FOUND = 100;
    public static final int PGSQL = -400;
    public static final int DUPLICATE_KEY = -403;

    private static final Map<String, Integer> BY_SQLSTATE = Map.of(
            "08001", -402,
            "08003", -220,
            "08007", -401,
            "21000", -404,
            "23505", DUPLICATE_KEY,
            "25001", -603,
            "25P01", -604,
            "34000", -602,
            "42804", -204);

    private SqlCodes() {}

    public static int fromSqlState(String sqlState) {
        if (sqlState == null) {
            return PGSQL;
        }
        return BY_SQLSTATE.getOrDefault(sqlState, PGSQL);
    }

    public static int of(SQLException e) {
        return fromSqlState(e.getSQLState());
    }
}
