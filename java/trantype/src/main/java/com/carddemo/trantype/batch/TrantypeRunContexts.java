package com.carddemo.trantype.batch;

import com.carddemo.common.run.ChainRun;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** Hands the foundation's {@link ChainRun} to a job's tasklet through a job parameter ({@code runKey}). */
@Component
public class TrantypeRunContexts {

    /** What a job step reports back to its chain handler. */
    public static final class Slot {
        final ChainRun run;
        volatile Integer rc;

        Slot(ChainRun run) { this.run = run; }

        public ChainRun run() { return run; }
        public Integer rc() { return rc; }
        public void rc(int rc) { this.rc = rc; }
    }

    private final Map<String, Slot> slots = new ConcurrentHashMap<>();

    public String open(ChainRun run) {
        String key = UUID.randomUUID().toString();
        slots.put(key, new Slot(run));
        return key;
    }

    public Slot get(String key) {
        Slot s = slots.get(key);
        if (s == null) {
            throw new IllegalStateException("no chain run for key " + key);
        }
        return s;
    }

    public Slot close(String key) {
        return slots.remove(key);
    }
}
