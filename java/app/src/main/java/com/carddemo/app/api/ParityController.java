package com.carddemo.app.api;

import com.carddemo.common.repo.RepoPaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Parity reports written by {@code make parity} (legacy rerun) and {@code make parity-java}. */
@RestController
@RequestMapping("/api/parity")
public class ParityController {

    private final RepoPaths repo;

    public ParityController(RepoPaths repo) {
        this.repo = repo;
    }

    public record Report(String name, Instant modified, long size) {}

    @GetMapping("/reports")
    public List<Report> reports() throws IOException {
        Path dir = repo.work().resolve("parity");
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.toString().endsWith(".md")).sorted().map(p -> {
                try {
                    return new Report(p.getFileName().toString(), Files.getLastModifiedTime(p).toInstant(), Files.size(p));
                } catch (IOException e) {
                    return new Report(p.getFileName().toString(), null, 0);
                }
            }).toList();
        }
    }

    @GetMapping(value = "/reports/{name}", produces = "text/plain;charset=UTF-8")
    public String report(@PathVariable String name) {
        return FileListing.read(repo.work().resolve("parity"), name);
    }
}
