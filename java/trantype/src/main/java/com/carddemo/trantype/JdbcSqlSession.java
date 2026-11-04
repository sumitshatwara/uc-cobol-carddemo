package com.carddemo.trantype;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;

public final class JdbcSqlSession implements SqlSession {
    private final Connection connection;
    private String preparedText = "";

    public JdbcSqlSession(Connection connection) throws SQLException {
        this.connection = Objects.requireNonNull(connection);
        this.connection.setAutoCommit(false);
    }

    @Override
    public int executeUpdate(String sql, String... params) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < params.length; index++) {
                statement.setString(index + 1, params[index]);
            }
            statement.executeUpdate();
            return 0;
        } catch (SQLException exception) {
            return SqlCodeTranslator.sqlcode(exception);
        }
    }

    @Override
    public int prepare(String text) {
        preparedText = text == null ? "" : text;
        return 0;
    }

    @Override
    public int executePrepared() {
        if (preparedText.isEmpty() || preparedText.isBlank()) {
            return -212;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute(preparedText);
            return 0;
        } catch (SQLException exception) {
            return SqlCodeTranslator.sqlcode(exception);
        }
    }

    @Override
    public int commit() {
        try {
            connection.commit();
            return 0;
        } catch (SQLException exception) {
            return SqlCodeTranslator.sqlcode(exception);
        }
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException ignored) {
        }
    }
}
