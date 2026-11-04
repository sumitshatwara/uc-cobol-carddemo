package com.carddemo.app;

import com.carddemo.common.testsupport.DbTest;
import com.carddemo.common.export.OutputWriter;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FlywayMigrationTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    @DbTest
    void freshJavaDatabaseMigratesVsamBatchAndTrantypeSchemas() throws Exception {
        DriverManagerDataSource source = new DriverManagerDataSource(
                System.getenv().getOrDefault("CARDEMO_DB_URL",
                        "jdbc:postgresql://localhost:5432/carddemo_java?currentSchema=carddemo,public"),
                System.getenv().getOrDefault("CARDEMO_DB_USER", "carddemo"),
                System.getenv().getOrDefault("CARDEMO_DB_PASSWORD", "carddemo"));
        Flyway flyway = Flyway.configure()
                .dataSource(source)
                .locations("classpath:db/migration")
                .schemas("carddemo")
                .defaultSchema("carddemo")
                .load();
        flyway.migrate();
        JdbcTemplate jdbc = new JdbcTemplate(source);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM carddemo.account", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM carddemo.transaction_type", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM carddemo.batch_job_instance", Integer.class)).isZero();

        jdbc.execute("DROP TABLE IF EXISTS carddemo.output_writer_test");
        jdbc.execute("CREATE TABLE carddemo.output_writer_test (id integer PRIMARY KEY, label varchar(30))");
        try {
            jdbc.update("INSERT INTO carddemo.output_writer_test (id, label) VALUES (2, 'second'), (1, 'first')");
            new OutputWriter(temporaryDirectory, jdbc, source).snapshotTable("output_writer_test", "id");
            assertThat(Files.readString(temporaryDirectory.resolve("db/output_writer_test.csv")))
                    .isEqualTo("id,label\n1,first\n2,second\n");
        } finally {
            jdbc.execute("DROP TABLE IF EXISTS carddemo.output_writer_test");
        }
    }
}
