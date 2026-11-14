package com.carddemo.app.api;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Lists and reads files under a base directory, refusing paths that escape it. */
final class FileListing {

    private FileListing() {}

    static List<String> list(Path base) {
        if (!Files.isDirectory(base)) {
            return List.of();
        }
        try (Stream<Path> s = Files.walk(base)) {
            return s.filter(Files::isRegularFile).map(p -> base.relativize(p).toString().replace('\\', '/')).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String read(Path base, String rel) {
        Path p = base.resolve(rel).normalize();
        if (!p.startsWith(base.normalize()) || !Files.isRegularFile(p)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, rel);
        }
        try {
            return Files.readString(p, StandardCharsets.ISO_8859_1);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
