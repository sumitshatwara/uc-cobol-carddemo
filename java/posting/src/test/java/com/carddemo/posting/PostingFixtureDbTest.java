package com.carddemo.posting;

import com.carddemo.common.clock.FrozenClock;
import com.carddemo.common.estate.ChainConfig;
import com.carddemo.common.export.OutputWriter;
import com.carddemo.common.fixture.FixtureLoader;
import com.carddemo.common.run.RunContext;
import com.carddemo.common.testsupport.DbTest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class PostingFixtureDbTest {
    private static final String[] FIXTURES = {
            "p01-baseline-mixed", "p02-reject-invalid-card", "p03-expired-and-overlimit",
            "p04-overlimit-reject", "p05-cycle-limit", "p06-duplicate-transid",
            "p07-empty-day"
    };

    @TempDir
    Path temporaryDirectory;
    private FixtureLoader fixtureLoader;
    private ChainConfig.Chain chain;

    @ParameterizedTest
    @ValueSource(strings = {
            "p01-baseline-mixed", "p02-reject-invalid-card", "p03-expired-and-overlimit",
            "p04-overlimit-reject", "p05-cycle-limit", "p06-duplicate-transid",
            "p07-empty-day"
    })
    @DbTest
    void runsPostingHandlerAgainstEachDatabaseFixture(String caseName) throws Exception {
        assertThat(FIXTURES).contains(caseName);
        DriverManagerDataSource source = new DriverManagerDataSource(
                System.getenv().getOrDefault("CARDEMO_DB_URL",
                        "jdbc:postgresql://localhost:5432/carddemo_java?currentSchema=carddemo,public"),
                System.getenv().getOrDefault("CARDEMO_DB_USER", "carddemo"),
                System.getenv().getOrDefault("CARDEMO_DB_PASSWORD", "carddemo"));
        Flyway.configure().dataSource(source).locations("classpath:db/migration")
                .schemas("carddemo").defaultSchema("carddemo").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(source);
        ChainConfig config = new ChainConfig();
        chain = config.get("posting");
        Path caseDirectory = config.root().resolve("fixtures/posting").resolve(caseName);
        fixtureLoader = new FixtureLoader(jdbc, source);
        fixtureLoader.load("posting", chain, caseDirectory, java.util.List.of());

        Path outputDirectory = temporaryDirectory.resolve(caseName);
        RunContext context = new RunContext(chain, caseName, caseDirectory, outputDirectory, fixtureLoader,
                new OutputWriter(outputDirectory, jdbc, source), new FrozenClock(chain.clock()), jdbc, null, caseName);
        PostingHandler handler = new PostingHandler(new DailyPostingService(new JdbcPostingStore(jdbc)),
                new TransactionReportService(new JdbcReportLookups(jdbc)));
        var result = handler.run(context);
        context.outputWriter().writeRcJson(result.rc());

        String manifest = Files.readString(outputDirectory.resolve("manifest.json"));
        assertThat(manifest).contains("AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS",
                "AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS", "AWS.M2.CARDDEMO.TCATBALF.VSAM.KSDS",
                "AWS.M2.CARDDEMO.DALYREJS.G0001V00", "AWS.M2.CARDDEMO.TRANREPT.G0001V00");
        assertThat(Files.isRegularFile(outputDirectory.resolve("rc.json"))).isTrue();
    }

    @AfterEach
    void leaveDatabaseClean() {
        if (fixtureLoader != null) {
            fixtureLoader.load("posting", chain, temporaryDirectory.resolve("empty-case"), java.util.List.of());
        }
    }
}
