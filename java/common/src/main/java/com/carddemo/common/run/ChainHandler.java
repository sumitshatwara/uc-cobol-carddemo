package com.carddemo.common.run;

import java.util.List;

public interface ChainHandler {
    String chain();

    default List<String> extraTables() {
        return List.of();
    }

    RunResult run(RunContext context) throws Exception;
}
