package com.carddemo.common;

import com.carddemo.common.clock.FrozenClock;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class FrozenClockTest {
    @Test
    void usesTheChainClockAndVerifiedCobolCurrentDate() {
        FrozenClock clock = FrozenClock.defaultClock();
        assertThat(clock.instant()).isEqualTo(Instant.parse("2022-07-05T10:00:00Z"));
        assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
        assertThat(clock.cobolCurrentDate()).isEqualTo("2022070510000000+0000");
    }
}
