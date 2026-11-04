package com.carddemo.trantype;

public interface SqlSession extends AutoCloseable {
    int executeUpdate(String sql, String... params);

    int prepare(String text);

    int executePrepared();

    int commit();

    @Override
    void close();
}
