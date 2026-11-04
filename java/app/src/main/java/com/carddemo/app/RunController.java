package com.carddemo.app;

import com.carddemo.common.estate.ChainConfig;
import com.carddemo.common.estate.EstateRoot;
import com.carddemo.common.export.JobRc;
import com.carddemo.common.run.ChainRunner;
import com.carddemo.common.run.RunResult;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class RunController {
    private final ChainRunner chainRunner;
    private final RunStore runStore;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Path root = EstateRoot.root();

    public RunController(ChainRunner chainRunner, RunStore runStore) {
        this.chainRunner = chainRunner;
        this.runStore = runStore;
    }

    @GetMapping("/jobs")
    public List<Map<String, Object>> jobs() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (String name : chainRunner.config().names()) {
            ChainConfig.Chain chain = chainRunner.config().get(name);
            Map<String, Object> job = new LinkedHashMap<>();
            job.put("chain", name);
            job.put("description", chain.description());
            job.put("jobs", chain.jobs());
            job.put("setup", chain.setup());
            job.put("cases", chainRunner.config().cases(name));
            job.put("javaAvailable", chainRunner.handlers().containsKey(name));
            result.add(job);
        }
        return result;
    }

    @PostMapping("/runs")
    public ResponseEntity<?> run(@RequestBody RunRequest request) throws Exception {
        ChainConfig.Chain chain = request.chain() == null ? null : chainRunner.config().get(request.chain());
        if (chain == null || request.caseName() == null
                || !chainRunner.config().cases(request.chain()).contains(request.caseName())) {
            return ResponseEntity.badRequest().body(Map.of("error", "unknown chain or fixture case"));
        }
        if (!chainRunner.handlers().containsKey(request.chain())) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "no Java handler registered for chain " + request.chain()));
        }
        String runId = UUID.randomUUID().toString();
        Path actual = root.resolve("work/java-runs").resolve(runId).resolve("actual");
        Instant startedAt = Instant.now();
        long startNanos = System.nanoTime();
        RunResult result = chainRunner.run(request.chain(), request.caseName(), actual, runId);
        long durationMs = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
        List<String> files = listFiles(actual);
        RunStore.RunSummary summary = RunStore.RunSummary.completed(runId, request.chain(), request.caseName(),
                result, startedAt, durationMs, files, actual.toString());
        runStore.put(summary);
        return ResponseEntity.ok(summary);
    }

    @GetMapping("/runs")
    public List<RunStore.RunSummary> runs() {
        return runStore.all();
    }

    @GetMapping("/runs/{runId}")
    public ResponseEntity<?> run(@PathVariable String runId) {
        RunStore.RunSummary run = runStore.get(runId);
        return run == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(run);
    }

    @GetMapping(value = "/runs/{runId}/files", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> file(@PathVariable String runId, @RequestParam String path) throws IOException {
        RunStore.RunSummary run = runStore.get(runId);
        if (run == null) {
            return ResponseEntity.notFound().build();
        }
        Path base = Path.of(run.outputDirectory()).toAbsolutePath().normalize();
        Path file = base.resolve(path).normalize();
        if (!file.startsWith(base) || !Files.isRegularFile(file)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Files.readString(file, StandardCharsets.UTF_8));
    }

    @GetMapping("/runs/{runId}/datasets/{dsn}/decoded")
    public ResponseEntity<?> decoded(@PathVariable String runId, @PathVariable String dsn) throws IOException {
        RunStore.RunSummary run = runStore.get(runId);
        if (run == null) {
            return ResponseEntity.notFound().build();
        }
        Path actual = Path.of(run.outputDirectory());
        Path manifestPath = actual.resolve("manifest.json");
        Path dataPath = actual.resolve("datasets").resolve(dsn + ".txt");
        if (!Files.isRegularFile(manifestPath) || !Files.isRegularFile(dataPath)) {
            return ResponseEntity.notFound().build();
        }
        Map<String, Map<String, Object>> manifest = mapper.readValue(manifestPath.toFile(),
                new TypeReference<>() { });
        Map<String, Object> metadata = manifest.get(dsn);
        if (metadata == null) {
            return ResponseEntity.notFound().build();
        }
        String layout = String.valueOf(metadata.get("layout"));
        List<String> lines = Files.readAllLines(dataPath, StandardCharsets.ISO_8859_1);
        if ("text".equals(layout)) {
            return ResponseEntity.ok(Map.of("dsn", dsn, "layout", layout, "records", lines));
        }
        int lrecl = ((Number) metadata.get("lrecl")).intValue();
        List<Map<String, Object>> records = new ArrayList<>();
        for (String line : lines) {
            byte[] source = line.getBytes(StandardCharsets.ISO_8859_1);
            byte[] record = new byte[lrecl];
            java.util.Arrays.fill(record, (byte) ' ');
            System.arraycopy(source, 0, record, 0, Math.min(source.length, lrecl));
            records.add(com.carddemo.common.codec.RecordCodec.decode(layout, record));
        }
        return ResponseEntity.ok(Map.of("dsn", dsn, "layout", layout, "records", records));
    }

    @PostMapping("/runs/{runId}/parity")
    public ResponseEntity<?> parity(@PathVariable String runId) throws IOException, InterruptedException {
        RunStore.RunSummary run = runStore.get(runId);
        if (run == null) {
            return ResponseEntity.notFound().build();
        }
        Path report = Path.of(run.outputDirectory()).getParent().resolve("parity.md");
        Files.deleteIfExists(report);
        Process process = new ProcessBuilder("python3", "tools/parity/compare.py", run.chain(), run.caseName(),
                "--actual", run.outputDirectory(), "--label", "java", "--report", report.toString())
                .directory(root.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        String markdown = Files.isRegularFile(report) ? Files.readString(report) : "";
        String summaryLine = output.lines().findFirst().orElse("");
        return ResponseEntity.ok(Map.of(
                "verdict", exitCode == 0 ? "PASS" : "FAIL",
                "exitCode", exitCode,
                "summaryLine", summaryLine,
                "reportMarkdown", markdown));
    }

    @GetMapping(value = "/parity/report", produces = "text/markdown")
    public ResponseEntity<String> parityReport() throws IOException {
        Path report = root.resolve("work/parity/report-java.md");
        return Files.isRegularFile(report)
                ? ResponseEntity.ok(Files.readString(report))
                : ResponseEntity.notFound().build();
    }

    private static List<String> listFiles(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (var files = Files.walk(root)) {
            return files.filter(Files::isRegularFile).map(root::relativize).map(Path::toString)
                    .map(path -> path.replace('\\', '/')).sorted(Comparator.naturalOrder()).toList();
        }
    }

    public record RunRequest(String chain, String caseName) {
    }
}
