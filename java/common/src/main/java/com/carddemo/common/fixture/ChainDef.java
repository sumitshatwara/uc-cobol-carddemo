package com.carddemo.common.fixture;

import java.util.LinkedHashMap;
import java.util.List;

/**
 * One chain of tools/runjcl/chains.json: inputs (DSN -> LRECL), setup and job JCL,
 * outputs (DSN -> copybook layout or {@code text}), SYSOUT steps ({@code JOB/STEP})
 * and, for Db2 chains, the seed script and tables (table -> ORDER BY columns).
 */
public record ChainDef(String name, String description, String clock, LinkedHashMap<String, Integer> inputs,
                       List<String> setup, List<String> jobs, LinkedHashMap<String, String> outputs,
                       List<String> sysout, Database database) {

    public record Database(String seed, LinkedHashMap<String, String> tables) {}
}
