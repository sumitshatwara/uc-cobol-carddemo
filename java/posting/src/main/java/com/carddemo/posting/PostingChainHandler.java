package com.carddemo.posting;

import com.carddemo.common.codec.Copybook;
import com.carddemo.common.codec.CopybookField;
import com.carddemo.common.codec.CopybookParser;
import com.carddemo.common.codec.FieldKind;
import com.carddemo.common.codec.RecordCodec;
import com.carddemo.common.entity.DatasetTables;
import com.carddemo.common.run.ChainHandler;
import com.carddemo.common.run.ChainRun;
import com.carddemo.common.run.SysoutWriter;
import com.carddemo.posting.post.DailyTranRecord;
import com.carddemo.posting.post.JdbcPostingStore;
import com.carddemo.posting.post.PostTranProgram;
import com.carddemo.posting.post.Validation;
import com.carddemo.posting.report.JdbcReportLookups;
import com.carddemo.posting.report.TranReptSort;
import com.carddemo.posting.report.TransactionReport;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.support.ListItemReader;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Chain {@code posting}: Spring Batch job {@code postTranJob} (POSTTRAN STEP15 = CBTRN02C) then
 * {@code tranReptJob} (TRANREPT PRC001 REPRO, STEP05R SORT, STEP10R = CBTRN03C).
 */
@Component
public class PostingChainHandler implements ChainHandler {

    static final String DALYTRAN = "AWS.M2.CARDDEMO.DALYTRAN.PS";
    static final String DATEPARM = "AWS.M2.CARDDEMO.DATEPARM";
    static final String DALYREJS = "AWS.M2.CARDDEMO.DALYREJS.G0001V00";
    static final String TRANREPT = "AWS.M2.CARDDEMO.TRANREPT.G0001V00";
    /** Setup-job steps the harness merges into POSTTRAN's rc.json (DEFGDGB, DALYREJS, ACCTFILE ... TRANFILE). */
    static final List<String> SETUP_STEPS = List.of("CLCIFIL", "OPCIFIL", "STEP05", "STEP10", "STEP20", "STEP25", "STEP30");

    private static final AtomicLong RUN_SEQ = new AtomicLong();

    private final JobRepository jobRepository;
    private final PlatformTransactionManager txManager;
    private final JobLauncher jobLauncher;
    private final CopybookParser copybooks;

    public PostingChainHandler(JobRepository jobRepository, PlatformTransactionManager txManager, JobLauncher jobLauncher,
                               CopybookParser copybooks) {
        this.jobRepository = jobRepository;
        this.txManager = txManager;
        this.jobLauncher = jobLauncher;
        this.copybooks = copybooks;
    }

    @Override
    public String chain() {
        return "posting";
    }

    @Override
    public void run(ChainRun run) throws Exception {
        for (String s : SETUP_STEPS) {
            run.stepRc("POSTTRAN", s, 0);
        }
        int rc15 = launch(postTranJob(run), run, "POSTTRAN", "STEP15");
        // Output datasets of POSTTRAN (written even when the step abends: the files were open I-O).
        run.datasets().exportTable("AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS", DatasetTables.TRANSACT);
        run.datasets().exportTable("AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS", DatasetTables.ACCOUNT);
        run.datasets().exportTable("AWS.M2.CARDDEMO.TCATBALF.VSAM.KSDS", DatasetTables.TRAN_CAT_BALANCE);
        if (rc15 >= CobolAbend.ABEND_RC) {
            return; // an abended POSTTRAN stops the chain
        }
        launch(tranReptJob(run), run, "TRANREPT", "STEP10R");
    }

