package com.carddemo.interest;

/** 9999-ABEND-PROGRAM: {@code CALL 'CEE3ABD'} ends the step (user abend U0999, harness RC 12). */
public final class AbendException extends RuntimeException {

    private final int abendCode;

    public AbendException(int abendCode) {
        super("user abend U" + String.format("%04d", abendCode));
        this.abendCode = abendCode;
    }

    public int abendCode() { return abendCode; }
}
