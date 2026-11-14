package com.carddemo.trantype.program;

import com.carddemo.common.run.SysoutWriter;
import com.carddemo.trantype.db2.Db2Session;
import java.util.Map;

/**
 * One COBOL run unit under a DSN attach: the shared {@code RETURN-CODE} special register,
 * the step's SYSOUT, its input DDs, the Db2 connection (one unit of work) and the load
 * library used for dynamic CALLs.
 *
 * @see "BR-TT-037 (one RETURN-CODE shared by caller and callee)"
 */
public class RunUnit {

    private final SysoutWriter sysout;
    private final Db2Session db2;
    private final Map<String, InputFile> dds;
    private final ProgramRegistry programs;
    private int returnCode;

    public RunUnit(SysoutWriter sysout, Db2Session db2, Map<String, InputFile> dds, ProgramRegistry programs) {
        this.sysout = sysout;
        this.db2 = db2;
        this.dds = dds;
        this.programs = programs;
    }

    /** One COBOL {@code DISPLAY}. */
    public void display(Object... operands) {
        sysout.display(operands);
    }

    public SysoutWriter sysout() { return sysout; }
    public Db2Session db2() { return db2; }
    public ProgramRegistry programs() { return programs; }

    public InputFile dd(String ddName) {
        InputFile f = dds.get(ddName);
        return f == null ? InputFile.missing() : f;
    }

    public int returnCode() { return returnCode; }

    /** {@code MOVE n TO RETURN-CODE}. */
    public void returnCode(int rc) { this.returnCode = rc; }
}