    private int launch(Job job, ChainRun run, String jclJob, String programStep) throws Exception {
        JobExecution ex = jobLauncher.run(job, new JobParametersBuilder()
                .addString("case", run.caseName())
                .addLong("seq", RUN_SEQ.incrementAndGet())
                // the job repository is persistent: a JVM-local counter alone would repeat across restarts
                .addString("runId", UUID.randomUUID().toString())
                .toJobParameters());
        if (ex.getStatus() == BatchStatus.COMPLETED) {
            return currentRc(ex);
        }
        Throwable cause = ex.getAllFailureExceptions().stream().findFirst().orElse(null);
        while (cause != null && !(cause instanceof CobolAbend) && cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        if (cause instanceof CobolAbend) {
            run.stepRc(jclJob, programStep, CobolAbend.ABEND_RC);
            return CobolAbend.ABEND_RC;
        }
        throw new IllegalStateException(job.getName() + " failed: " + ex.getAllFailureExceptions());
    }

    private static int currentRc(JobExecution ex) {
        return ex.getExecutionContext().getInt("rc", 0);
    }

    /** POSTTRAN: one chunk-oriented step STEP15 (reader: DALYTRAN; processor: validation; writer: posting/rejects). */
    Job postTranJob(ChainRun run) {
        SysoutWriter sysout = run.sysout("POSTTRAN", "STEP15");
        PostTranProgram program = new PostTranProgram(new JdbcPostingStore(run.jdbc()), sysout, run.clock().db2Timestamp());
        List<String> daily = run.inputRecords(DALYTRAN);
        var step = new StepBuilder("STEP15", jobRepository)
                .<DailyTranRecord, Validation>chunk(1, txManager)
                .reader(new ListItemReader<>(daily.stream().map(DailyTranRecord::new).toList()))
                .processor(program::validate)
                .writer(chunk -> chunk.forEach(program::write))
                .listener(new StepExecutionListener() {
                    @Override
                    public void beforeStep(StepExecution se) {
                        program.start();
                    }

                    @Override
                    public ExitStatus afterStep(StepExecution se) {
                        if (se.getStatus() == BatchStatus.COMPLETED) {
                            int rc = program.finish();
                            run.stepRc("POSTTRAN", "STEP15", rc);
                            se.getJobExecution().getExecutionContext().putInt("rc", rc);
                        }
                        run.datasets().writeRecords(DALYREJS, "REJTRAN", PostTranProgram.REJECT_LRECL, program.rejects());
                        return se.getExitStatus();
                    }
                })
                .build();
        return new JobBuilder("postTranJob", jobRepository).start(step).build();
    }

    /** TRANREPT: PRC001 (REPRO unload), STEP05R (SORT), STEP10R (CBTRN03C). */
    Job tranReptJob(ChainRun run) {
        AtomicReference<List<String>> backup = new AtomicReference<>(List.of());
        AtomicReference<List<String>> daly = new AtomicReference<>(List.of());
        AtomicInteger rc = new AtomicInteger();
        var prc001 = new StepBuilder("PRC001", jobRepository).tasklet((contribution, ctx) -> {
            backup.set(unloadTransact(run));
            run.stepRc("TRANREPT", "PRC001", 0);
            return RepeatStatus.FINISHED;
        }, txManager).build();
        var step05r = new StepBuilder("STEP05R", jobRepository).tasklet((contribution, ctx) -> {
            daly.set(TranReptSort.sort(backup.get()));
            run.stepRc("TRANREPT", "STEP05R", 0);
            return RepeatStatus.FINISHED;
        }, txManager).build();
        SysoutWriter sysout = run.sysout("TRANREPT", "STEP10R");
        TransactionReport report = new TransactionReport(new JdbcReportLookups(run.jdbc()), sysout);
        var step10r = new StepBuilder("STEP10R", jobRepository).tasklet((contribution, ctx) -> {
            try {
                report.run(daly.get(), run.inputRecords(DATEPARM));
                run.stepRc("TRANREPT", "STEP10R", rc.get());
            } finally {
                run.datasets().writeRecords(TRANREPT, "text", TransactionReport.LRECL, report.lines());
            }
            return RepeatStatus.FINISHED;
        }, txManager).build();
        return new JobBuilder("tranReptJob", jobRepository).start(prc001).next(step05r).next(step10r).build();
    }

    /** IDCAMS REPRO of TRANSACT.VSAM.KSDS (key order) to the 350-byte backup generation. @see "BR-POST-030" */
    List<String> unloadTransact(ChainRun run) {
        Copybook cb = copybooks.get(DatasetTables.TRANSACT.copybook());
        List<String> recs = new ArrayList<>();
        run.jdbc().query("SELECT * FROM " + DatasetTables.TRANSACT.qualified() + " ORDER BY " + DatasetTables.TRANSACT.orderBy(), rs -> {
            Map<String, Object> row = new LinkedHashMap<>();
            for (CopybookField f : cb.namedFields().values()) {
                Object v = rs.getObject(f.name().toLowerCase().replace('-', '_'));
                if (f.kind() != FieldKind.TEXT && v != null && !(v instanceof BigDecimal)) {
                    v = new BigDecimal(v.toString());
                }
                row.put(f.name(), v);
            }
            recs.add(RecordCodec.encodeLine(cb, row));
        });
        return recs;
    }
}
