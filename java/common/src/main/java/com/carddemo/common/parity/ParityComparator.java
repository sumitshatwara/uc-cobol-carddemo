package com.carddemo.common.parity;

import com.carddemo.common.repo.RepoPaths;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Runs the estate's own field-level comparator, {@code tools/parity/compare.py}, on a Java
 * run's output directory.  The comparator is never re-implemented in Java: parity is decided
 * by the same code that checks the legacy reruns.
 */
@Component
public class ParityComparator {

    private final RepoPaths repo;
    private final String python;

    public ParityComparator(RepoPaths repo, @Value("${carddemo.parity.python:python3}") String python) {
        this.repo = repo;
        this.python = python;
    }

    public record Verdict(boolean passed, int exitCode, String summary, String reportMarkdown) {}

    public Verdict compare(String chain, String caseName, Path actualDir, Path report, String label) {
        try {
            Files.createDirectories(report.getParent());
            Files.deleteIfExists(report);
            Process p = new ProcessBuilder(List.of(python, "tools/parity/compare.py", chain, caseName,
                    "--actual", actualDir.toAbsolutePath().toString(), "--report", report.toAbsolutePath().toString(), "--label", label))
                    .directory(repo.root().toFile()).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            if (!p.waitFor(120, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return new Verdict(false, -1, "comparator timed out", "");
            }
            String md = Files.exists(report) ? Files.readString(report) : "";
            return new Verdict(p.exitValue() == 0, p.exitValue(), out, md);
        } catch (IOException e) {
            return new Verdict(false, -1, "comparator unavailable: " + e.getMessage(), "");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Verdict(false, -1, "interrupted", "");
        }
    }
}
