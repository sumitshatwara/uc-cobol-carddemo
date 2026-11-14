package com.carddemo.common.repo;

import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Locates the repository root (the directory holding {@code fixtures/} and
 * {@code tools/runjcl/chains.json}) so the Java side reads the same fixtures,
 * copybooks and comparator as the legacy harness.
 */
@Component
public class RepoPaths {

    private final Path root;

    public RepoPaths(@Value("${carddemo.repo-root:}") String configured) {
        this.root = configured == null || configured.isBlank() ? discover(Path.of("").toAbsolutePath()) : Path.of(configured).toAbsolutePath().normalize();
    }

    /** Walks up from {@code start} until the repository markers are found. */
    public static Path discover(Path start) {
        for (Path p = start; p != null; p = p.getParent()) {
            if (Files.isRegularFile(p.resolve("tools/runjcl/chains.json")) && Files.isDirectory(p.resolve("fixtures"))) {
                return p;
            }
        }
        throw new IllegalStateException("repository root not found above " + start + " (set carddemo.repo-root)");
    }

    public Path root() { return root; }
    public Path fixtures() { return root.resolve("fixtures"); }
    public Path chainsJson() { return root.resolve("tools/runjcl/chains.json"); }
    public Path work() { return root.resolve("work"); }
}
