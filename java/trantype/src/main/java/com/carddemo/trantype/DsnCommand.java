package com.carddemo.trantype;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The {@code RUN PROGRAM(pgm) ... PARMS('...')} subcommand of a TSO batch step
 * ({@code EXEC PGM=IKJEFT01}, SYSTSIN in-stream), read from the job's JCL member, with the same
 * pattern the off-host JCL runner uses.
 *
 * @see "BR-TT-034, BR-TT-035"
 */
public record DsnCommand(String program, String parms) {

    private static final Pattern RUN = Pattern.compile(
            "RUN\\s+PROGRAM\\(([A-Z0-9#@$]+)\\)(?:.*?PARMS?\\('([^']*)'\\))?",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    public static DsnCommand parse(String systsin) {
        Matcher m = RUN.matcher(systsin);
        if (!m.find()) {
            throw new IllegalArgumentException("no DSN RUN PROGRAM(...) subcommand");
        }
        return new DsnCommand(m.group(1).toUpperCase(), m.group(2));
    }

    /** The first {@code RUN PROGRAM} in the JCL member (only the in-stream SYSTSIN holds one). */
    public static DsnCommand fromJcl(Path jcl) {
        try {
            return parse(Files.readString(jcl));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
