package com.carddemo.trantype.batch;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/** Chain {@code trantype-dynamic}: job MNTTRDYN ({@code mntTrDynJob}). */
@Component
public class TrantypeDynamicChain extends DsnChainHandler {

    public TrantypeDynamicChain(JobLauncher launcher, @Qualifier("mntTrDynJob") Job job, TrantypeRunContexts contexts) {
        super(launcher, job, TrantypeJobsConfig.MNTTRDYN, contexts);
    }

    @Override
    public String chain() {
        return "trantype-dynamic";
    }
}
