package com.carddemo.trantype;

import com.carddemo.common.run.SysoutWriter;
import com.carddemo.trantype.program.InputFile;
import com.carddemo.trantype.program.ProgramRegistry;
import com.carddemo.trantype.program.RunUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Runs a DSN step on plain objects (no Spring, no database). */
final class Harness {

    static final DsnCommand MNTTRDB2 = new DsnCommand("COBTUPDT", null);
    static final DsnCommand MNTTRDYN = new DsnCommand("CBTRNDYN", "COBTUPDT");

    record Result(List<String> sysout, int rc, InMemoryDb2Session db) {
        String line(int i) { return sysout.get(i); }
    }

    private Harness() {}

    static ProgramRegistry programs() {
        return ProgramRegistry.of(new TransactionTypeMaintenance(), new DynamicSqlDriver());
    }

    static RunUnit runUnit(InMemoryDb2Session db, List<String> inpfile, List<String> dynsql) {
        Map<String, InputFile> dds = new LinkedHashMap<>();
        if (dynsql != null) {
            dds.put("DYNSQL", InputFile.of(dynsql));
        }
        if (inpfile != null) {
            dds.put("INPFILE", InputFile.of(inpfile));
        }
        return new RunUnit(new SysoutWriter(), db, dds, programs());
    }

    static Result run(DsnCommand cmd, InMemoryDb2Session db, List<String> inpfile, List<String> dynsql) {
        RunUnit ru = runUnit(db, inpfile, dynsql);
        int rc = DsnRunner.run(cmd, ru);
        return new Result(ru.sysout().lines(), rc, db);
    }

    static Result update(String... records) {
        return run(MNTTRDB2, InMemoryDb2Session.seeded(), List.of(records), null);
    }

    static Result dynamic(List<String> dynsql, String... records) {
        return run(MNTTRDYN, InMemoryDb2Session.seeded(), List.of(records), dynsql);
    }

    static String pad(String s, int n) {
        return s + " ".repeat(n - s.length());
    }

    /** {@code PROCESSING   } + the 53-byte record. */
    static String processing(String rec) {
        return "PROCESSING   " + pad(rec, 53);
    }
}
