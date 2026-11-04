package com.carddemo.online.account;

import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

final class ValidationCodes {
    static final Set<String> GENERAL_AREAS;
    static final Set<String> STATES;
    static final Set<String> STATE_ZIP2;

    static {
        try {
            var lines = new ClassPathResource("online-validation-codes.txt").getContentAsString(StandardCharsets.UTF_8)
                    .lines().toList();
            GENERAL_AREAS = values(lines, "GENERAL");
            STATES = values(lines, "STATE");
            STATE_ZIP2 = values(lines, "ZIP2");
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private ValidationCodes() {
    }

    private static Set<String> values(java.util.List<String> lines, String key) {
        String line = lines.stream().filter(value -> value.startsWith(key + "=")).findFirst()
                .orElseThrow(() -> new IllegalStateException("Missing " + key + " validation list"));
        return Arrays.stream(line.substring(key.length() + 1).split(","))
                .filter(value -> !value.isBlank()).collect(Collectors.toUnmodifiableSet());
    }
}
