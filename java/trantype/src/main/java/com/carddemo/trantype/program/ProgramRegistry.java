package com.carddemo.trantype.program;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/**
 * The "load library" for dynamic CALLs: legacy program name to Spring bean.  CBTRNDYN's
 * {@code CALL WS-PGM-NAME} resolves the name read from the JCL PARMS here at run time.
 *
 * @see "BR-TT-034"
 */
@Component
public class ProgramRegistry {

    private final Map<String, CardDemoProgram> programs = new TreeMap<>();

    public ProgramRegistry(List<CardDemoProgram> beans) {
        for (CardDemoProgram p : beans) {
            if (programs.put(p.programId(), p) != null) {
                throw new IllegalStateException("two programs named " + p.programId());
            }
        }
    }

    public static ProgramRegistry of(CardDemoProgram... programs) {
        return new ProgramRegistry(List.of(programs));
    }

    /**
     * GnuCOBOL resolves {@code CALL identifier} on the field contents without trailing spaces.
     *
     * @throws ProgramNotFoundException when no such program exists (legacy: runtime error, run unit ends)
     */
    public CardDemoProgram resolve(String name) {
        CardDemoProgram p = programs.get(name.stripTrailing());
        if (p == null) {
            throw new ProgramNotFoundException(name.stripTrailing());
        }
        return p;
    }

    public Collection<String> names() {
        return programs.keySet();
    }
}
