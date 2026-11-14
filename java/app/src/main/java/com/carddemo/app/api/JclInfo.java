package com.carddemo.app.api;

import com.carddemo.common.repo.RepoPaths;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/** Reads the legacy JCL to list each job's steps and programs for the console (read-only). */
@Component
public class JclInfo {

    private static final Pattern EXEC = Pattern.compile("^//(\\S+)\\s+EXEC\\s+(?:PGM=)?([A-Z0-9$#@]+)");
    private static final Pattern DSN_RUN = Pattern.compile("RUN\\s+PROGRAM\\(([A-Z0-9]+)\\)");

    private final RepoPaths repo;

    public JclInfo(RepoPaths repo) {
        this.repo = repo;
    }

    public record Step(String step, String program) {}

    public record Job(String job, String path, List<Step> steps) {}

    public Job job(String name) {
        Path jcl = find(name);
        List<Step> steps = new ArrayList<>();
        if (jcl != null) {
            try {
                String currentStep = null;
                for (String line : Files.readAllLines(jcl, StandardCharsets.ISO_8859_1)) {
                    if (line.startsWith("//*")) {
                        continue;
                    }
                    Matcher m = EXEC.matcher(line);
                    if (m.find()) {
                        currentStep = m.group(1);
                        steps.add(new Step(currentStep, m.group(2)));
                    }
                    Matcher d = DSN_RUN.matcher(line);
                    if (d.find() && currentStep != null) {
                        steps.add(new Step(currentStep, d.group(1)));
                    }
                }
            } catch (IOException e) {
                // unreadable JCL: show the job without steps
            }
        }
        return new Job(name, jcl == null ? null : repo.root().relativize(jcl).toString(), steps);
    }

    private Path find(String name) {
        try (Stream<Path> s = Files.walk(repo.root().resolve("app"))) {
            return s.filter(p -> {
                String fn = p.getFileName().toString().toUpperCase();
                return fn.equals(name.toUpperCase() + ".JCL");
            }).findFirst().orElse(null);
        } catch (IOException e) {
            return null;
        }
    }
}
