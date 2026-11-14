package com.carddemo.trantype.program;

/**
 * COBOL {@code STOP RUN}: ends the whole run unit (the calling program included) with the
 * current RETURN-CODE.  Thrown by a called program, caught only by the DSN attach
 * ({@link com.carddemo.trantype.DsnRunner}), which then performs the end-of-task commit.
 *
 * @see "BR-TT-022, BR-TT-036"
 */
public final class StopRun extends RuntimeException {

    private final String program;

    public StopRun(String program) {
        super("STOP RUN in " + program, null, false, false);
        this.program = program;
    }

    public String program() {
        return program;
    }
}
