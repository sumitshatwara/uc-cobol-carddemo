package com.carddemo.common.run;

import com.carddemo.common.clock.FrozenClock;
import com.carddemo.common.fixture.ChainDef;
import com.carddemo.common.fixture.FixtureLoader;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Context of one chain execution on one fixture case, handed to a {@link ChainHandler}.
 * <p>
 * Return codes follow the legacy harness's {@code rc.json}: per job a {@code maxrc} and a
 * {@code steps} map.  Note that the harness accumulates the step RCs of the chain's
 * <em>setup</em> jobs into the first job's {@code steps} map (e.g. INTCALC lists
 * STEP05..STEP30), so handlers record those steps too.  {@code maxrc} defaults to the
 * highest step RC of the job unless set with {@link #jobRc}.
 */
public class ChainRun {

    private final ChainDef chain;
    private final String caseName;
    private final Path caseDir;
    private final Path outDir;
    private final FrozenClock clock;
    private final JdbcTemplate jdbc;
    private final DatasetWriter datasets;
    private final Map<String, LinkedHashMap<String, Integer>> stepRcs = new LinkedHashMap<>();
    private final Map<String, Integer> jobRcs = new LinkedHashMap<>();
    private final Map<String, SysoutWriter> sysouts = new LinkedHashMap<>();

    ChainRun(ChainDef chain, String caseName, Path caseDir, Path outDir, FrozenClock clock, JdbcTemplate jdbc, DatasetWriter datasets) {
        this.chain = chain;
        this.caseName = caseName;
        this.caseDir = caseDir;
        this.outDir = outDir;
        this.clock = clock;
        this.jdbc = jdbc;
        this.datasets = datasets;
    }

    public ChainDef chain() { return chain; }
    public String caseName() { return caseName; }
    public Path caseDir() { return caseDir; }
    public Path outDir() { return outDir; }
    public FrozenClock clock() { return clock; }
    public JdbcTemplate jdbc() { return jdbc; }
    public DatasetWriter datasets() { return datasets; }

    /** The input records of a dataset, padded to the chain's LRECL (also in carddemo.fixture_record). */
    public List<String> inputRecords(String dsn) {
        Integer lrecl = chain.inputs().get(dsn);
        if (lrecl == null) {
            throw new IllegalArgumentException(chain.name() + " has no input " + dsn);
        }
        return FixtureLoader.readRecords(caseDir, dsn, lrecl);
    }

    /** SYSOUT of {@code job}/{@code step}; files listed in chains.json "sysout" are written even if empty. */
    public SysoutWriter sysout(String job, String step) {
        return sysouts.computeIfAbsent(job + "." + step, k -> new SysoutWriter());
    }

    public void stepRc(String job, String step, int rc) {
        stepRcs.computeIfAbsent(job, k -> new LinkedHashMap<>()).put(step, rc);
    }

    public void jobRc(String job, int maxrc) {
        jobRcs.put(job, maxrc);
    }

    Map<String, SysoutWriter> sysouts() { return sysouts; }

    /** rc.json content as a nested map (serialised with {@code PyJson}). */
    Map<String, Object> rcJson() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String job : chain.jobs()) {
            Map<String, Integer> steps = stepRcs.getOrDefault(job, new LinkedHashMap<>());
            int max = jobRcs.getOrDefault(job, steps.values().stream().mapToInt(Integer::intValue).max().orElse(0));
            Map<String, Object> j = new LinkedHashMap<>();
            j.put("maxrc", max);
            j.put("steps", steps);
            out.put(job, j);
        }
        return out;
    }

    int maxRc() {
        return rcJson().values().stream().mapToInt(v -> (Integer) ((Map<?, ?>) v).get("maxrc")).max().orElse(0);
    }
}
