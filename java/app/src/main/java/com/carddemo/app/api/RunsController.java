package com.carddemo.app.api;

import com.carddemo.common.parity.ParityComparator;
import com.carddemo.common.repo.RepoPaths;
import com.carddemo.common.run.ChainRunService;
import com.carddemo.common.run.RunResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Job submission and output browsing (replaces JCL submit + SDSF SYSOUT browsing).
 * A run executes the Java chain on a fixture case, writes the outputs under
 * {@code work/java/<chain>/<case>/actual} and checks them against the legacy golden
 * files with tools/parity/compare.py.
 */
@RestController
@RequestMapping("/api/runs")
public class RunsController {

    private final ChainRunService runs;
    private final ParityComparator parity;
    private final RepoPaths repo;
    private final ObjectMapper json = new ObjectMapper();
    private final List<RunView> history = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger seq = new AtomicInteger();

    public RunsController(ChainRunService runs, ParityComparator parity, RepoPaths repo) {
        this.runs = runs;
        this.parity = parity;
        this.repo = repo;
    }

    public record RunRequest(String chain, String caseName) {}

    public record Parity(boolean passed, String summary, String report) {}

    public record RunView(String id, String chain, String caseName, Instant startedAt, long durationMs, int maxRc, String error,
                          JsonNode rc, List<String> files, String outDir, Parity parity) {}

    @PostMapping
    public RunView submit(@RequestBody RunRequest req) {
        if (req.chain() == null || req.caseName() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "chain and caseName are required");
        }
        String id = String.format("R%04d", seq.incrementAndGet());
        Instant started = Instant.now();
        Path out = repo.work().resolve("java").resolve(req.chain()).resolve(req.caseName()).resolve("actual");
        RunResult r = runs.run(req.chain(), req.caseName(), out);
        Parity p = null;
        if (!r.failed()) {
            var v = parity.compare(req.chain(), req.caseName(), out, repo.work().resolve("parity/runs/" + id + ".md"), "java");
            p = new Parity(v.passed(), v.summary(), v.reportMarkdown());
        }
        RunView view = new RunView(id, req.chain(), req.caseName(), started, r.durationMs(), r.maxRc(), r.error(), readRc(out),
                FileListing.list(out), repo.root().relativize(out).toString(), p);
        history.add(0, view);
        return view;
    }

    @GetMapping
    public List<RunView> list() {
        synchronized (history) {
            return List.copyOf(history);
        }
    }

    @GetMapping("/{id}")
    public RunView get(@PathVariable String id) {
        synchronized (history) {
            return history.stream().filter(r -> r.id().equals(id)).findFirst()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, id));
        }
    }

    @GetMapping(value = "/{id}/file", produces = "text/plain;charset=UTF-8")
    public String file(@PathVariable String id, @RequestParam String path) {
        return FileListing.read(repo.root().resolve(get(id).outDir()), path);
    }

    private JsonNode readRc(Path out) {
        try {
            Path rc = out.resolve("rc.json");
            return Files.exists(rc) ? json.readTree(rc.toFile()) : null;
        } catch (IOException e) {
            return null;
        }
    }
}
