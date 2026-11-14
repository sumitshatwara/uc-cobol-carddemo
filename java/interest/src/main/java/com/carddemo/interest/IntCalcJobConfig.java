package com.carddemo.interest;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/** Spring Batch job {@code intCalcJob} = JCL job INTCALC; one step {@code STEP15} = CBACT04C. */
@Configuration
public class IntCalcJobConfig {

    @Bean
    public Job intCalcJob(JobRepository jobRepository, Step intCalcStep15) {
        return new JobBuilder("intCalcJob", jobRepository).start(intCalcStep15).build();
    }

    @Bean
    public Step intCalcStep15(JobRepository jobRepository, PlatformTransactionManager transactionManager, IntCalcStep15Tasklet tasklet) {
        return new StepBuilder(InterestChainHandler.STEP, jobRepository).tasklet(tasklet, transactionManager).build();
    }
}
