package com.carddemo.trantype.batch;

import com.carddemo.common.repo.RepoPaths;
import com.carddemo.trantype.program.ProgramRegistry;
import javax.sql.DataSource;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.support.transaction.ResourcelessTransactionManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * One Spring Batch job per legacy JCL job, one tasklet step per JCL step:
 * {@code mntTrDb2Job} = MNTTRDB2 STEP1 (DSN RUN PROGRAM(COBTUPDT)) and
 * {@code mntTrDynJob} = MNTTRDYN STEP1 (DSN RUN PROGRAM(CBTRNDYN) PARMS('COBTUPDT')).
 * The step's own transaction manager is resourceless: the Db2 unit of work is the tasklet's
 * JDBC connection, committed at end of task.
 */
@Configuration
public class TrantypeJobsConfig {

    public static final String MNTTRDB2 = "MNTTRDB2";
    public static final String MNTTRDYN = "MNTTRDYN";
    public static final String STEP1 = "STEP1";

    @Bean
    public Job mntTrDb2Job(JobRepository jobRepository, DataSource dataSource, ProgramRegistry programs,
                           TrantypeRunContexts contexts, RepoPaths repo) {
        return job("mntTrDb2Job", MNTTRDB2, jobRepository, dataSource, programs, contexts, repo);
    }

    @Bean
    public Job mntTrDynJob(JobRepository jobRepository, DataSource dataSource, ProgramRegistry programs,
                           TrantypeRunContexts contexts, RepoPaths repo) {
        return job("mntTrDynJob", MNTTRDYN, jobRepository, dataSource, programs, contexts, repo);
    }

    private static Job job(String name, String legacyJob, JobRepository jobRepository, DataSource dataSource,
                           ProgramRegistry programs, TrantypeRunContexts contexts, RepoPaths repo) {
        var step = new StepBuilder(STEP1, jobRepository)
                .tasklet(new DsnStepTasklet(legacyJob, STEP1, dataSource, programs, contexts, repo),
                        new ResourcelessTransactionManager())
                .build();
        return new JobBuilder(name, jobRepository).start(step).build();
    }
}
