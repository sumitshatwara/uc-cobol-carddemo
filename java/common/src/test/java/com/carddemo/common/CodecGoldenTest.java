package com.carddemo.common;

import com.carddemo.common.codec.RecordCodec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class CodecGoldenTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void javaCodecMatchesPythonForEveryFixtureRecord() throws Exception {
        Path vectors = resourceDirectory();
        int count = 0;
        try (Stream<Path> files = Files.list(vectors)) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".json")).sorted().toList()) {
                for (JsonNode vector : mapper.readTree(file.toFile())) {
                    assertVector(vector);
                    count++;
                }
            }
        }
        assertThat(count).isGreaterThan(0);
    }

    private void assertVector(JsonNode vector) {
        String layout = vector.path("layout").asText();
        byte[] source = hex(vector.path("sourceHex").asText());
        Map<String, Object> values = RecordCodec.decode(layout, source);
        JsonNode expected = vector.path("values");
        Set<String> decimalFields = mapper.convertValue(vector.path("decimalFields"), mapper.getTypeFactory()
                .constructCollectionType(Set.class, String.class));
        assertThat(values.keySet()).containsExactlyInAnyOrderElementsOf(
                StreamSupportSupport.strings(expected.fieldNames()));
        expected.fields().forEachRemaining(field -> {
            Object actual = values.get(field.getKey());
            if (decimalFields.contains(field.getKey())) {
                assertThat(actual).isInstanceOf(BigDecimal.class);
                assertThat(((BigDecimal) actual).toPlainString()).isEqualTo(field.getValue().asText());
            } else {
                assertThat(actual).isEqualTo(field.getValue().asText());
            }
        });

        byte[] encoded = RecordCodec.encode(layout, values);
        byte[] pythonEncoded = hex(vector.path("pythonEncodedHex").asText());
        assertThat(encoded).isEqualTo(pythonEncoded);
        if (!java.util.Arrays.equals(rightTrim(encoded), rightTrim(source))) {
            assertThat(pythonEncoded).as("filler/default-byte differences at %s", vector.path("location").asText())
                    .isNotEqualTo(rightTrim(source));
        }
    }

    private static byte[] rightTrim(byte[] bytes) {
        int end = bytes.length;
        while (end > 0 && bytes[end - 1] == ' ') {
            end--;
        }
        return java.util.Arrays.copyOf(bytes, end);
    }

    private static byte[] hex(String value) {
        return java.util.HexFormat.of().parseHex(value);
    }

    private static Path resourceDirectory() throws URISyntaxException {
        return Path.of(CodecGoldenTest.class.getClassLoader().getResource("codec-golden").toURI());
    }

    private static final class StreamSupportSupport {
        private static java.util.List<String> strings(java.util.Iterator<String> iterator) {
            java.util.List<String> values = new java.util.ArrayList<>();
            iterator.forEachRemaining(values::add);
            return values;
        }
    }
}
