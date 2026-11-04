package com.carddemo.common.estate;

import java.nio.file.Files;
import java.nio.file.Path;

public final class EstateRoot {
    private EstateRoot() {
    }

    public static Path root() {
        String configured = System.getProperty("carddemo.estate-root");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("tools/parity/compare.py"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Unable to locate CardDemo estate root from " + System.getProperty("user.dir"));
    }
}
