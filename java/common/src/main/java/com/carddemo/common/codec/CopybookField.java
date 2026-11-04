package com.carddemo.common.codec;

public record CopybookField(
        String name,
        int offset,
        int length,
        Kind kind,
        int scale,
        boolean signed,
        int digits) {

    public enum Kind {
        TEXT,
        DISPLAY,
        COMP3,
        COMP
    }

    public boolean isFiller() {
        return name.toUpperCase().startsWith("FILLER");
    }
}
