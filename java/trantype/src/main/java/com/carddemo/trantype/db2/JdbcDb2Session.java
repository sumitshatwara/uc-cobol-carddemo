package com.carddemo.trantype.db2;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link Db2Session} on one PostgreSQL JDBC connection with autocommit off: one transaction for
 * the whole job step, committed at end of task.  PostgreSQL's own semantics are kept on purpose:
 * after a failed statement the transaction is aborted, every later statement fails with SQLSTATE
 * 25P02 (SQLCODE -400) and the final COMMIT is answered with ROLLBACK, which the ESQL driver
 * reports as SQLCODE 0.  No savepoints (pgjdbc {@code autosave=never}).
 *
 * @see "BR-TT-023, BR-TT-024, BR-TT-038"
 */
public class JdbcDb2Session implements Db2Session {

    private static final Logger log = LoggerFactory.getLogger(JdbcDb2Session.class);

    private final Connection conn;
    private final Map<String, String> prepared = new HashMap<>();

    /**
     * @param qualifier the plan's default qualifier for unqualified table names (Db2 BIND
     *                  QUALIFIER; off-host the connecting user's schema, {@code carddemo})
     */
    public JdbcDb2Session(Connection conn, String qualifier) throws SQLException {
        this.conn = conn;
        conn.setAutoCommit(false);
        try (Statement s = conn.createStatement()) {
            s.execute("SET LOCAL search_path TO " + qualifier + ", public");
        }
    }

    @Override
    public int execute(String sql, String... hostVariables) {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < hostVariables.length; i++) {
                ps.setString(i + 1, hostVariables[i]);
            }
            ps.executeUpdate();
            return SqlCodes.OK;
        } catch (SQLException e) {
            log.info("SQL failed ({}): {}", e.getSQLState(), e.getMessage());
            return SqlCodes.of(e);
        }
    }

    /** The ESQL driver keeps the statement text; nothing reaches the server before EXECUTE. */
    @Override
    public int prepare(String statementName, String text) {
        prepared.put(statementName, text);
        return SqlCodes.OK;
    }

    @Override
    public int executePrepared(String statementName) {
        String text = prepared.get(statementName);
        if (text == null) {
            return -230; // OCPG_INVALID_STMT
        }
        try (Statement s = conn.createStatement()) {
            s.execute(text);
            return SqlCodes.OK;
        } catch (SQLException e) {
            log.info("dynamic SQL failed ({}): {}", e.getSQLState(), e.getMessage());
            return SqlCodes.of(e);
        }
    }

    @Override
    public int commitAtEndOfTask() {
        try {
            conn.commit();
            return SqlCodes.OK;
        } catch (SQLException e) {
            if ("25P02".equals(e.getSQLState()) || String.valueOf(e.getMessage()).contains("returned ROLLBACK")) {
                // PostgreSQL answered COMMIT with ROLLBACK (aborted transaction): reported as success.
                return SqlCodes.OK;
            }
            return SqlCodes.of(e);
        }
    }
}
