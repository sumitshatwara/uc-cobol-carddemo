package com.carddemo.common.codec;

/**
 * One elementary item of a flattened copybook: byte offset and length in the
 * record, storage kind, decimal scale (digits after the implied V), sign and
 * total digit count.  OCCURS items are expanded as {@code NAME[1]}, {@code NAME[2]}...
 */
public record CopybookField(String name, int offset, int length, FieldKind kind, int scale, boolean signed, int digits) {

    public boolean isFiller() {
        return name.toUpperCase().startsWith("FILLER");
    }

    public CopybookField withName(String newName) {
        return new CopybookField(newName, offset, length, kind, scale, signed, digits);
    }

    public CopybookField shifted(String newName, int delta) {
        return new CopybookField(newName, offset + delta, length, kind, scale, signed, digits);
    }
}
