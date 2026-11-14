package com.carddemo.online;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carddemo.online.routines.DateFormatRoutine;
import com.carddemo.online.routines.WaitRoutine;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** HLASM ports COBDATFT and MVSWAIT (analysis-only legacy, never assembled or executed). */
class RoutinesTest {

    private final DateFormatRoutine cobdatft = new DateFormatRoutine();

    private static String out(char[] rec) {
        return DateFormatRoutine.field(rec, DateFormatRoutine.COOUTDT, 20);
    }

    private static String err(char[] rec) {
        return DateFormatRoutine.field(rec, DateFormatRoutine.COERMSG, 38);
    }

    @Test
    void BR_ONL_060_linkageUpdatesRecordInPlaceAndReturnsZero() {
        char[] rec = DateFormatRoutine.record("1", "20250315", "1", "", "");
        assertEquals(80, rec.length);
        assertEquals(0, cobdatft.call(rec));
        char[] bad = DateFormatRoutine.record("9", "garbage", "1", "", "");
        assertEquals(0, cobdatft.call(bad)); // no exception, R15 still 0
    }

    @Test
    void BR_ONL_061_yyyymmddToIsoDate() {
        char[] rec = DateFormatRoutine.record("1", "20250315", "1", "", "");
        cobdatft.call(rec);
        assertTrue(out(rec).startsWith("2025-03-15"));
        assertEquals(" ".repeat(38), err(rec));
    }

    @Test
    void BR_ONL_062_isoDateToYyyymmddWithoutSeparatorCheck() {
        char[] rec = DateFormatRoutine.record("2", "2025/03/15", "2", "", "");
        cobdatft.call(rec);
        assertTrue(out(rec).startsWith("20250315"));
    }

    @Test
    void BR_ONL_062_finding12_hyphenGuardComparesTwentyBytes() {
        // F-12: CLC COINPDT+4,=C'-' is a 20-byte compare, so a hyphenated type-1 date is not rejected
        char[] rec = DateFormatRoutine.record("1", "2025-03-15", "1", "", "");
        assertEquals(0, cobdatft.call(rec));
        assertTrue(out(rec).startsWith("2025--0-3-"));
        assertEquals(" ".repeat(38), err(rec));
    }

    @Test
    void BR_ONL_063_invalidInputMessageLeavesOutputUnchanged() {
        for (String[] c : new String[][] {{"2", "1"}, {"1", "2"}, {"3", "1"}}) {
            char[] rec = DateFormatRoutine.record(c[0], "20250315", c[1], "", "");
            assertEquals(0, cobdatft.call(rec));
            assertTrue(err(rec).startsWith("INVALID INPUT"), c[0] + "/" + c[1]);
            assertEquals(" ".repeat(20), out(rec));
        }
    }

    @Test
    void BR_ONL_064_mvswaitSleepsHundredthsInjectable() {
        List<Long> slept = new ArrayList<>();
        WaitRoutine w = new WaitRoutine(slept::add);
        assertEquals(3600, w.cobswait("00003600"));
        assertEquals(List.of(36_000L), slept); // 36 seconds
        assertEquals(0, w.mvswait(5));
        assertEquals(0, new WaitRoutine(WaitRoutine.Sleeper.NO_OP).mvswait(100_000));
    }
}
