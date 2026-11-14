package com.carddemo.trantype.batch;

import com.carddemo.common.run.ChainHandler;
import com.carddemo.common.run.ChainRun;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;

/** Runs one trantype job for a chain and reports its step RC to the foundation. */
abstract class DsnChainHandler implements ChainHandler {

    private final JobLauncher launcher;
    private final Job job;
    private final String legacyJob;
    private final TrantypeRunContexts contexts;

    DsnChainHandler(JobLauncher launcher, Job job, String legacyJob, TrantypeRunContexts contexts) {
        this.launcher = launcher;
        this.job = job;
        this.legacyJob = legacyJob;
        this.contexts = contexts;
    }

    @Override
    public void run(ChainRun run) throws Exception {
        String key = contexts.open(run);
        try {
            JobExecution ex = launcher.run(job, new JobParametersBuilder()
                    .addString("runKey", key)
                    .addString("case", run.caseName())
                    .toJobParameters());
            TrantypeRunContexts.Slot slot = contexts.get(key);
            if (ex.getStatus() != BatchStatus.COMPLETED || slot.rc() == null) {
                Throwable cause = ex.getAllFailureExceptions().stream().findFirst().orElse(null);
                throw new IllegalStateException(job.getName() + " ended " + ex.getStatus(), cause);
            }
            run.stepRc(legacyJob, TrantypeJobsConfig.STEP1, slot.rc());
        } finally {
            contexts.close(key);
        }
    }
}
