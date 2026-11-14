package com.carddemo.common;

import com.carddemo.common.codec.CopybookParser;
import com.carddemo.common.repo.RepoPaths;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Test helper shared with the slices through the common test-jar: repository paths and golden files. */
public final class TestRepo {

    private static final Path ROOT = RepoPaths.discover(Path.of("").toAbsolutePath());
    private static final CopybookParser COPYBOOKS = new CopybookParser(ROOT);

    private TestRepo() {}

    public static Path root() { return ROOT; }

    public static CopybookParser copybooks() { return COPYBOOKS; }

    public static Path fixture(String chain, String caseName) {
        return ROOT.resolve("fixtures").resolve(chain).resolve(caseName);
    }

    /** Lines of a golden file under fixtures/&lt;chain&gt;/&lt;case&gt;/expected/. */
    public static List<String> expectedLines(String chain, String caseName, String relPath) {
        try {
            return Files.readAllLines(fixture(chain, caseName).resolve("expected").resolve(relPath), StandardCharsets.ISO_8859_1);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static String expectedText(String chain, String caseName, String relPath) {
        try {
            return Files.readString(fixture(chain, caseName).resolve("expected").resolve(relPath), StandardCharsets.ISO_8859_1);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
