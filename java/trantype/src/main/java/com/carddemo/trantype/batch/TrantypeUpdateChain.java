package com.carddemo.trantype.batch;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/** Chain {@code trantype-update}: job MNTTRDB2 ({@code mntTrDb2Job}). */
@Component
public class TrantypeUpdateChain extends DsnChainHandler {

    public TrantypeUpdateChain(JobLauncher launcher, @Qualifier("mntTrDb2Job") Job job, TrantypeRunContexts contexts) {
        super(launcher, job, TrantypeJobsConfig.MNTTRDB2, contexts);
    }

    @Override
    public String chain() {
        return "trantype-update";
    }
}
