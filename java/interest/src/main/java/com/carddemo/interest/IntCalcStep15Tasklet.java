package com.carddemo.interest;

import com.carddemo.common.run.ChainRun;
import com.carddemo.common.run.SysoutWriter;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.stereotype.Component;

/**
 * INTCALC {@code //STEP15 EXEC PGM=CBACT04C,PARM='2022071800'} as a tasklet: the control-break
 * loop of CBACT04C does not chunk cleanly, so the whole program runs once inside the step.
 * A user abend is not rethrown: the step completes with exit code {@code ABEND} so the account
 * rewrites done before it stay committed, as on z/OS (BR-INT-048); the handler records RC 12.
 */
@Component
public class IntCalcStep15Tasklet implements Tasklet {

    private final IntCalcRunContext context;

    public IntCalcStep15Tasklet(IntCalcRunContext context) {
        this.context = context;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        ChainRun run = context.run();
        JdbcIntCalcFiles files = new JdbcIntCalcFiles(run.jdbc());
        SysoutWriter sysout = run.sysout(InterestChainHandler.JOB, InterestChainHandler.STEP);
        Cbact04c program = new Cbact04c(files, sysout::display, Cbact04c.JCL_PARM, run.clock()::db2Timestamp);
        int rc = program.run();
        context.complete(new IntCalcRunContext.Outcome(rc, files.transactions(), program.categories(), program.rewrittenAccounts()));
        contribution.setExitStatus(rc == 0 ? ExitStatus.COMPLETED : new ExitStatus("ABEND", "CBACT04C user abend U0999, RC " + rc));
        return RepeatStatus.FINISHED;
    }
}
