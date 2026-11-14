package com.carddemo.common.run;

import java.util.ArrayList;
import java.util.List;

/**
 * SYSOUT of one job step: the lines a COBOL program DISPLAYs.  Operators read these,
 * so they are part of parity: reproduce the exact text, including trailing spaces of
 * DISPLAYed records and fields.
 */
public class SysoutWriter {

    private final List<String> lines = new ArrayList<>();

    /** One {@code DISPLAY} statement (all operands concatenated, no separator). */
    public SysoutWriter display(Object... operands) {
        StringBuilder sb = new StringBuilder();
        for (Object o : operands) {
            sb.append(o);
        }
        lines.add(sb.toString());
        return this;
    }

    public List<String> lines() {
        return List.copyOf(lines);
    }

    String text() {
        return lines.isEmpty() ? "" : String.join("\n", lines) + "\n";
    }
}
