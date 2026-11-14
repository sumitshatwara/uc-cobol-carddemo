package com.carddemo.interest;

import com.carddemo.common.run.ChainRun;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Hands the current {@link ChainRun} to the STEP15 tasklet and its outcome back to the
 * handler.  Runs are serialised by {@code ChainRunService}, so one slot is enough.
 */
@Component
public class IntCalcRunContext {

    /** Outcome of STEP15. */
    public record Outcome(int rc, List<InterestTran> transactions, List<Cbact04c.CategoryResult> categories, List<Long> rewrittenAccounts) {}

    private volatile ChainRun run;
    private volatile Outcome outcome;

    void start(ChainRun run) {
        this.run = run;
        this.outcome = null;
    }

    ChainRun run() {
        if (run == null) {
            throw new IllegalStateException("intCalcJob launched outside InterestChainHandler");
        }
        return run;
    }

    void complete(Outcome outcome) {
        this.outcome = outcome;
    }

    Outcome outcome() {
        return outcome;
    }

    void clear() {
        run = null;
    }
}
