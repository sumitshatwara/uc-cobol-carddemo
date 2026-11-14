package com.carddemo.app.api;

import com.carddemo.common.fixture.ChainCatalog;
import com.carddemo.common.fixture.ChainDef;
import com.carddemo.common.run.ChainRunService;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Job catalogue for the operations console: the batch chains (replacing the operator's
 * JCL library), their fixture cases, and the legacy golden outputs of each case.
 */
@RestController
@RequestMapping("/api")
public class JobsController {

    private final ChainCatalog catalog;
    private final ChainRunService runs;
    private final JclInfo jcl;

    public JobsController(ChainCatalog catalog, ChainRunService runs, JclInfo jcl) {
        this.catalog = catalog;
        this.runs = runs;
        this.jcl = jcl;
    }

    public record CaseInfo(String name, String readme) {}

    public record JobInfo(String chain, String description, String clock, List<JclInfo.Job> jobs, List<String> setup,
                          Map<String, Integer> inputs, Map<String, String> outputs, List<String> sysout,
                          List<String> tables, boolean implemented, int caseCount, List<CaseInfo> cases) {}

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "UP");
        m.put("implementedChains", runs.handlers().keySet());
        return m;
    }

    @GetMapping("/jobs")
    public List<JobInfo> jobs() {
        return catalog.all().values().stream().map(c -> info(c, false)).toList();
    }

    @GetMapping("/jobs/{chain}")
    public JobInfo job(@PathVariable String chain) {
        return info(catalog.get(chain), true);
    }

    /** {@code kind} = {@code input} or {@code expected} (legacy golden outputs). */
    @GetMapping("/jobs/{chain}/cases/{caseName}/files")
    public List<String> caseFiles(@PathVariable String chain, @PathVariable String caseName, @RequestParam(defaultValue = "expected") String kind) {
        return FileListing.list(base(chain, caseName, kind));
    }

    @GetMapping(value = "/jobs/{chain}/cases/{caseName}/file", produces = "text/plain;charset=UTF-8")
    public String caseFile(@PathVariable String chain, @PathVariable String caseName, @RequestParam(defaultValue = "expected") String kind,
                           @RequestParam String path) {
        return FileListing.read(base(chain, caseName, kind), path);
    }

    private Path base(String chain, String caseName, String kind) {
        if (!kind.equals("input") && !kind.equals("expected")) {
            throw new IllegalArgumentException("kind must be input or expected");
        }
        return catalog.caseDir(chain, caseName).resolve(kind);
    }

    private JobInfo info(ChainDef c, boolean withCases) {
        List<String> caseNames = catalog.cases(c.name());
        List<CaseInfo> cases = withCases ? caseNames.stream().map(n -> new CaseInfo(n, catalog.caseSummary(c.name(), n))).toList() : null;
        List<String> tables = c.database() == null ? List.of() : List.copyOf(c.database().tables().keySet());
        return new JobInfo(c.name(), c.description(), c.clock(), c.jobs().stream().map(jcl::job).toList(), c.setup(), c.inputs(),
                c.outputs(), c.sysout(), tables, runs.implemented(c.name()), caseNames.size(), cases);
    }
}
