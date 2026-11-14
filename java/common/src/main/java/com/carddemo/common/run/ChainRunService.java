package com.carddemo.common.run;

import com.carddemo.common.clock.FrozenClock;
import com.carddemo.common.codec.CopybookParser;
import com.carddemo.common.fixture.ChainCatalog;
import com.carddemo.common.fixture.ChainDef;
import com.carddemo.common.fixture.FixtureLoader;
import com.carddemo.common.fixture.PyJson;
import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.postgresql.PGConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Service;

/**
 * Runs a Java chain on a fixture case and writes its outputs in the recorded format
 * ({@code datasets/}, {@code sysout/}, {@code rc.json}, {@code db/}), ready for
 * {@code tools/parity/compare.py <chain> <case> --actual <outDir> --label java}.
 * Runs are serialised: every run owns the whole {@code carddemo} schema.
 */
@Service
public class ChainRunService {

    private static final Logger log = LoggerFactory.getLogger(ChainRunService.class);

    private final ChainCatalog catalog;
    private final FixtureLoader loader;
    private final FrozenClock clock;
    private final JdbcTemplate jdbc;
    private final DataSource dataSource;
    private final CopybookParser copybooks;
    private final Map<String, ChainHandler> handlers;

    public ChainRunService(ChainCatalog catalog, FixtureLoader loader, FrozenClock clock, JdbcTemplate jdbc,
                           DataSource dataSource, CopybookParser copybooks, List<ChainHandler> handlers) {
        this.catalog = catalog;
        this.loader = loader;
        this.clock = clock;
        this.jdbc = jdbc;
        this.dataSource = dataSource;
        this.copybooks = copybooks;
        this.handlers = handlers.stream().collect(Collectors.toMap(ChainHandler::chain, Function.identity(), (a, b) -> {
            throw new IllegalStateException("two handlers for chain " + a.chain());
        }, TreeMap::new));
    }

    /** Chains that have a Java implementation. */
    public Map<String, ChainHandler> handlers() {
        return handlers;
    }

    public boolean implemented(String chain) {
        return handlers.containsKey(chain);
    }

    public synchronized RunResult run(String chainName, String caseName, Path outDir) {
        long t0 = System.currentTimeMillis();
        ChainDef chain = catalog.get(chainName);
        ChainHandler handler = handlers.get(chainName);
        try {
            resetDir(outDir);
            if (handler == null) {
                throw new IllegalStateException("no Java implementation for chain " + chainName
                        + " (implemented: " + handlers.keySet() + ")");
            }
            Path caseDir = catalog.caseDir(chainName, caseName);
            clock.set(chain.clock());
            loader.load(chain, caseDir);
            ChainRun run = new ChainRun(chain, caseName, caseDir, outDir, clock, jdbc, new DatasetWriter(outDir, jdbc, copybooks));
            handler.run(run);
            writeOutputs(chain, run, outDir);
            return new RunResult(chainName, caseName, outDir, run.maxRc(), System.currentTimeMillis() - t0, null);
        } catch (Exception e) {
            log.error("{}/{} failed", chainName, caseName, e);
            return new RunResult(chainName, caseName, outDir, -1, System.currentTimeMillis() - t0,
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private void writeOutputs(ChainDef chain, ChainRun run, Path outDir) throws Exception {
        Files.writeString(outDir.resolve("rc.json"), PyJson.dumps(run.rcJson()));
        Path sysoutDir = Files.createDirectories(outDir.resolve("sysout"));
        for (String ref : chain.sysout()) {
            String key = ref.replace('/', '.');
            SysoutWriter w = run.sysouts().get(key);
            Files.writeString(sysoutDir.resolve(key + ".txt"), w == null ? "" : w.text(), StandardCharsets.ISO_8859_1);
        }
        for (Map.Entry<String, SysoutWriter> e : run.sysouts().entrySet()) {
            Path p = sysoutDir.resolve(e.getKey() + ".txt");
            if (!Files.exists(p)) {
                Files.writeString(p, e.getValue().text(), StandardCharsets.ISO_8859_1);
            }
        }
        if (chain.database() != null) {
            Path dbDir = Files.createDirectories(outDir.resolve("db"));
            for (Map.Entry<String, String> t : chain.database().tables().entrySet()) {
                Files.writeString(dbDir.resolve(t.getKey() + ".csv"), snapshot(t.getKey(), t.getValue()));
            }
        }
    }

    /** Same bytes as the harness: {@code COPY (SELECT * FROM t ORDER BY k) TO STDOUT WITH CSV HEADER}. */
    String snapshot(String table, String orderBy) throws Exception {
        Connection conn = DataSourceUtils.getConnection(dataSource);
        try {
            StringWriter out = new StringWriter();
            conn.unwrap(PGConnection.class).getCopyAPI()
                    .copyOut("COPY (SELECT * FROM " + table + " ORDER BY " + orderBy + ") TO STDOUT WITH CSV HEADER", out);
            return out.toString();
        } finally {
            DataSourceUtils.releaseConnection(conn, dataSource);
        }
    }

    private static void resetDir(Path dir) {
        try {
            if (Files.exists(dir)) {
                try (Stream<Path> s = Files.walk(dir)) {
                    for (Path p : (Iterable<Path>) s.sorted(Comparator.reverseOrder())::iterator) {
                        Files.delete(p);
                    }
                }
            }
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
