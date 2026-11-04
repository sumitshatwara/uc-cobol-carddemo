package com.carddemo.trantype;

import com.carddemo.common.export.JobRc;
import com.carddemo.common.run.RunContext;
import com.carddemo.common.run.RunResult;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.BiFunction;

final class TrantypeChainSupport {
    private TrantypeChainSupport() {
    }

    static RunResult run(RunContext context, String job, String program, String parms,
                         BiFunction<SqlSession, List<String>, Integer> body) {
        List<String> sysout = new ArrayList<>();
        DataSource dataSource = Objects.requireNonNull(context.jdbcTemplate().getDataSource());
        int returnCode = new DsnRunService().run(dataSource, program, parms, sysout,
                session -> body.apply(session, sysout));

        context.outputWriter().writeSysout(job, "STEP1", sysout);
        if (context.chainConfig().database() != null) {
            context.chainConfig().database().tables().forEach(context.outputWriter()::snapshotTable);
        }
        return new RunResult(Map.of(job, new JobRc(returnCode,
                new TreeMap<>(Map.of("STEP1", returnCode)))));
    }
}
