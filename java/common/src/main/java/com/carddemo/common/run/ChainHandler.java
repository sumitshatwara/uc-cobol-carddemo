package com.carddemo.common.run;

/**
 * Service-provider interface implemented by each batch slice, one bean per chain of
 * tools/runjcl/chains.json.  The foundation ({@link ChainRunService}) has already loaded
 * the fixture case into PostgreSQL and set the frozen clock when {@link #run} is called;
 * the handler launches its Spring Batch job(s) and reports, through {@link ChainRun},
 * the step return codes, the SYSOUT lines and the output datasets.  The foundation then
 * writes {@code rc.json}, {@code sysout/*.txt} and the Db2 table snapshots.
 */
public interface ChainHandler {

    /** Chain name as in tools/runjcl/chains.json, e.g. {@code interest}. */
    String chain();

    void run(ChainRun run) throws Exception;
}
