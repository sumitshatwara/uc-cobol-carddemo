package com.carddemo.common.run;

import com.carddemo.common.codec.Copybook;
import com.carddemo.common.codec.CopybookField;
import com.carddemo.common.codec.CopybookParser;
import com.carddemo.common.codec.FieldKind;
import com.carddemo.common.codec.RecordCodec;
import com.carddemo.common.entity.DatasetTables.TableDef;
import com.carddemo.common.fixture.PyJson;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Writes output datasets in the recorded fixture format (tools/parity/record.py {@code unload}):
 * {@code datasets/<DSN>.txt}, one record per line, trailing spaces removed, plus an entry in
 * {@code manifest.json} ({@code layout}, {@code lrecl}, {@code records}).
 */
public class DatasetWriter {

    private final Path outDir;
    private final JdbcTemplate jdbc;
    private final CopybookParser copybooks;
    private final Map<String, Object> manifest = new TreeMap<>();

    DatasetWriter(Path outDir, JdbcTemplate jdbc, CopybookParser copybooks) {
        this.outDir = outDir;
        this.jdbc = jdbc;
        this.copybooks = copybooks;
    }

    /** Writes already-formatted records (each the full fixed-length record, or a report line). */
    public void writeRecords(String dsn, String layout, int lrecl, List<String> records) {
        StringBuilder sb = new StringBuilder();
        for (String r : records) {
            String rec = r.length() > lrecl ? r.substring(0, lrecl) : r;
            int end = rec.length();
            while (end > 0 && rec.charAt(end - 1) == ' ') {
                end--;
            }
            sb.append(rec, 0, end).append('\n');
        }
        try {
            Path target = outDir.resolve("datasets").resolve(dsn + ".txt");
            Files.createDirectories(target.getParent());
            Files.writeString(target, sb.toString(), StandardCharsets.ISO_8859_1);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("layout", layout);
            entry.put("lrecl", lrecl);
            entry.put("records", records.size());
            manifest.put(dsn, entry);
            Files.writeString(outDir.resolve("manifest.json"), PyJson.dumps(manifest));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Encodes field maps (copybook field name -> value) with the copybook and writes them. */
    public void writeFieldMaps(String dsn, String copybook, List<? extends Map<String, ?>> rows) {
        Copybook cb = copybooks.get(copybook);
        List<String> recs = new ArrayList<>(rows.size());
        for (Map<String, ?> row : rows) {
            recs.add(RecordCodec.encodeLine(cb, row));
        }
        writeRecords(dsn, copybook, cb.recordLength(), recs);
    }

    /** Unloads a dataset table in key order (the IDCAMS REPRO of a KSDS to a flat file). */
    public void exportTable(String dsn, TableDef table) {
        exportQuery(dsn, table.copybook(), "SELECT * FROM " + table.qualified() + " ORDER BY " + table.orderBy());
    }

    /**
     * Runs {@code sql} and encodes each row with {@code copybook}; result columns are matched to
     * copybook fields by lower-snake-case name ({@code ACCT-CURR-BAL -> acct_curr_bal}).
     */
    public void exportQuery(String dsn, String copybook, String sql) {
        Copybook cb = copybooks.get(copybook);
        List<Map<String, Object>> rows = new ArrayList<>();
        jdbc.query(sql, rs -> {
            Map<String, Object> row = new LinkedHashMap<>();
            for (CopybookField f : cb.namedFields().values()) {
                Object v = rs.getObject(f.name().toLowerCase().replace('-', '_'));
                if (f.kind() != FieldKind.TEXT && v != null && !(v instanceof BigDecimal)) {
                    v = new BigDecimal(v.toString());
                }
                row.put(f.name(), v);
            }
            rows.add(row);
        });
        writeFieldMaps(dsn, copybook, rows);
    }
}
