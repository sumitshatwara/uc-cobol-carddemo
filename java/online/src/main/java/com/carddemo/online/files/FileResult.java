package com.carddemo.online.files;

public record FileResult<T>(int resp, int resp2, T record) {
    public static final int NORMAL = 0;
    public static final int NOTFND = 13;

    public boolean normal() {
        return resp == NORMAL;
    }
}
