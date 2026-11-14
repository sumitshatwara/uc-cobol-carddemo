package com.carddemo.common.fixture;

import com.carddemo.common.codec.Copybook;
import com.carddemo.common.codec.CopybookField;
import com.carddemo.common.codec.CopybookParser;
import com.carddemo.common.codec.FieldKind;
import com.carddemo.common.codec.RecordCodec;
import com.carddemo.common.entity.DatasetTables;
import com.carddemo.common.entity.DatasetTables.TableDef;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Component;

/**
 * Loads a fixture case into PostgreSQL, mirroring tools/parity/record.py
 * ({@code materialise_inputs} + {@code reset_database}):
 * <ol>
 *   <li>truncates every table in schema {@code carddemo} (a fresh "catalogue" per case);</li>
 *   <li>reads each input {@code fixtures/<chain>/<case>/input/<DSN>.txt} (latin-1, one record per
 *       line, padded/cut to the chain's LRECL) into {@code carddemo.fixture_record};</li>
 *   <li>decodes the records of datasets that have a table ({@link DatasetTables#BY_INPUT_DSN})
 *       through their copybook and inserts them as typed rows (the IDCAMS REPRO into the KSDS);</li>
 *   <li>for Db2 chains, runs the case's seed script ({@code input/db/seed.sql}).</li>
 * </ol>
 */
@Component
public class FixtureLoader {

    private final JdbcTemplate jdbc;
    private final DataSource dataSource;
    private final CopybookParser copybooks;

    public FixtureLoader(JdbcTemplate jdbc, DataSource dataSource, CopybookParser copybooks) {
        this.jdbc = jdbc;
        this.dataSource = dataSource;
        this.copybooks = copybooks;
    }

    public void load(ChainDef chain, Path caseDir) {
        truncateAll();
        for (Map.Entry<String, Integer> in : chain.inputs().entrySet()) {
            String dsn = in.getKey();
            List<String> records = readRecords(caseDir, dsn, in.getValue());
            List<Object[]> raw = new ArrayList<>();
            for (int i = 0; i < records.size(); i++) {
                raw.add(new Object[] {dsn, i + 1, records.get(i)});
            }
            jdbc.batchUpdate("INSERT INTO carddemo.fixture_record (dsn, rec_seq, data) VALUES (?, ?, ?)", raw);
            TableDef table = DatasetTables.BY_INPUT_DSN.get(dsn);
            if (table != null) {
                insertTyped(table, dsn, records);
            }
        }
        if (chain.database() != null) {
            Path seed = caseDir.resolve("input").resolve(chain.database().seed());
            if (Files.exists(seed)) {
                runScript(seed);
            }
        }
    }

    /** Truncates every base table of schema carddemo (inputs, outputs and slice tables). */
    public void truncateAll() {
        List<String> tables = jdbc.queryForList(
                "SELECT 'carddemo.' || quote_ident(table_name) FROM information_schema.tables "
                        + "WHERE table_schema = 'carddemo' AND table_type = 'BASE TABLE' ORDER BY table_name", String.class);
        if (!tables.isEmpty()) {
            jdbc.execute("TRUNCATE TABLE " + String.join(", ", tables) + " CASCADE");
        }
    }

    /**
     * Records of one input dataset exactly as the legacy harness materialises them:
     * Python {@code splitlines()} on the latin-1 text, each line padded with spaces or cut to LRECL.
     */
    public static List<String> readRecords(Path caseDir, String dsn, int lrecl) {
        Path src = caseDir.resolve("input").resolve(dsn + ".txt");
        if (!Files.exists(src)) {
            return List.of();
        }
        String text;
        try {
            text = Files.readString(src, StandardCharsets.ISO_8859_1);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        List<String> lines = new ArrayList<>(Arrays.asList(text.split("\r\n|[\n\r\u000b\u000c\u001c\u001d\u001e\u0085]", -1)));
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        List<String> out = new ArrayList<>(lines.size());
        for (String l : lines) {
            out.add(l.length() >= lrecl ? l.substring(0, lrecl) : l + " ".repeat(lrecl - l.length()));
        }
        return out;
    }

    private void insertTyped(TableDef table, String dsn, List<String> records) {
        Copybook cb = copybooks.get(table.copybook());
        List<CopybookField> fields = new ArrayList<>(cb.namedFields().values());
        List<String> cols = new ArrayList<>();
        if (table.sequential()) {
            cols.add("rec_seq");
        }
        fields.forEach(f -> cols.add(f.name().toLowerCase().replace('-', '_')));
        String sql = "INSERT INTO " + table.qualified() + " (" + String.join(", ", cols) + ") VALUES ("
                + String.join(", ", cols.stream().map(c -> "?").toList()) + ")";
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < records.size(); i++) {
            Map<String, Object> values = RecordCodec.decodeLine(cb, records.get(i));
            List<Object> row = new ArrayList<>();
            if (table.sequential()) {
                row.add(i + 1);
            }
            for (CopybookField f : fields) {
                Object v = values.get(f.name());
                if (f.kind() != FieldKind.TEXT) {
                    if (!(v instanceof BigDecimal d)) {
                        throw new IllegalStateException(dsn + " record " + (i + 1) + ": " + f.name() + " is not numeric: '" + v + "'");
                    }
                    v = f.scale() == 0 ? (Object) d.longValueExact() : d;
                }
                row.add(v);
            }
            rows.add(row.toArray());
        }
        jdbc.batchUpdate(sql, rows);
    }

    private void runScript(Path script) {
        var conn = DataSourceUtils.getConnection(dataSource);
        try {
            ScriptUtils.executeSqlScript(conn, new FileSystemResource(script));
        } finally {
            DataSourceUtils.releaseConnection(conn, dataSource);
        }
    }
}
