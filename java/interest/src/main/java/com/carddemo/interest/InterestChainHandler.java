package com.carddemo.interest;

import com.carddemo.common.entity.DatasetTables;
import com.carddemo.common.run.ChainHandler;
import com.carddemo.common.run.ChainRun;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Chain {@code interest} (tools/runjcl/chains.json): launches {@code intCalcJob} on the fixture
 * case the foundation has loaded, then reports what the legacy harness records:
 * <ul>
 *   <li>{@code rc.json}: INTCALC with the setup jobs' steps STEP05..STEP30 merged in (all 0) and
 *       STEP15 = CBACT04C's return code;</li>
 *   <li>SYSOUT {@code INTCALC.STEP15} (written by the program through {@link Cbact04c});</li>
 *   <li>{@code AWS.M2.CARDDEMO.SYSTRAN.G0001V00} (CVTRA05Y, write order) and
 *       {@code AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS} (CVACT01Y, key order).</li>
 * </ul>
 */
@Component
public class InterestChainHandler implements ChainHandler {

    public static final String CHAIN = "interest";
    public static final String JOB = "INTCALC";
    public static final String STEP = "STEP15";
    public static final String SYSTRAN_DSN = "AWS.M2.CARDDEMO.SYSTRAN.G0001V00";
    public static final String ACCTDATA_DSN = "AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS";
    /** Steps of rc.json: the setup jobs' steps merged into INTCALC by the harness, plus STEP15. */
    static final List<String> RC_STEPS = List.of("STEP05", "STEP10", "STEP15", "STEP20", "STEP25", "STEP30");

    private static final AtomicLong LAUNCHES = new AtomicLong();

    private final JobLauncher jobLauncher;
    private final Job intCalcJob;
    private final IntCalcRunContext context;

    public InterestChainHandler(JobLauncher jobLauncher, @Qualifier("intCalcJob") Job intCalcJob, IntCalcRunContext context) {
        this.jobLauncher = jobLauncher;
        this.intCalcJob = intCalcJob;
        this.context = context;
    }

    @Override
    public String chain() {
        return CHAIN;
    }

    @Override
    public void run(ChainRun run) throws Exception {
        context.start(run);
        try {
            JobParameters params = new JobParametersBuilder()
                    .addString("case", run.caseName())
                    .addString("parm", Cbact04c.JCL_PARM)
                    .addLong("launch", System.currentTimeMillis() * 1000 + LAUNCHES.incrementAndGet() % 1000)
                    .toJobParameters();
            JobExecution exec = jobLauncher.run(intCalcJob, params);
            IntCalcRunContext.Outcome outcome = context.outcome();
            if (exec.getStatus() != BatchStatus.COMPLETED || outcome == null) {
                throw new IllegalStateException("intCalcJob ended " + exec.getStatus() + " " + exec.getAllFailureExceptions());
            }
            for (String step : RC_STEPS) {
                run.stepRc(JOB, step, step.equals(STEP) ? outcome.rc() : 0);
            }
            if (outcome.rc() == 0) {
                // DISP=(NEW,CATLG,DELETE): the new generation is kept only when the step ends normally
                run.datasets().writeFieldMaps(SYSTRAN_DSN, "CVTRA05Y", outcome.transactions().stream().map(InterestTran::toFields).toList());
            }
            run.datasets().exportTable(ACCTDATA_DSN, DatasetTables.ACCOUNT);
        } finally {
            context.clear();
        }
    }
}
