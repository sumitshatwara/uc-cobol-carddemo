package com.carddemo.trantype;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.function.Function;

public final class DsnRunService {
    public int run(DataSource dataSource, String program, String parms, List<String> sysout,
                   Function<SqlSession, Integer> body) {
        Connection connection = null;
        JdbcSqlSession session;
        try {
            connection = dataSource.getConnection();
            session = new JdbcSqlSession(connection);
        } catch (SQLException exception) {
            closeAfterFailure(connection);
            sysout.add("DSNRUN: DATABASE CONNECT FAILED "
                    + SqlCodeTranslator.displaySqlca(SqlCodeTranslator.sqlcode(exception)));
            return 12;
        }

        try {
            sysout.add("DSNRUN: DSN ATTACHED, RUNNING " + fixedProgram(program));
            int returnCode = body.apply(session);
            int commitCode = session.commit();
            if (commitCode == 0) {
                sysout.add("DSNRUN: COMMIT AT END OF TASK");
            } else {
                sysout.add("DSNRUN: COMMIT FAILED " + SqlCodeTranslator.displaySqlca(commitCode));
            }
            return returnCode;
        } finally {
            session.close();
        }
    }

    private static String fixedProgram(String program) {
        String value = program == null ? "" : program;
        return value.length() > 8 ? value.substring(0, 8) : String.format("%-8s", value);
    }

    private static void closeAfterFailure(Connection connection) {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
            }
        }
    }
}
