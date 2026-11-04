package com.carddemo.common.export;

import com.carddemo.common.codec.CopybookParser;
import com.carddemo.common.codec.RecordCodec;
import com.carddemo.common.fixture.DatasetCatalog;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.core.util.Separators;
import org.postgresql.PGConnection;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class OutputWriter {
    private final Path outDir;
    private final JdbcTemplate jdbcTemplate;
    private final DataSource dataSource;
    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public OutputWriter(Path outDir, JdbcTemplate jdbcTemplate, DataSource dataSource) {
        this.outDir = outDir;
        this.jdbcTemplate = jdbcTemplate;
        this.dataSource = dataSource;
    }

    public void writeDataset(String dsn, String layout, int lrecl, List<byte[]> records) {
        Path file = outDir.resolve("datasets").resolve(dsn + ".txt");
        ensureParent(file);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (byte[] record : records) {
            int end = record.length;
            while (end > 0 && record[end - 1] == ' ') {
                end--;
            }
            output.write(record, 0, end);
            output.write('\n');
        }
        writeBytes(file, output.toByteArray());
        Map<String, ManifestEntry> manifest = readManifest();
        manifest.put(dsn, new ManifestEntry(layout, lrecl, records.size()));
        writeJson(outDir.resolve("manifest.json"), manifest);
    }

    public void writeTextDataset(String dsn, int lrecl, List<String> lines) {
        List<byte[]> records = lines.stream().map(line -> {
            byte[] source = line.getBytes(StandardCharsets.ISO_8859_1);
            byte[] record = new byte[lrecl];
            Arrays.fill(record, (byte) ' ');
            System.arraycopy(source, 0, record, 0, Math.min(lrecl, source.length));
            return record;
        }).toList();
        writeDataset(dsn, "text", lrecl, records);
    }

    public void exportTable(String dsn, String orderBy) {
        DatasetCatalog.Dataset dataset = DatasetCatalog.find(dsn)
                .orElseThrow(() -> new IllegalArgumentException("No catalog mapping for " + dsn));
        List<byte[]> records = jdbcTemplate.query("SELECT * FROM " + tableName(dataset.table()) + " ORDER BY " + orderBy,
                resultSet -> {
                    List<byte[]> rows = new ArrayList<>();
                    ResultSetMetaData metadata = resultSet.getMetaData();
                    while (resultSet.next()) {
                        Map<String, Object> values = new TreeMap<>();
                        for (int column = 1; column <= metadata.getColumnCount(); column++) {
                            values.put(metadata.getColumnLabel(column), resultSet.getObject(column));
                        }
                        rows.add(RecordCodec.encode(dataset.copybook(), values));
                    }
                    return rows;
                });
        int lrecl = records.isEmpty() ? CopybookParser.recordLength(CopybookParser.parse(dataset.copybook()))
                : records.getFirst().length;
        writeDataset(dsn, dataset.copybook(), lrecl, records);
    }

    public void writeSysout(String job, String step, List<String> lines) {
        Path file = outDir.resolve("sysout").resolve(job + "." + step + ".txt");
        String content = lines.isEmpty() ? "" : String.join("\n", lines) + "\n";
        writeString(file, content);
    }

    public void writeRcJson(Map<String, JobRc> returnCodes) {
        writeJson(outDir.resolve("rc.json"), returnCodes);
    }

    public String gdgName(String base, int generation) {
        return base + String.format(".G%04dV00", generation);
    }

    public void snapshotTable(String table, String orderBy) {
        String sql = "COPY (SELECT * FROM " + tableName(table) + " ORDER BY " + orderBy + ") TO STDOUT WITH CSV HEADER";
        Path file = outDir.resolve("db").resolve(table + ".csv");
        ensureParent(file);
        try (Connection connection = dataSource.getConnection();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            connection.unwrap(PGConnection.class).getCopyAPI().copyOut(sql, output);
            writeBytes(file, output.toByteArray());
        } catch (SQLException | IOException exception) {
            throw new IllegalStateException("Unable to snapshot table " + table, exception);
        }
    }

    public Path outDir() {
        return outDir;
    }

    private Map<String, ManifestEntry> readManifest() {
        Path file = outDir.resolve("manifest.json");
        if (!Files.isRegularFile(file)) {
            return new TreeMap<>();
        }
        try {
            return mapper.readValue(file.toFile(),
                    new com.fasterxml.jackson.core.type.TypeReference<TreeMap<String, ManifestEntry>>() { });
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read output manifest " + file, exception);
        }
    }

    private void writeJson(Path file, Object value) {
        ensureParent(file);
        try {
            DefaultPrettyPrinter printer = new DefaultPrettyPrinter()
                    .withSeparators(Separators.createDefaultInstance()
                            .withObjectFieldValueSpacing(Separators.Spacing.AFTER))
                    .withObjectIndenter(new DefaultIndenter(" ", "\n"))
                    .withArrayIndenter(new DefaultIndenter(" ", "\n"));
            byte[] json = mapper.writer(printer).writeValueAsBytes(value instanceof Map<?, ?> map
                    ? new TreeMap<>(map) : value);
            writeBytes(file, json);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to write JSON " + file, exception);
        }
    }

    private static String tableName(String table) {
        String[] parts = table.split("\\.", 2);
        if (parts.length == 1) {
            return "carddemo." + quoteIdentifier(parts[0]);
        }
        return quoteIdentifier(parts[0]) + "." + quoteIdentifier(parts[1]);
    }

    private static String quoteIdentifier(String identifier) {
        if (!identifier.matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
            throw new IllegalArgumentException("invalid SQL identifier: " + identifier);
        }
        return identifier.equalsIgnoreCase("transaction") ? "\"" + identifier + "\"" : identifier;
    }

    private static void ensureParent(Path file) {
        try {
            Files.createDirectories(file.getParent());
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to create output directory " + file.getParent(), exception);
        }
    }

    private static void writeString(Path file, String content) {
        ensureParent(file);
        try {
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to write " + file, exception);
        }
    }

    private static void writeBytes(Path file, byte[] content) {
        ensureParent(file);
        try {
            Files.write(file, content);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to write " + file, exception);
        }
    }

    public record ManifestEntry(String layout, int lrecl, int records) {
    }
}
