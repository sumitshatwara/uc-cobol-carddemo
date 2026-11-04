package com.carddemo.common.run;

import com.carddemo.common.export.JobRc;

import java.util.Map;
import java.util.TreeMap;

public record RunResult(Map<String, JobRc> rc) {
    public RunResult {
        rc = Map.copyOf(new TreeMap<>(rc));
    }

    public int maxRc() {
        return rc.values().stream().mapToInt(JobRc::maxrc).max().orElse(0);
    }
}
