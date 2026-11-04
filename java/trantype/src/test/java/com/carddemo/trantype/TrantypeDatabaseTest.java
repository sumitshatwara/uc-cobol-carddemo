package com.carddemo.trantype;

import com.carddemo.common.clock.FrozenClock;
import com.carddemo.common.estate.ChainConfig;
import com.carddemo.common.estate.EstateRoot;
import com.carddemo.common.export.OutputWriter;
import com.carddemo.common.fixture.FixtureLoader;
import com.carddemo.common.run.ChainHandler;
import com.carddemo.common.run.RunContext;
import com.carddemo.common.run.RunResult;
import com.carddemo.common.testsupport.DbTest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DbTest
class TrantypeDatabaseTest {
    private static DataSource dataSource;
    private static JdbcTemplate jdbc;

    @TempDir
    Path temporaryDirectory;

    @BeforeAll
    static void migrate() {
        dataSource = new DriverManagerDataSource(
                System.getenv().getOrDefault("CARDEMO_DB_URL",
                        "jdbc:postgresql://localhost:5432/carddemo_java?currentSchema=carddemo,public"),
                System.getenv().getOrDefault("CARDEMO_DB_USER", "carddemo"),
                System.getenv().getOrDefault("CARDEMO_DB_PASSWORD", "carddemo"));
        Path root = EstateRoot.root();
        Flyway.configure()
                .dataSource(dataSource)
                .locations("filesystem:" + root.resolve("java/common/src/main/resources/db/migration/common"),
                        "filesystem:" + root.resolve("java/trantype/src/main/resources/db/migration/trantype"))
                .schemas("carddemo")
                .defaultSchema("carddemo")
                .load()
                .migrate();
        jdbc = new JdbcTemplate(dataSource);
    }

    @AfterAll
    static void cleanSchemaForLaterModuleTests() {
        if (dataSource != null) {
            Flyway.configure()
                    .dataSource(dataSource)
                    .locations("filesystem:" + EstateRoot.root().resolve("java/common/src/main/resources/db/migration/common"),
                            "filesystem:" + EstateRoot.root().resolve("java/trantype/src/main/resources/db/migration/trantype"))
                    .schemas("carddemo")
                    .defaultSchema("carddemo")
                    .cleanDisabled(false)
                    .load()
                    .clean();
        }
    }

    @BeforeEach
    void seed() {
        jdbc.execute("TRUNCATE TABLE carddemo.transaction_type_categor, carddemo.transaction_type CASCADE");
        jdbc.update("INSERT INTO carddemo.transaction_type (tr_type, tr_description) "
                + "VALUES ('01', 'Purchase'), ('02', 'Payment'), ('03', 'Credit')");
    }

    @Test
    @DisplayName("BR-TRT-011 Descriptions are stored space-padded to 50 bytes")
    void brTrt011_dbPreservesDescriptionPadding() throws SQLException {
        List<String> sysout = new ArrayList<>();
        try (JdbcSqlSession sql = new JdbcSqlSession(dataSource.getConnection())) {
            assertThat(new TransactionTypeMaintenanceService().run(cards("U02Payment - bank transfer"), sql, sysout))
                    .isZero();
            assertThat(sql.commit()).isZero();
        }

        assertThat(description("02")).isEqualTo("Payment - bank transfer" + " ".repeat(27));
        assertThat(description("01")).isEqualTo("Purchase");
    }

