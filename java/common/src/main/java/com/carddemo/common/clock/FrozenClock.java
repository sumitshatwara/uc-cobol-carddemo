package com.carddemo.common.clock;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The batch clock.  Legacy runs freeze {@code COB_CURRENT_DATE} per chain
 * (tools/runjcl/chains.json, {@code "clock": "2022-07-05 10:00:00.00"}); the Java
 * jobs must read time only from here, never from {@code LocalDateTime.now()}.
 * The hundredths matter: without them {@code PROC-TS} drifts between runs.
 */
@Component
public class FrozenClock {

    private static final DateTimeFormatter CHAIN_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SS");
    private volatile LocalDateTime now;

    public FrozenClock(@Value("${carddemo.clock:2022-07-05 10:00:00.00}") String clock) {
        set(clock);
    }

    /** Sets the clock from the chains.json format {@code yyyy-MM-dd HH:mm:ss.SS}. */
    public final void set(String chainClock) {
        this.now = LocalDateTime.parse(chainClock, CHAIN_FORMAT);
    }

    public LocalDateTime now() {
        return now;
    }

    /** {@code FUNCTION CURRENT-DATE(1:16)}: {@code YYYYMMDDhhmmsshh}. */
    public String currentDate16() {
        return now.format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")) + String.format("%02d", now.getNano() / 10_000_000);
    }

    /** {@code FUNCTION CURRENT-DATE(1:8)}: {@code YYYYMMDD}. */
    public String currentDate8() {
        return now.format(DateTimeFormatter.BASIC_ISO_DATE);
    }

    /**
     * Db2-format timestamp {@code YYYY-MM-DD-hh.mm.ss.hh0000} as built by the programs'
     * {@code Z-GET-DB2-FORMAT-TIMESTAMP} paragraphs (hundredths followed by four zeros).
     */
    public String db2Timestamp() {
        return now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HH.mm.ss.")) + String.format("%02d", now.getNano() / 10_000_000) + "0000";
    }
}
