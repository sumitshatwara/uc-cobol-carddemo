package com.carddemo.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.common.clock.FrozenClock;
import com.carddemo.common.cobol.CobolNumeric;
import com.carddemo.common.fixture.PyJson;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** rc.json / manifest.json format, frozen clock and COBOL numeric conventions. */
class HarnessFormatTest {

    @Test
    void rcJsonIsByteIdenticalToTheHarness() {
        Map<String, Integer> steps = new LinkedHashMap<>();
        for (String s : new String[] {"STEP30", "STEP05", "STEP10", "STEP15", "STEP20", "STEP25"}) {
            steps.put(s, 0);
        }
        Map<String, Object> job = new LinkedHashMap<>();
        job.put("steps", steps);
        job.put("maxrc", 0);
        assertThat(PyJson.dumps(Map.of("INTCALC", job))).isEqualTo(TestRepo.expectedText("interest", "i02-tie-half-cent", "rc.json"));
    }

    @Test
    void frozenClockFormats() {
        FrozenClock clock = new FrozenClock("2022-07-05 10:00:00.00");
        assertThat(clock.db2Timestamp()).isEqualTo("2022-07-05-10.00.00.000000");
        assertThat(clock.currentDate16()).isEqualTo("2022070510000000");
        clock.set("2022-07-05 10:00:00.37");
        assertThat(clock.db2Timestamp()).isEqualTo("2022-07-05-10.00.00.370000");
    }

    @Test
    void cobolTruncationIsNotRounding() {
        // 1235.50 * 12.00 / 1200 = 12.355 -> 12.35 (fixtures/interest/i02-tie-half-cent)
        BigDecimal raw = new BigDecimal("1235.50").multiply(new BigDecimal("12.00")).divide(new BigDecimal("1200"));
        assertThat(CobolNumeric.truncate(raw, 2)).isEqualTo(new BigDecimal("12.35"));
        assertThat(CobolNumeric.truncate(new BigDecimal("-12.359"), 2)).isEqualTo(new BigDecimal("-12.35"));
        assertThat(CobolNumeric.rounded(new BigDecimal("12.355"), 2)).isEqualTo(new BigDecimal("12.36"));
        assertThat(CobolNumeric.fit(new BigDecimal("123456.789"), 4, 2)).isEqualTo(new BigDecimal("3456.78"));
    }
}
