package com.carddemo.interest;

import com.carddemo.common.clock.FrozenClock;
import java.util.ArrayList;
import java.util.List;

/** Runs {@link Cbact04c} on a {@link FakeFiles} with the INTCALC PARM and the chain's frozen clock. */
final class IntCalcHarness {

    record Result(int rc, List<String> sysout, Cbact04c program, FakeFiles files) {
        List<InterestTran> trans() { return files.written; }
        String lastLine() { return sysout.get(sysout.size() - 1); }
    }

    static final FrozenClock CLOCK = new FrozenClock("2022-07-05 10:00:00.00");

    private IntCalcHarness() {}

    static Result run(FakeFiles files) {
        List<String> out = new ArrayList<>();
        Cbact04c program = new Cbact04c(files, out::add, Cbact04c.JCL_PARM, CLOCK::db2Timestamp);
        int rc = program.run();
        return new Result(rc, out, program, files);
    }

    /** The shared rate table of the fixtures (DEFAULT / GOLD / STANDARD). */
    static FakeFiles withStandardRates() {
        return new FakeFiles()
                .rate("DEFAULT", "01", 1, "21.00").rate("DEFAULT", "01", 2, "27.00").rate("DEFAULT", "02", 1, "0.00")
                .rate("GOLD", "01", 1, "12.00").rate("GOLD", "01", 2, "18.00").rate("GOLD", "02", 1, "0.00")
                .rate("STANDARD", "01", 1, "18.50").rate("STANDARD", "01", 2, "24.00").rate("STANDARD", "02", 1, "0.00");
    }
}
