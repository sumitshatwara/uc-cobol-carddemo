package com.carddemo.common.fixture;

import java.util.Map;
import java.util.TreeMap;

/**
 * Serialises nested maps exactly like Python's {@code json.dumps(obj, indent=1, sort_keys=True)},
 * the format the harness uses for {@code rc.json} and {@code manifest.json}.
 */
public final class PyJson {

    private PyJson() {}

    public static String dumps(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value, 0);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object v, int depth) {
        if (v instanceof Map<?, ?> m) {
            if (m.isEmpty()) {
                sb.append("{}");
                return;
            }
            TreeMap<String, Object> sorted = new TreeMap<>();
            m.forEach((k, val) -> sorted.put(String.valueOf(k), val));
            sb.append("{\n");
            int i = 0;
            for (Map.Entry<String, Object> e : sorted.entrySet()) {
                sb.append(" ".repeat(depth + 1));
                string(sb, e.getKey());
                sb.append(": ");
                write(sb, e.getValue(), depth + 1);
                if (++i < sorted.size()) {
                    sb.append(',');
                }
                sb.append('\n');
            }
            sb.append(" ".repeat(depth)).append('}');
        } else if (v instanceof Number || v instanceof Boolean) {
            sb.append(v instanceof Boolean b ? (b ? "true" : "false") : v.toString());
        } else if (v == null) {
            sb.append("null");
        } else {
            string(sb, v.toString());
        }
    }

    private static void string(StringBuilder sb, String s) {
        sb.append('"');
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20 || c > 0x7e) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
