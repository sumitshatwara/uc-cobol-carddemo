package com.carddemo.common.codec;

/** Storage class of an elementary copybook item. */
public enum FieldKind {
    /** PIC X / A, any usage. */
    TEXT,
    /** PIC 9 USAGE DISPLAY: zoned decimal, sign overpunched on the last byte (EBCDIC convention). */
    DISPLAY,
    /** USAGE COMP-3 / PACKED-DECIMAL. */
    COMP3,
    /** USAGE COMP / BINARY / COMP-4 / COMP-5, big-endian. */
    COMP
}
