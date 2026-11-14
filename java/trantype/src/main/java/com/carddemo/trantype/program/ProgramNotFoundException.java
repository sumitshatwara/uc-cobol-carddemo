package com.carddemo.trantype.program;

/** A dynamic CALL named a program that is not in the load library (no ON EXCEPTION in the caller). */
public class ProgramNotFoundException extends RuntimeException {
    public ProgramNotFoundException(String name) {
        super("module '" + name + "' not found");
    }
}