    @Test
    @DisplayName("BR-TRT-013 Duplicate add fails with negative SQLCODE (-403 under ocesql)")
    void brTrt013_duplicateKeyMapsToMinus403() throws SQLException {
        List<String> sysout = new ArrayList<>();
        try (JdbcSqlSession sql = new JdbcSqlSession(dataSource.getConnection())) {
            assertThat(new TransactionTypeMaintenanceService().run(cards("A01Purchase"), sql, sysout))
                    .isEqualTo(4);
            assertThat(sql.commit()).isZero();
        }

        assertThat(sysout).contains(message("Error accessing: TRANSACTION_TYPE table. SQLCODE: -403"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM carddemo.transaction_type", Integer.class)).isEqualTo(3);
    }

    @Test
    @DisplayName("BR-TRT-018 Update of a missing type reports success under ocesql")
    void brTrt018_updateOfMissingTypeReportsSuccess() throws SQLException {
        List<String> sysout = new ArrayList<>();
        try (JdbcSqlSession sql = new JdbcSqlSession(dataSource.getConnection())) {
            assertThat(new TransactionTypeMaintenanceService().run(cards("U09Nothing"), sql, sysout)).isZero();
            assertThat(sql.commit()).isZero();
        }

        assertThat(sysout).contains("RECORD UPDATED SUCCESSFULLY");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM carddemo.transaction_type", Integer.class)).isEqualTo(3);
    }

    @Test
    @DisplayName("BR-TRT-021 Delete of a missing type reports success")
    void brTrt021_deleteOfMissingTypeReportsSuccess() throws SQLException {
        List<String> sysout = new ArrayList<>();
        try (JdbcSqlSession sql = new JdbcSqlSession(dataSource.getConnection())) {
            assertThat(new TransactionTypeMaintenanceService().run(cards("D09"), sql, sysout)).isZero();
            assertThat(sql.commit()).isZero();
        }

        assertThat(sysout).contains("RECORD DELETED SUCCESSFULLY");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM carddemo.transaction_type", Integer.class)).isEqualTo(3);
    }

    @Test
    @DisplayName("BR-TRT-022 Delete SQL error reports and sets RC 4")
    void brTrt022_foreignKeyDeleteUsesProbeSqlcode() throws SQLException {
        jdbc.update("INSERT INTO carddemo.transaction_type_categor "
                + "(trc_type_code, trc_type_category, trc_cat_data) VALUES ('01', '0001', 'x')");
        List<String> sysout = new ArrayList<>();
        try (JdbcSqlSession sql = new JdbcSqlSession(dataSource.getConnection())) {
            assertThat(new TransactionTypeMaintenanceService().run(cards("D01"), sql, sysout)).isEqualTo(4);
            assertThat(sql.commit()).isZero();
        }

        assertThat(sysout).contains(message("Error accessing: TRANSACTION_TYPE table. SQLCODE: -400"));
        assertThat(description("01")).isEqualTo("Purchase");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM carddemo.transaction_type_categor", Integer.class))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("BR-TRT-026 MNTTRDB2 step and RC")
    void brTrt026_mnttrdb2StepAndRcMatchGolden() throws Exception {
        RunResult result = runFixture("trantype-update", "t07-mixed-batch");

        assertThat(result.rc()).containsKey("MNTTRDB2");
        assertThat(result.rc().get("MNTTRDB2").maxrc()).isZero();
        assertThat(result.rc().get("MNTTRDB2").steps()).containsEntry("STEP1", 0);
    }

    @Test
    @DisplayName("BR-TRT-029 Failed statement aborts the whole PostgreSQL unit of work")
    void brTrt029_failedStatementRollsBackUnitOfWork() {
        List<String> sysout = new ArrayList<>();
        List<byte[]> records = cards("A04Authorization", "A01Dup", "A06Six");
        int rc = new DsnRunService().run(dataSource, "COBTUPDT", "", sysout,
                sql -> new TransactionTypeMaintenanceService().run(records, sql, sysout));

        assertThat(rc).isEqualTo(4);
        assertThat(sysout).contains("RECORD INSERTED SUCCESSFULLY",
                message("Error accessing: TRANSACTION_TYPE table. SQLCODE: -403"),
                message("Error accessing: TRANSACTION_TYPE table. SQLCODE: -400"),
                "DSNRUN: COMMIT AT END OF TASK");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM carddemo.transaction_type", Integer.class)).isEqualTo(3);
        assertThat(description("01")).isEqualTo("Purchase");
    }

    @Test
    @DisplayName("BR-TRT-034 Only column-1 `--` is a comment; inline `--` swallows the rest")
    void brTrt034_inlineCommentSwallowsWhereClause() {
        List<String> sysout = new ArrayList<>();
        List<byte[]> dynsql = dynamicCards("DELETE FROM TRANSACTION_TYPE  -- remove fee",
                "WHERE TR_TYPE = '02'");
        int rc = new DsnRunService().run(dataSource, "CBTRNDYN", "", sysout,
                sql -> new DynamicSqlService().run(dynsql, "", sql,
                        new TransactionTypeMaintenanceService(), List.of(), sysout).returnCode());

        assertThat(rc).isZero();
        assertThat(sysout).contains("CBTRNDYN: EXECUTE SQLCODE +0000000000");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM carddemo.transaction_type", Integer.class)).isZero();
    }

    @Test
    @DisplayName("BR-TRT-037 Statement errors surface at EXECUTE, not PREPARE, under ocesql")
    void brTrt037_emptyPreparedStatementMapsToMinus212() throws SQLException {
        try (JdbcSqlSession sql = new JdbcSqlSession(dataSource.getConnection())) {
            assertThat(sql.prepare("")).isZero();
            assertThat(sql.executePrepared()).isEqualTo(-212);
            assertThat(sql.prepare(" ".repeat(73))).isZero();
            assertThat(sql.executePrepared()).isEqualTo(-212);
        }
    }

    @Test
    @DisplayName("BR-TRT-043 Dynamic SQL and COBTUPDT share one unit of work")
    void brTrt043_dynamicAndBatchChangesShareUnitOfWork() {
        runDynamicAndBatch();

        assertThat(description("02")).isEqualTo("Payment - SEPA");
        assertThat(description("04")).isEqualTo("Authorization" + " ".repeat(37));
    }

    @Test
    @DisplayName("BR-TRT-044 Unqualified names and unpadded literals in dynamic SQL")
    void brTrt044_unqualifiedDynamicSqlPreservesLiteralPadding() {
        runDynamicAndBatch();

        assertThat(description("02")).isEqualTo("Payment - SEPA");
        assertThat(description("02")).hasSize(14);
        assertThat(description("04")).isEqualTo("Authorization" + " ".repeat(37));
        assertThat(description("04")).hasSize(50);
    }

    @Test
    void everyTrantypeFixtureMatchesGoldenOutputByteForByte() throws Exception {
        ChainConfig config = new ChainConfig(EstateRoot.root());
        FixtureLoader fixtureLoader = new FixtureLoader(jdbc, dataSource);
        Map<String, ChainHandler> handlers = Map.of(
                "trantype-update", new TrantypeUpdateHandler(),
                "trantype-dynamic", new TrantypeDynamicHandler());

        for (String chainName : List.of("trantype-update", "trantype-dynamic")) {
            ChainConfig.Chain chain = config.get(chainName);
            for (String caseName : config.cases(chainName)) {
                Path caseDirectory = EstateRoot.root().resolve("fixtures").resolve(chainName).resolve(caseName);
                Path out = temporaryDirectory.resolve(chainName).resolve(caseName);
                fixtureLoader.load(chainName, chain, caseDirectory, handlers.get(chainName).extraTables());
                OutputWriter writer = new OutputWriter(out, jdbc, dataSource);
                RunContext context = new RunContext(chain, caseName, caseDirectory, out, fixtureLoader, writer,
                        new FrozenClock(chain.clock()), jdbc, null, "trantype-test");
                RunResult result = handlers.get(chainName).run(context);
                writer.writeRcJson(result.rc());

                Path expected = caseDirectory.resolve("expected");
                String job = chain.jobs().getFirst();
                assertBytesEqual(expected.resolve("rc.json"), out.resolve("rc.json"));
                assertBytesEqual(expected.resolve("sysout").resolve(job + ".STEP1.txt"),
                        out.resolve("sysout").resolve(job + ".STEP1.txt"));
                assertBytesEqual(expected.resolve("db/carddemo.transaction_type.csv"),
                        out.resolve("db/carddemo.transaction_type.csv"));
                assertBytesEqual(expected.resolve("db/carddemo.transaction_type_categor.csv"),
                        out.resolve("db/carddemo.transaction_type_categor.csv"));
            }
        }
    }

    private static void runDynamicAndBatch() {
        List<String> sysout = new ArrayList<>();
        int rc = new DsnRunService().run(dataSource, "CBTRNDYN", "COBTUPDT", sysout,
                sql -> new DynamicSqlService().run(dynamicCards(
                                "UPDATE TRANSACTION_TYPE SET TR_DESCRIPTION = 'Payment - SEPA'",
                                " WHERE TR_TYPE = '02'"),
                        "COBTUPDT", sql, new TransactionTypeMaintenanceService(),
                        cards("A04Authorization"), sysout).returnCode());
        assertThat(rc).isZero();
    }

    private RunResult runFixture(String chainName, String caseName) throws Exception {
        ChainConfig config = new ChainConfig(EstateRoot.root());
        ChainConfig.Chain chain = config.get(chainName);
        FixtureLoader fixtureLoader = new FixtureLoader(jdbc, dataSource);
        Path caseDirectory = EstateRoot.root().resolve("fixtures").resolve(chainName).resolve(caseName);
        fixtureLoader.load(chainName, chain, caseDirectory, List.of());
        OutputWriter writer = new OutputWriter(temporaryDirectory.resolve(chainName).resolve(caseName), jdbc, dataSource);
        RunContext context = new RunContext(chain, caseName, caseDirectory, writer.outDir(), fixtureLoader,
                writer, new FrozenClock(chain.clock()), jdbc, null, "trantype-test");
        RunResult result = new TrantypeUpdateHandler().run(context);
        writer.writeRcJson(result.rc());
        assertBytesEqual(caseDirectory.resolve("expected/rc.json"), writer.outDir().resolve("rc.json"));
        assertBytesEqual(caseDirectory.resolve("expected/sysout/MNTTRDB2.STEP1.txt"),
                writer.outDir().resolve("sysout/MNTTRDB2.STEP1.txt"));
        return result;
    }

    private String description(String type) {
        return jdbc.queryForObject("SELECT tr_description FROM carddemo.transaction_type WHERE tr_type = ?",
                String.class, type);
    }

    private static List<byte[]> cards(String... records) {
        return Arrays.stream(records).map(record -> fixedCard(record, 53)).toList();
    }

    private static List<byte[]> dynamicCards(String... records) {
        return Arrays.stream(records).map(record -> fixedCard(record, 80)).toList();
    }

    private static byte[] fixedCard(String text, int width) {
        String value = text.length() > width ? text.substring(0, width) : text;
        return (value + " ".repeat(width - value.length())).getBytes(StandardCharsets.ISO_8859_1);
    }

    private static String message(String text) {
        return text + " ".repeat(80 - text.length());
    }

    private static void assertBytesEqual(Path expected, Path actual) throws Exception {
        assertThat(Files.readAllBytes(actual))
                .as("%s should match %s", actual, expected)
                .isEqualTo(Files.readAllBytes(expected));
    }
}
