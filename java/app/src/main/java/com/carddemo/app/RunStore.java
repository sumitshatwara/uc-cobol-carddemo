package com.carddemo.app;

import com.carddemo.common.export.JobRc;
import com.carddemo.common.run.RunResult;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RunStore {
    private final Map<String, RunSummary> runs = new ConcurrentHashMap<>();

    public void put(RunSummary run) {
        runs.put(run.runId(), run);
    }

    public RunSummary get(String runId) {
        return runs.get(runId);
    }

    public List<RunSummary> all() {
        return runs.values().stream().sorted(Comparator.comparing(RunSummary::startedAt).reversed()).toList();
    }

    public record RunSummary(
            String runId,
            String chain,
            String caseName,
            String status,
            int maxRc,
            Map<String, JobRc> rc,
            Instant startedAt,
            long durationMs,
            List<String> files,
            String outputDirectory) {
        public RunSummary {
            rc = Map.copyOf(rc);
            files = List.copyOf(files);
        }

        static RunSummary completed(String runId, String chain, String caseName, RunResult result,
                                    Instant startedAt, long durationMs, List<String> files, String outputDirectory) {
            return new RunSummary(runId, chain, caseName, "COMPLETED", result.maxRc(), result.rc(),
                    startedAt, durationMs, files, outputDirectory);
        }
    }
}
