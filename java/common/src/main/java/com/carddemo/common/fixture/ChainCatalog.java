package com.carddemo.common.fixture;

import com.carddemo.common.repo.RepoPaths;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/** Reads tools/runjcl/chains.json and the fixture cases under fixtures/&lt;chain&gt;/. */
@Component
public class ChainCatalog {

    private final RepoPaths repo;
    private final Map<String, ChainDef> chains = new LinkedHashMap<>();

    public ChainCatalog(RepoPaths repo) {
        this.repo = repo;
        try {
            JsonNode root = new ObjectMapper().readTree(repo.chainsJson().toFile());
            root.fields().forEachRemaining(e -> chains.put(e.getKey(), parse(e.getKey(), e.getValue())));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static ChainDef parse(String name, JsonNode n) {
        LinkedHashMap<String, Integer> inputs = new LinkedHashMap<>();
        n.path("inputs").fields().forEachRemaining(e -> inputs.put(e.getKey(), e.getValue().asInt()));
        LinkedHashMap<String, String> outputs = new LinkedHashMap<>();
        n.path("outputs").fields().forEachRemaining(e -> outputs.put(e.getKey(), e.getValue().asText()));
        ChainDef.Database db = null;
        if (n.has("database")) {
            LinkedHashMap<String, String> tables = new LinkedHashMap<>();
            n.get("database").path("tables").fields().forEachRemaining(e -> tables.put(e.getKey(), e.getValue().asText()));
            db = new ChainDef.Database(n.get("database").path("seed").asText(), tables);
        }
        return new ChainDef(name, n.path("description").asText(), n.path("clock").asText(), inputs,
                strings(n.path("setup")), strings(n.path("jobs")), outputs, strings(n.path("sysout")), db);
    }

    private static List<String> strings(JsonNode arr) {
        List<String> out = new ArrayList<>();
        arr.forEach(x -> out.add(x.asText()));
        return out;
    }

    public Map<String, ChainDef> all() {
        return chains;
    }

    public ChainDef get(String chain) {
        ChainDef def = chains.get(chain);
        if (def == null) {
            throw new IllegalArgumentException("unknown chain " + chain + "; known: " + chains.keySet());
        }
        return def;
    }

    /** Fixture case names for a chain, sorted (same order as {@code ls fixtures/<chain>}). */
    public List<String> cases(String chain) {
        Path dir = repo.fixtures().resolve(chain);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(Files::isDirectory).map(p -> p.getFileName().toString()).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Path caseDir(String chain, String caseName) {
        Path dir = repo.fixtures().resolve(chain).resolve(caseName).normalize();
        if (!dir.startsWith(repo.fixtures()) || !Files.isDirectory(dir)) {
            throw new IllegalArgumentException("no fixture case " + chain + "/" + caseName);
        }
        return dir;
    }

    /** First paragraph of the case README (what the case proves). */
    public String caseSummary(String chain, String caseName) {
        Path readme = caseDir(chain, caseName).resolve("README.md");
        if (!Files.exists(readme)) {
            return "";
        }
        try {
            return Files.readString(readme);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
