package com.carddemo.online;

import com.carddemo.online.routines.MvsWait;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MvsWaitTest {
    @Test
    @DisplayName("BR-ONL-048 MVSWAIT timed wait and COBSWAIT driver")
    void brOnl048_wait() {
        // Given
        AtomicLong sleptCentiseconds = new AtomicLong(-1);
        MvsWait wait = new MvsWait(sleptCentiseconds::set);

        // When
        var result = wait.run("00003600      VALUE IN CENTISECONDS");
        var nonNumericFinding = new MvsWait().run("A2345678");

        // Then
        assertEquals("00003600", result.parmValue());
        assertEquals(3600, result.mvsWaitTime());
        assertEquals(0, result.rc());
        assertEquals(3600, sleptCentiseconds.get());
        assertEquals(0xff, result.callerSaveAreaByte12());
        assertEquals(12345678, nonNumericFinding.mvsWaitTime());
        assertEquals(0, nonNumericFinding.rc());
    }
}
