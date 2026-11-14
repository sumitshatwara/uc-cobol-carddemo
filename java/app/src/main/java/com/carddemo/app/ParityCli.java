package com.carddemo.app;

import com.carddemo.common.fixture.ChainCatalog;
import com.carddemo.common.fixture.ChainDef;
import com.carddemo.common.fixture.FixtureLoader;
import com.carddemo.common.repo.RepoPaths;
import com.carddemo.common.run.ChainRunService;
import com.carddemo.common.run.RunResult;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.stereotype.Component;

/**
 * Batch entry point used by the Makefile (no web server):
 * <pre>
 *   java -jar carddemo-app.jar --spring.main.web-application-type=none \
 *        --carddemo.cli.mode=run --carddemo.cli.chain=interest [--carddemo.cli.cases=i01-baseline,...] \
 *        [--carddemo.cli.out=work/java]
 *   java -jar carddemo-app.jar --spring.main.web-application-type=none --carddemo.cli.mode=check-fixtures
 * </pre>
 * {@code run} writes {@code <out>/<chain>/<case>/actual} for every case (default: all cases);
 * {@code make parity-java} then compares each with tools/parity/compare.py.
 * {@code check-fixtures} loads every case of every chain into PostgreSQL (loader smoke test).
 */
@Component
public class ParityCli implements ApplicationRunner, ExitCodeGenerator {

    private final ChainRunService runs;
    private final ChainCatalog catalog;
    private final FixtureLoader loader;
    private final RepoPaths repo;
    private final String mode;
    private final String chains;
    private final String cases;
    private final String out;
    private int exitCode;

    public ParityCli(ChainRunService runs, ChainCatalog catalog, FixtureLoader loader, RepoPaths repo,
                     @Value("${carddemo.cli.mode:}") String mode, @Value("${carddemo.cli.chain:}") String chains,
                     @Value("${carddemo.cli.cases:all}") String cases, @Value("${carddemo.cli.out:}") String out) {
        this.runs = runs;
        this.catalog = catalog;
        this.loader = loader;
        this.repo = repo;
        this.mode = mode;
        this.chains = chains;
        this.cases = cases;
        this.out = out;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (mode.isBlank()) {
            return;
        }
        Path base = out.isBlank() ? repo.work().resolve("java") : repo.root().resolve(out);
        switch (mode) {
            case "run" -> runChains(base);
            case "check-fixtures" -> checkFixtures();
            default -> {
                System.err.println("unknown carddemo.cli.mode " + mode);
                exitCode = 2;
            }
        }
    }

    private void runChains(Path base) {
        List<String> chainList = chains.isBlank() ? new ArrayList<>(runs.handlers().keySet()) : Arrays.asList(chains.split(","));
        for (String chain : chainList) {
            List<String> caseList = cases.equals("all") ? catalog.cases(chain) : Arrays.asList(cases.split(","));
            for (String c : caseList) {
                RunResult r = runs.run(chain, c, base.resolve(chain).resolve(c).resolve("actual"));
                if (r.failed()) {
                    System.out.printf("java %s/%s: ERROR %s%n", chain, c, r.error());
                    exitCode = 1;
                } else {
                    System.out.printf("java %s/%s: MAXRC=%d (%d ms) -> %s%n", chain, c, r.maxRc(), r.durationMs(), repo.root().relativize(r.outDir()));
                }
            }
        }
    }

    private void checkFixtures() {
        for (ChainDef chain : catalog.all().values()) {
            for (String c : catalog.cases(chain.name())) {
                try {
                    loader.load(chain, catalog.caseDir(chain.name(), c));
                    System.out.printf("fixture %s/%s: loaded%n", chain.name(), c);
                } catch (Exception e) {
                    System.out.printf("fixture %s/%s: ERROR %s%n", chain.name(), c, e.getMessage());
                    exitCode = 1;
                }
            }
        }
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
