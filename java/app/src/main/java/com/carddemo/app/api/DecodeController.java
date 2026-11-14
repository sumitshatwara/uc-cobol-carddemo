package com.carddemo.app.api;

import com.carddemo.common.codec.Copybook;
import com.carddemo.common.codec.CopybookParser;
import com.carddemo.common.codec.RecordCodec;
import com.carddemo.common.fixture.ChainCatalog;
import com.carddemo.common.repo.RepoPaths;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Decodes a dataset file of a run (Java output) or of a fixture case (input / legacy golden)
 * through a copybook, for the console's record views.  Numbers are returned as plain strings
 * so no precision is lost in JSON.
 */
@RestController
@RequestMapping("/api")
public class DecodeController {

    private final RunsController runs;
    private final ChainCatalog catalog;
    private final CopybookParser copybooks;
    private final RepoPaths repo;

    public DecodeController(RunsController runs, ChainCatalog catalog, CopybookParser copybooks, RepoPaths repo) {
        this.runs = runs;
        this.catalog = catalog;
        this.copybooks = copybooks;
        this.repo = repo;
    }

    public record Decoded(String layout, List<String> fields, List<Map<String, String>> records) {}

    @GetMapping("/runs/{id}/decoded")
    public Decoded run(@PathVariable String id, @RequestParam String path, @RequestParam String layout) {
        return decode(FileListing.read(repo.root().resolve(runs.get(id).outDir()), path), layout);
    }

    @GetMapping("/jobs/{chain}/cases/{caseName}/decoded")
    public Decoded fixture(@PathVariable String chain, @PathVariable String caseName, @RequestParam(defaultValue = "expected") String kind,
                           @RequestParam String path, @RequestParam String layout) {
        if (!kind.equals("input") && !kind.equals("expected")) {
            throw new IllegalArgumentException("kind must be input or expected");
        }
        return decode(FileListing.read(catalog.caseDir(chain, caseName).resolve(kind), path), layout);
    }

    private Decoded decode(String text, String layout) {
        Copybook cb = copybooks.get(layout);
        List<Map<String, String>> recs = new ArrayList<>();
        for (String line : text.split("\n")) {
            if (line.isEmpty()) {
                continue;
            }
            Map<String, String> m = new LinkedHashMap<>();
            RecordCodec.decodeLine(cb, line).forEach((k, v) -> m.put(k, v instanceof BigDecimal d ? d.toPlainString() : String.valueOf(v)));
            recs.add(m);
        }
        return new Decoded(cb.name(), new ArrayList<>(cb.namedFields().keySet()), recs);
    }
}
