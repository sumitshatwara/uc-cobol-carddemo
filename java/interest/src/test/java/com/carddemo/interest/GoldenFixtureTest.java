package com.carddemo.interest;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.common.TestRepo;
import com.carddemo.interest.IntCalcHarness.Result;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Runs the program port on the input of every interest fixture case (in memory, no database) and
 * compares SYSOUT, SYSTRAN and the account master with the legacy golden files line for line.
 * The authoritative regression gate is still {@code make parity-java} (field-level compare.py).
 */
class GoldenFixtureTest {

    @ParameterizedTest
    @ValueSource(strings = {"i01-baseline", "i02-tie-half-cent", "i03-zero-rate-skipped", "i04-default-group-fallback",
            "i05-multi-category", "i06-negative-balance", "i07-nontie-rounding"})
    void fixtureCaseMatchesGoldenFiles(String caseName) {
        Result r = IntCalcHarness.run(FakeFiles.fromFixture(caseName));
        assertThat(r.rc()).isZero();
        assertThat(r.sysout()).containsExactlyElementsOf(TestRepo.expectedLines("interest", caseName, "sysout/INTCALC.STEP15.txt"));
        assertThat(r.files().systranLines()).containsExactlyElementsOf(golden(caseName, InterestChainHandler.SYSTRAN_DSN));
        assertThat(r.files().accountLines()).containsExactlyElementsOf(golden(caseName, InterestChainHandler.ACCTDATA_DSN));
    }

    private static List<String> golden(String caseName, String dsn) {
        return TestRepo.expectedLines("interest", caseName, "datasets/" + dsn + ".txt");
    }
}
