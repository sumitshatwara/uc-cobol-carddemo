package com.carddemo.common.estate;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class ChainConfig {
    private final Path root;
    private final Map<String, Chain> chains;

    public ChainConfig() {
        this(EstateRoot.root());
    }

    public ChainConfig(Path root) {
        this.root = root;
        try {
            this.chains = new ObjectMapper().readValue(root.resolve("tools/runjcl/chains.json").toFile(),
                    new com.fasterxml.jackson.core.type.TypeReference<TreeMap<String, Chain>>() { });
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read tools/runjcl/chains.json", exception);
        }
    }

    public Path root() {
        return root;
    }

    public Map<String, Chain> chains() {
        return Map.copyOf(chains);
    }

    public Chain get(String name) {
        return chains.get(name);
    }

    public List<String> names() {
        return chains.keySet().stream().sorted().toList();
    }

    public List<String> cases(String chain) {
        Path directory = root.resolve("fixtures").resolve(chain);
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (var children = Files.list(directory)) {
            return children.filter(Files::isDirectory)
                    .map(path -> path.getFileName().toString()).sorted().toList();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to list fixture cases for " + chain, exception);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Chain(
            String description,
            String clock,
            Map<String, Integer> inputs,
            List<String> setup,
            List<String> jobs,
            Map<String, String> outputs,
            List<String> sysout,
            Database database) {
        public Chain {
            inputs = inputs == null ? Map.of() : Map.copyOf(inputs);
            setup = setup == null ? List.of() : List.copyOf(setup);
            jobs = jobs == null ? List.of() : List.copyOf(jobs);
            outputs = outputs == null ? Map.of() : Map.copyOf(outputs);
            sysout = sysout == null ? List.of() : List.copyOf(sysout);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Database(String seed, Map<String, String> tables) {
        public Database {
            tables = tables == null ? Map.of() : Map.copyOf(tables);
        }
    }
}
