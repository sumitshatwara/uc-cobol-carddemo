package com.carddemo.common.fixture;

import com.carddemo.common.codec.CopybookField;
import com.carddemo.common.codec.CopybookParser;
import com.carddemo.common.codec.RecordCodec;
import com.carddemo.common.estate.ChainConfig;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

@Component
public class FixtureLoader {
    private final JdbcTemplate jdbcTemplate;
    private final DataSource dataSource;
    private final Map<String, List<byte[]>> rawRecords = new LinkedHashMap<>();

    public FixtureLoader(JdbcTemplate jdbcTemplate, DataSource dataSource) {
        this.jdbcTemplate = jdbcTemplate;
        this.dataSource = dataSource;
    }

    public synchronized void load(String chainName, ChainConfig.Chain chain, Path caseDirectory, List<String> extraTables) {
        rawRecords.clear();
        Set<String> tables = new TreeSet<>();
        DatasetCatalog.all().values().forEach(dataset -> tables.add(qualified(dataset.table())));
        extraTables.forEach(table -> tables.add(qualified(table)));
        if (chain.database() != null) {
            chain.database().tables().keySet().forEach(table -> tables.add(qualified(table)));
        }
        for (String table : tables) {
            jdbcTemplate.execute("TRUNCATE TABLE " + table + " CASCADE");
        }

        for (Map.Entry<String, Integer> input : chain.inputs().entrySet()) {
            List<byte[]> records = readRecords(caseDirectory.resolve("input").resolve(input.getKey() + ".txt"), input.getValue());
            DatasetCatalog.Dataset dataset = DatasetCatalog.find(input.getKey()).orElse(null);
            if (dataset == null) {
                rawRecords.put(input.getKey(), records);
            } else {
                records.forEach(record -> insert(dataset, RecordCodec.decode(dataset.copybook(), record)));
            }
        }

        if (chain.database() != null) {
            Path seed = caseDirectory.resolve("input").resolve(chain.database().seed());
            if (Files.isRegularFile(seed)) {
                new ResourceDatabasePopulator(new FileSystemResource(seed.toFile())).execute(dataSource);
            }
        }
    }

    public synchronized List<byte[]> rawRecords(String dsn) {
        return rawRecords.getOrDefault(dsn, List.of()).stream().map(byte[]::clone).toList();
    }

    private void insert(DatasetCatalog.Dataset dataset, Map<String, Object> row) {
        List<CopybookField> fields = CopybookParser.parse(dataset.copybook()).stream()
                .filter(field -> !field.isFiller()).toList();
        String columns = String.join(", ", fields.stream().map(field -> column(field.name())).toList());
        String placeholders = String.join(", ", fields.stream().map(field -> "?").toList());
        Object[] values = fields.stream().map(field -> row.get(field.name())).toArray();
        int[] types = fields.stream().mapToInt(field -> field.kind() == CopybookField.Kind.TEXT
                ? Types.VARCHAR : Types.NUMERIC).toArray();
        jdbcTemplate.update("INSERT INTO " + qualified(dataset.table()) + " (" + columns + ") VALUES (" + placeholders + ")",
                values, types);
    }

    private static List<byte[]> readRecords(Path file, int lrecl) {
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        try {
            List<byte[]> records = new ArrayList<>();
            for (String line : Files.readAllLines(file, StandardCharsets.ISO_8859_1)) {
                byte[] source = line.getBytes(StandardCharsets.ISO_8859_1);
                byte[] record = new byte[lrecl];
                Arrays.fill(record, (byte) ' ');
                System.arraycopy(source, 0, record, 0, Math.min(source.length, lrecl));
                records.add(record);
            }
            return records;
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read fixture input " + file, exception);
        }
    }

    private static String column(String name) {
        return name.toLowerCase().replace('-', '_');
    }

    private static String qualified(String table) {
        String[] parts = table.split("\\.", 2);
        if (parts.length == 2) {
            return parts[0] + "." + quoteIdentifier(parts[1]);
        }
        return "carddemo." + quoteIdentifier(table);
    }

    private static String quoteIdentifier(String identifier) {
        if (!identifier.matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
            throw new IllegalArgumentException("invalid SQL identifier: " + identifier);
        }
        return identifier.equalsIgnoreCase("transaction") ? "\"" + identifier + "\"" : identifier;
    }
}
