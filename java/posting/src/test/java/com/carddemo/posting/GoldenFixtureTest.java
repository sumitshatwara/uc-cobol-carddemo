package com.carddemo.posting;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.common.TestRepo;
import com.carddemo.common.fixture.FixtureLoader;
import com.carddemo.common.run.SysoutWriter;
import com.carddemo.posting.post.PostTranProgram;
import com.carddemo.posting.report.TranReptSort;
import com.carddemo.posting.report.TransactionReport;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Both programs on every posting fixture case with in-memory files: SYSOUT, rejects and the report
 * must equal the legacy golden files (the database-backed run is checked by make parity-java).
 */
class GoldenFixtureTest {

    @ParameterizedTest
    @ValueSource(strings = {"p01-baseline-mixed", "p02-invalid-card", "p03-account-missing", "p04-overlimit-reject",
            "p05-expired-account", "p06-credits-new-category", "p07-empty-day"})
    void goldenSysoutRejectsAndReport(String caseName) {
        var dir = TestRepo.fixture("posting", caseName);
        InMemoryPostingStore store = InMemoryPostingStore.fromFixture(caseName);
        SysoutWriter s15 = new SysoutWriter();
        PostTranProgram p = new PostTranProgram(store, s15, TestData.PROC_TS);
        p.runAll(FixtureLoader.readRecords(dir, "AWS.M2.CARDDEMO.DALYTRAN.PS", 350));
        assertThat(s15.lines()).containsExactlyElementsOf(TestRepo.expectedLines("posting", caseName, "sysout/POSTTRAN.STEP15.txt"));
        assertThat(p.rejects().stream().map(Cobol::rstrip).toList())
                .containsExactlyElementsOf(TestRepo.expectedLines("posting", caseName, "datasets/AWS.M2.CARDDEMO.DALYREJS.G0001V00.txt"));

        SysoutWriter s10 = new SysoutWriter();
        List<String> report = new TransactionReport(store, s10).run(TranReptSort.sort(store.transactRecords()),
                FixtureLoader.readRecords(dir, "AWS.M2.CARDDEMO.DATEPARM", 80));
        assertThat(s10.lines()).containsExactlyElementsOf(TestRepo.expectedLines("posting", caseName, "sysout/TRANREPT.STEP10R.txt"));
        assertThat(new ArrayList<>(report.stream().map(Cobol::rstrip).toList()))
                .containsExactlyElementsOf(TestRepo.expectedLines("posting", caseName, "datasets/AWS.M2.CARDDEMO.TRANREPT.G0001V00.txt"));
    }
}
