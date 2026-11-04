package com.carddemo.interest;

import com.carddemo.common.codec.RecordCodec;
import com.carddemo.common.estate.ChainConfig;
import com.carddemo.common.estate.EstateRoot;
import com.carddemo.common.fixture.FixtureLoader;
import com.carddemo.common.run.ChainRunner;
import com.carddemo.common.testsupport.DbTest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InterestHandlerDbTest {
    @TempDir
    Path temporaryDirectory;
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void clearFixtureRows() {
        if (jdbcTemplate != null) {
            jdbcTemplate.execute("""
                    TRUNCATE TABLE carddemo.account, carddemo.card_xref, carddemo.tran_cat_balance,
                        carddemo.disclosure_group, carddemo.tran_category, carddemo.tran_type,
                        carddemo.daily_transaction, carddemo."transaction" CASCADE
                    """);
            Boolean transactionTypeExists =
                    jdbcTemplate.queryForObject(
                            "SELECT to_regclass('carddemo.transaction_type') IS NOT NULL",
                            Boolean.class);
            if (Boolean.TRUE.equals(transactionTypeExists)) {
                jdbcTemplate.execute("""
                        TRUNCATE TABLE carddemo.transaction_type_categor,
                            carddemo.transaction_type CASCADE
                        """);
            }
        }
    }

    @Test
    @DbTest
    void allSevenInterestFixturesMatchTheGoldenOutputs() throws Exception {
        DriverManagerDataSource source = new DriverManagerDataSource(
                System.getenv().getOrDefault("CARDEMO_DB_URL",
                        "jdbc:postgresql://localhost:5432/carddemo_java?currentSchema=carddemo,public"),
                System.getenv().getOrDefault("CARDEMO_DB_USER", "carddemo"),
                System.getenv().getOrDefault("CARDEMO_DB_PASSWORD", "carddemo"));
        Flyway.configure()
                .dataSource(source)
                .locations("classpath:db/migration")
                .schemas("carddemo")
                .defaultSchema("carddemo")
                .load()
                .migrate();

        Path root = EstateRoot.root();
        jdbcTemplate = new JdbcTemplate(source);
        FixtureLoader fixtureLoader = new FixtureLoader(jdbcTemplate, source);
        InterestHandler handler = new InterestHandler(new InterestCalculationService());
        ChainRunner runner = new ChainRunner(
                new ChainConfig(root),
                List.of(handler),
                fixtureLoader,
                jdbcTemplate,
                null);
        List<String> cases = List.of(
                "i01-baseline",
                "i02-tie-half-cent",
                "i03-zero-rate-skipped",
                "i04-default-group-fallback",
                "i05-multi-category",
                "i06-negative-balance",
                "i07-nontie-rounding");

        for (String caseName : cases) {
            Path output = temporaryDirectory.resolve(caseName);
            var result = runner.run("interest", caseName, output, "interest-db-" + caseName);
            Path expected = root.resolve("fixtures/interest").resolve(caseName).resolve("expected");

            assertThat(result.maxRc()).as(caseName).isZero();
            assertThat(Files.readString(output.resolve("sysout/INTCALC.STEP15.txt")))
                    .as(caseName + " SYSOUT")
                    .isEqualTo(Files.readString(expected.resolve("sysout/INTCALC.STEP15.txt")));
            assertThat(Files.readString(output.resolve("datasets/AWS.M2.CARDDEMO.SYSTRAN.G0001V00.txt")))
                    .as(caseName + " SYSTRAN")
                    .isEqualTo(Files.readString(expected.resolve(
                            "datasets/AWS.M2.CARDDEMO.SYSTRAN.G0001V00.txt")));
            assertThat(Files.readString(output.resolve("datasets/AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS.txt")))
                    .as(caseName + " ACCTDATA")
                    .isEqualTo(Files.readString(expected.resolve(
                            "datasets/AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS.txt")));
            assertThat(Files.readString(output.resolve("manifest.json")))
                    .as(caseName + " manifest")
                    .isEqualTo(Files.readString(expected.resolve("manifest.json")));
            assertThat(Files.readString(output.resolve("rc.json")))
                    .as(caseName + " return codes")
                    .isEqualTo(Files.readString(expected.resolve("rc.json")));
        }

        Path tieOutput = temporaryDirectory.resolve("i02-tie-half-cent/datasets/"
                + "AWS.M2.CARDDEMO.SYSTRAN.G0001V00.txt");
        byte[] firstTransaction = Files.readAllLines(tieOutput, StandardCharsets.ISO_8859_1)
                .getFirst().getBytes(StandardCharsets.ISO_8859_1);
        assertThat(RecordCodec.decode("CVTRA05Y", firstTransaction))
                .containsEntry("TRAN-AMT", new java.math.BigDecimal("12.35"));
    }
}
