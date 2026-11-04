package com.carddemo.app;

import com.carddemo.common.estate.EstateRoot;
import com.carddemo.common.run.ChainRunner;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

@Component
public class CliRunner implements ApplicationRunner, ExitCodeGenerator {
    private final ChainRunner chainRunner;
    private int exitCode;

    public CliRunner(ChainRunner chainRunner) {
        this.chainRunner = chainRunner;
    }

    @Override
    public void run(ApplicationArguments arguments) throws Exception {
        if (!arguments.containsOption("carddemo.cli")) {
            return;
        }
        String mode = arguments.getOptionValues("carddemo.cli").getFirst();
        if ("list-chains".equals(mode)) {
            chainRunner.handlers().keySet().stream().sorted().forEach(System.out::println);
            return;
        }
        if (!"run-all".equals(mode)) {
            throw new IllegalArgumentException("unsupported --carddemo.cli mode " + mode);
        }
        String requested = arguments.getOptionValues("carddemo.chain") == null
                ? null : arguments.getOptionValues("carddemo.chain").getFirst();
        if (requested != null && !chainRunner.handlers().containsKey(requested)) {
            System.err.println("no Java handler registered for chain " + requested);
            exitCode = 3;
            return;
        }
        for (String chain : chainRunner.handlers().keySet().stream().sorted().toList()) {
            if (requested != null && !requested.equals(chain)) {
                continue;
            }
            for (String caseName : chainRunner.config().cases(chain)) {
                Path output = EstateRoot.root().resolve("work/parity-java").resolve(chain).resolve(caseName).resolve("actual");
                chainRunner.run(chain, caseName, output);
                System.out.println(chain + " " + caseName);
            }
        }
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
