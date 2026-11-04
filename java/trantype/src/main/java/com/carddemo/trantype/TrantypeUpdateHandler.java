package com.carddemo.trantype;

import com.carddemo.common.run.ChainHandler;
import com.carddemo.common.run.RunContext;
import com.carddemo.common.run.RunResult;
import org.springframework.stereotype.Component;

@Component
public final class TrantypeUpdateHandler implements ChainHandler {
    private final TransactionTypeMaintenanceService maintenance = new TransactionTypeMaintenanceService();

    @Override
    public String chain() {
        return "trantype-update";
    }

    @Override
    public RunResult run(RunContext context) {
        return TrantypeChainSupport.run(context, "MNTTRDB2", "COBTUPDT", "",
                (sql, sysout) -> maintenance.run(context.fixtureLoader().rawRecords("INPFILE"), sql, sysout));
    }
}
