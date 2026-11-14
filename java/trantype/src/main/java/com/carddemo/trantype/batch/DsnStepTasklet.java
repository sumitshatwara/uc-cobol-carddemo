package com.carddemo.trantype.batch;

import com.carddemo.common.repo.RepoPaths;
import com.carddemo.common.run.ChainRun;
import com.carddemo.trantype.DsnCommand;
import com.carddemo.trantype.DsnRunner;
import com.carddemo.trantype.db2.JdbcDb2Session;
import com.carddemo.trantype.program.InputFile;
import com.carddemo.trantype.program.ProgramRegistry;
import com.carddemo.trantype.program.RunUnit;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;

/**
 * Tasklet of an {@code EXEC PGM=IKJEFT01} step: reads the DSN RUN subcommand from the job's JCL,
 * opens one JDBC connection (one unit of work for the whole step) and runs the program through
 * {@link DsnRunner}.  The connection is taken straight from the pool, outside Spring Batch's
 * step transaction, so the job repository's metadata commits never touch the Db2 unit of work.
 */
public class DsnStepTasklet implements Tasklet {

    /** Db2 BIND QUALIFIER of plan CARDDEMO: unqualified dynamic SQL names resolve to this schema. */
    static final String QUALIFIER = "carddemo";
    static final String JCL_DIR = "app/app-transaction-type-db2/jcl";

    private final String job;
    private final String step;
    private final DataSource dataSource;
    private final ProgramRegistry programs;
    private final TrantypeRunContexts contexts;
    private final RepoPaths repo;

    public DsnStepTasklet(String job, String step, DataSource dataSource, ProgramRegistry programs,
                          TrantypeRunContexts contexts, RepoPaths repo) {
        this.job = job;
        this.step = step;
        this.dataSource = dataSource;
        this.programs = programs;
        this.contexts = contexts;
        this.repo = repo;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) throws Exception {
        String key = (String) chunkContext.getStepContext().getJobParameters().get("runKey");
        TrantypeRunContexts.Slot slot = contexts.get(key);
        ChainRun run = slot.run();
        DsnCommand cmd = DsnCommand.fromJcl(jclPath(repo.root(), job));
        Map<String, InputFile> dds = new LinkedHashMap<>();
        for (String dd : run.chain().inputs().keySet()) {
            dds.put(dd, InputFile.of(run.inputRecords(dd)));
        }
        try (Connection conn = dataSource.getConnection()) {
            try {
                RunUnit ru = new RunUnit(run.sysout(job, step), new JdbcDb2Session(conn, QUALIFIER), dds, programs);
                int rc = DsnRunner.run(cmd, ru);
                slot.rc(rc);
                contribution.setExitStatus(new org.springframework.batch.core.ExitStatus("RC" + rc));
            } finally {
                if (!conn.getAutoCommit()) {
                    conn.rollback(); // no-op after the end-of-task commit; discards work on a failure
                    conn.setAutoCommit(true);
                }
            }
        }
        return RepeatStatus.FINISHED;
    }

    static Path jclPath(Path root, String job) {
        return root.resolve(JCL_DIR).resolve(job + ".jcl");
    }
}
