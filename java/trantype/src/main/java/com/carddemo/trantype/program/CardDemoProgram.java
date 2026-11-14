package com.carddemo.trantype.program;

/**
 * A legacy load module that can be the target of a dynamic {@code CALL} (or of
 * {@code DSN RUN PROGRAM(...)}).  Implementations are Spring beans registered in
 * {@link ProgramRegistry} under their legacy program name.
 */
public interface CardDemoProgram {

    /** Legacy PROGRAM-ID, e.g. {@code COBTUPDT}. */
    String programId();

    /**
     * Runs the program in the given run unit.  Returning normally is {@code GOBACK};
     * {@code STOP RUN} throws {@link StopRun}.
     */
    void call(RunUnit runUnit, ParmArea parm);
}
