package com.carddemo.common.clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public final class FrozenClock extends Clock {
    private static final DateTimeFormatter CLOCK_FORMAT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SS", Locale.ROOT).withZone(ZoneOffset.UTC);
    private static final String COBOL_CURRENT_DATE = "2022070510000000+0000";
    private final Clock delegate;

    public FrozenClock(String chainClock) {
        this.delegate = Clock.fixed(Instant.from(CLOCK_FORMAT.parse(chainClock)), ZoneOffset.UTC);
    }

    public static FrozenClock defaultClock() {
        return new FrozenClock("2022-07-05 10:00:00.00");
    }

    @Override
    public ZoneId getZone() {
        return delegate.getZone();
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return Clock.fixed(delegate.instant(), zone);
    }

    @Override
    public Instant instant() {
        return delegate.instant();
    }

    /**
     * GnuCOBOL FUNCTION CURRENT-DATE under COB_CURRENT_DATE=2022-07-05 10:00:00.00,
     * verified in the estate as the 21-character value 2022070510000000+0000.
     */
    public String cobolCurrentDate() {
        return COBOL_CURRENT_DATE;
    }
}

@Configuration
class FrozenClockConfiguration {
    @Bean
    FrozenClock frozenClock() {
        return FrozenClock.defaultClock();
    }
}
