package com.carddemo.online.store;

/**
 * Outcome of a CICS file request: {@code RESP}, {@code RESP2} and the record read (null unless
 * {@code resp == NORMAL}).
 */
public record FileResult<T>(int resp, int resp2, T record) {

    public static <T> FileResult<T> ok(T record) {
        return new FileResult<>(Resp.NORMAL, 0, record);
    }

    public static <T> FileResult<T> notFound() {
        return new FileResult<>(Resp.NOTFND, Resp.NOTFND_RESP2, null);
    }

    public static <T> FileResult<T> error(int resp, int resp2) {
        return new FileResult<>(resp, resp2, null);
    }

    public boolean normal() {
        return resp == Resp.NORMAL;
    }
}
