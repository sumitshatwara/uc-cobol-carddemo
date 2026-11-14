package com.carddemo.trantype;

import com.carddemo.trantype.db2.SqlCodes;
import com.carddemo.trantype.program.CardDemoProgram;
import com.carddemo.trantype.program.CobolText;
import com.carddemo.trantype.program.ParmArea;
import com.carddemo.trantype.program.ProgramNotFoundException;
import com.carddemo.trantype.program.RunUnit;
import com.carddemo.trantype.program.StopRun;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The TSO {@code DSN} command processor (off-host: {@code tools/stubs/DSNRUN.cbl} + {@code DSNEXIT.cbl}):
 * attaches Db2, runs the program with the PARMS area, and commits the unit of work at normal end
 * of task, i.e. after STOP RUN or the main program's GOBACK.  The step RC is the run unit's
 * RETURN-CODE at that point.
 *
 * @see "BR-TT-022, BR-TT-023, BR-TT-034, BR-TT-037"
 */
public final class DsnRunner {

    private static final Logger log = LoggerFactory.getLogger(DsnRunner.class);

    /** RC of a run unit ended by a failed dynamic CALL (libcob runtime error, {@code cob_stop_run(1)}). */
    public static final int RC_CALL_FAILED = 1;

    private DsnRunner() {}

    public static int run(DsnCommand cmd, RunUnit ru) {
        ru.display("DSNRUN: DSN ATTACHED, RUNNING ", CobolText.picX(cmd.program(), 8));
        try {
            CardDemoProgram main = ru.programs().resolve(cmd.program());
            main.call(ru, ParmArea.of(cmd.parms()));
        } catch (StopRun stop) {
            log.debug("{}: STOP RUN rc={}", stop.program(), ru.returnCode());
        } catch (ProgramNotFoundException e) {
            // libcob: "module '...' not found" on stderr, then STOP RUN with status 1 (exit procs still run)
            log.warn("{}: {}", cmd.program(), e.getMessage());
            ru.returnCode(RC_CALL_FAILED);
        }
        // DSNEXIT (CBL_EXIT_PROC): end-of-task commit
        int sqlcode = ru.db2().commitAtEndOfTask();
        if (sqlcode != SqlCodes.OK) {
            ru.display("DSNRUN: COMMIT FAILED ", CobolText.signed10(sqlcode));
        } else {
            ru.display("DSNRUN: COMMIT AT END OF TASK");
        }
        return ru.returnCode();
    }
}
