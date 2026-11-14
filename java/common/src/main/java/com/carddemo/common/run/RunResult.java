package com.carddemo.common.run;

import java.nio.file.Path;

/** Outcome of one Java chain run on one fixture case. */
public record RunResult(String chain, String caseName, Path outDir, int maxRc, long durationMs, String error) {

    public boolean failed() {
        return error != null;
    }
}
