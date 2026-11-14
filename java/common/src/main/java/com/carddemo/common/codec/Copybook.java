package com.carddemo.common.codec;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A parsed copybook: its elementary fields in record order. */
public record Copybook(String name, List<CopybookField> fields) {

    public int recordLength() {
        return fields.stream().mapToInt(f -> f.offset() + f.length()).max().orElse(0);
    }

    public CopybookField field(String fieldName) {
        return fields.stream().filter(f -> f.name().equalsIgnoreCase(fieldName)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException(name + " has no field " + fieldName));
    }

    /** Non-FILLER fields keyed by name, in record order. */
    public Map<String, CopybookField> namedFields() {
        Map<String, CopybookField> m = new LinkedHashMap<>();
        for (CopybookField f : fields) {
            if (!f.isFiller()) {
                m.put(f.name(), f);
            }
        }
        return m;
    }
}
