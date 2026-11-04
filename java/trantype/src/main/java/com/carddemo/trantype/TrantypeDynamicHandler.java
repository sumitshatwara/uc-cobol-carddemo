package com.carddemo.trantype;

import com.carddemo.common.run.ChainHandler;
import com.carddemo.common.run.RunContext;
import com.carddemo.common.run.RunResult;
import org.springframework.stereotype.Component;

@Component
public final class TrantypeDynamicHandler implements ChainHandler {
    private final DynamicSqlService dynamic = new DynamicSqlService();
    private final TransactionTypeMaintenanceService maintenance = new TransactionTypeMaintenanceService();

    @Override
    public String chain() {
        return "trantype-dynamic";
    }

    @Override
    public RunResult run(RunContext context) {
        return TrantypeChainSupport.run(context, "MNTTRDYN", "CBTRNDYN", "COBTUPDT",
                (sql, sysout) -> dynamic.run(context.fixtureLoader().rawRecords("DYNSQL"), "COBTUPDT", sql,
                        maintenance, context.fixtureLoader().rawRecords("INPFILE"), sysout).returnCode());
    }
}
