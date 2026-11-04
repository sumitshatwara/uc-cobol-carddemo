package com.carddemo.common.run;

import com.carddemo.common.clock.FrozenClock;
import com.carddemo.common.estate.ChainConfig;
import com.carddemo.common.export.OutputWriter;
import com.carddemo.common.fixture.FixtureLoader;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;

public record RunContext(
        ChainConfig.Chain chainConfig,
        String caseName,
        Path caseDirectory,
        Path outDir,
        FixtureLoader fixtureLoader,
        OutputWriter outputWriter,
        FrozenClock clock,
        JdbcTemplate jdbcTemplate,
        JobLauncher jobLauncher,
        String runId) {
}
