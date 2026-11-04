package com.carddemo.common.run;

import com.carddemo.common.clock.FrozenClock;
import com.carddemo.common.estate.ChainConfig;
import com.carddemo.common.estate.EstateRoot;
import com.carddemo.common.export.OutputWriter;
import com.carddemo.common.fixture.FixtureLoader;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ChainRunner {
    private final ChainConfig chainConfig;
    private final Map<String, ChainHandler> handlers;
    private final FixtureLoader fixtureLoader;
    private final JdbcTemplate jdbcTemplate;
    private final JobLauncher jobLauncher;
    private final ReentrantLock runLock = new ReentrantLock();

    public ChainRunner(ChainConfig chainConfig, List<ChainHandler> handlers, FixtureLoader fixtureLoader,
                      JdbcTemplate jdbcTemplate, JobLauncher jobLauncher) {
        this.chainConfig = chainConfig;
        this.handlers = handlers.stream().collect(Collectors.toUnmodifiableMap(ChainHandler::chain, Function.identity()));
        this.fixtureLoader = fixtureLoader;
        this.jdbcTemplate = jdbcTemplate;
        this.jobLauncher = jobLauncher;
    }

    public ChainConfig config() {
        return chainConfig;
    }

    public Map<String, ChainHandler> handlers() {
        return handlers;
    }

    public RunResult run(String chainName, String caseName, Path outDir) throws Exception {
        return run(chainName, caseName, outDir, UUID.randomUUID().toString());
    }

    public RunResult run(String chainName, String caseName, Path outDir, String runId) throws Exception {
        ChainConfig.Chain chain = chainConfig.get(chainName);
        if (chain == null || !chainConfig.cases(chainName).contains(caseName)) {
            throw new IllegalArgumentException("unknown chain or fixture case: " + chainName + "/" + caseName);
        }
        ChainHandler handler = handlers.get(chainName);
        if (handler == null) {
            throw new IllegalStateException("no Java handler registered for chain " + chainName);
        }
        runLock.lockInterruptibly();
        try {
            Path caseDirectory = EstateRoot.root().resolve("fixtures").resolve(chainName).resolve(caseName);
            fixtureLoader.load(chainName, chain, caseDirectory, handler.extraTables());
            OutputWriter writer = new OutputWriter(outDir, jdbcTemplate, jdbcTemplate.getDataSource());
            RunContext context = new RunContext(chain, caseName, caseDirectory, outDir, fixtureLoader, writer,
                    new FrozenClock(chain.clock()), jdbcTemplate, jobLauncher, runId);
            RunResult result = handler.run(context);
            writer.writeRcJson(result.rc());
            return result;
        } finally {
            runLock.unlock();
        }
    }
}
