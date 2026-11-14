package com.carddemo.posting;

import static com.carddemo.posting.TestData.tran;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.common.TestRepo;
import com.carddemo.common.run.SysoutWriter;
import com.carddemo.posting.report.NumericEdit;
import com.carddemo.posting.report.TranReptSort;
import com.carddemo.posting.report.TransactionReport;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** TRANREPT (REPRO, SORT, CBTRN03C) rules BR-POST-030 .. BR-POST-044, on in-memory files. */
class TranReptRulesTest {

    private static final String CARD_A = "4000000000001001";
    private static final String CARD_B = "4000000000001002";
    private static final List<String> DATEPARM = List.of("2022-01-01 2022-07-06");

    private final InMemoryPostingStore lookups = new InMemoryPostingStore().card(CARD_A, 1001).card(CARD_B, 1002);
    private final SysoutWriter sysout = new SysoutWriter();

    TranReptRulesTest() {
        lookups.types.put("01", "Purchase");
        lookups.types.put("02", "Payment");
        lookups.cats.put("011", "Regular Sales Draft");
        lookups.cats.put("021", "Payment - Bank Transfer");
    }

    private List<String> report(List<String> trans) {
        return new TransactionReport(lookups, sysout).run(trans, DATEPARM);
    }

    private static List<String> trimmed(List<String> lines) {
        return lines.stream().map(Cobol::rstrip).toList();
    }

    @Test
    void BR_POST_030_reproUnloadsTheTransactionMasterInKeyOrder() throws Exception {
        String jcl = Files.readString(TestRepo.root().resolve("app/jcl/TRANREPT.jcl"));
        assertThat(jcl).contains("EXEC PROC=REPROC").contains("DSN=AWS.M2.CARDDEMO.TRANSACT.BKUP(+1)").contains("LRECL=350");
        InMemoryPostingStore s = InMemoryPostingStore.fromFixture("p01-baseline-mixed");
        var p = new com.carddemo.posting.post.PostTranProgram(s, new SysoutWriter(), TestData.PROC_TS);
        p.runAll(com.carddemo.common.fixture.FixtureLoader.readRecords(TestRepo.fixture("posting", "p01-baseline-mixed"),
                "AWS.M2.CARDDEMO.DALYTRAN.PS", 350));
        List<String> backup = s.transactRecords();
        assertThat(backup).hasSize(4).allMatch(r -> r.length() == 350);
        assertThat(backup.stream().map(r -> r.substring(0, 16)).toList()).isSorted();
    }

    @Test
    void BR_POST_031_sortFiltersOnProcessingDateAndSortsStablyByCard() throws Exception {
        String jcl = Files.readString(TestRepo.root().resolve("app/jcl/TRANREPT.jcl"));
        assertThat(jcl).contains("PARM-START-DATE,C'" + TranReptSort.PARM_START_DATE + "'")
                .contains("PARM-END-DATE,C'" + TranReptSort.PARM_END_DATE + "'");
        List<String> in = List.of(
                tran("1", "01", 1, "1.00", CARD_B, "2022-07-05-10.00.00.000000"),
                tran("2", "01", 1, "1.00", CARD_A, "2022-07-07-00.00.00.000000"), // after the end date
                tran("3", "01", 1, "1.00", CARD_A, "2022-07-06-23.59.59.000000"),
                tran("4", "01", 1, "1.00", CARD_B, "2021-12-31-23.59.59.000000"), // before the start date
                tran("5", "01", 1, "1.00", CARD_A, "2022-01-01-00.00.00.000000"));
        List<String> out = TranReptSort.sort(in);
        assertThat(out.stream().map(r -> r.substring(0, 1)).toList()).containsExactly("3", "5", "1");
    }

    @Test
    void BR_POST_032_dateRangeComesFromDateparm() {
        List<String> lines = new TransactionReport(lookups, sysout).run(List.of(tran("1", "1.00", CARD_A)), List.of("2022-03-01 2022-09-30"));
        assertThat(sysout.lines().get(1)).isEqualTo("Reporting from 2022-03-01 to 2022-09-30");
        assertThat(lines.get(0)).contains("Date Range: 2022-03-01 to 2022-09-30");
        SysoutWriter out2 = new SysoutWriter();
        assertThat(new TransactionReport(lookups, out2).run(List.of(tran("1", "1.00", CARD_A)), List.of())).isEmpty();
        assertThat(out2.lines()).containsExactly("START OF EXECUTION OF PROGRAM CBTRN03C", "END OF EXECUTION OF PROGRAM CBTRN03C");
    }

    @Test
    void BR_POST_033_outOfRangeDateEndsTheLoopWithoutTotals() {
        List<String> lines = report(List.of(tran("1", "1.00", CARD_A),
                tran("2", "01", 1, "2.00", CARD_A, "2023-01-01-00.00.00.000000"), tran("3", "3.00", CARD_A)));
        assertThat(lines).hasSize(5); // headers + one detail, no page/grand total
        assertThat(lines).noneMatch(l -> l.startsWith("Grand Total") || l.startsWith("Page Total"));
        assertThat(sysout.lines()).last().isEqualTo("END OF EXECUTION OF PROGRAM CBTRN03C");
    }

    @Test
    void BR_POST_034_eachReportedRecordIsEchoedToSysout() {
        String t = tran("1", "1.00", CARD_A);
        report(List.of(t));
        assertThat(sysout.lines().get(2)).isEqualTo(t).hasSize(350);
    }

    @Test
    void BR_POST_035_headersBeforeTheFirstDetail() {
        List<String> l = trimmed(report(List.of(tran("1", "1.00", CARD_A))));
        assertThat(l.get(0)).isEqualTo("DALYREPT                              Daily Transaction Report                 Date Range: 2022-01-01 to 2022-07-06");
        assertThat(l.get(1)).isEmpty();
        assertThat(l.get(2)).isEqualTo("Transaction ID   Account ID  Transaction Type   Tran Category                      Tran Source            Amount");
        assertThat(l.get(3)).isEqualTo("-".repeat(133));
    }

    @Test
    void BR_POST_036_detailLineFormat() {
        List<String> l = report(List.of(tran("0000000000000003", "02", 1, "-200.00", CARD_A, TestData.PROC_TS)));
        assertThat(Cobol.rstrip(l.get(4))).isEqualTo(
                "0000000000000003 00000001001 02-Payment         0001-Payment - Bank Transfer       POS TERM      -        200.00");
        assertThat(l.get(4)).hasSize(133);
        assertThat(NumericEdit.edit(new BigDecimal("1234567.89"), '-')).isEqualTo("   1,234,567.89");
        assertThat(NumericEdit.edit(new BigDecimal("0.05"), '+')).isEqualTo("+           .05");
    }

    @Test
    void BR_POST_037_zeroAmountsPrintAsBlanks() {
        assertThat(NumericEdit.edit(BigDecimal.ZERO, '-')).isEqualTo(" ".repeat(15));
        assertThat(NumericEdit.edit(BigDecimal.ZERO, '+')).isEqualTo(" ".repeat(15));
        List<String> l = report(List.of(tran("1", "0.00", CARD_A)));
        assertThat(l.get(4).substring(103, 118)).isBlank();
    }

    @Test
    void BR_POST_038_missingLookupsAbend() {
        assertThatThrownBy(() -> report(List.of(tran("1", "1.00", "4000999999999999")))).isInstanceOf(CobolAbend.class);
        assertThat(sysout.lines()).endsWith("INVALID CARD NUMBER : 4000999999999999", "FILE STATUS IS: NNNN0023", "ABENDING PROGRAM");
        SysoutWriter o2 = new SysoutWriter();
        assertThatThrownBy(() -> new TransactionReport(lookups, o2).run(List.of(tran("1", "07", 1, "1.00", CARD_A, TestData.PROC_TS)), DATEPARM))
                .isInstanceOf(CobolAbend.class);
        assertThat(o2.lines()).contains("INVALID TRANSACTION TYPE : 07");
        SysoutWriter o3 = new SysoutWriter();
        assertThatThrownBy(() -> new TransactionReport(lookups, o3).run(List.of(tran("1", "01", 9, "1.00", CARD_A, TestData.PROC_TS)), DATEPARM))
                .isInstanceOf(CobolAbend.class);
        assertThat(o3.lines()).contains("INVALID TRAN CATG KEY : 010009");
    }

    @Test
    void BR_POST_039_accountTotalsBreakOnCardNumber() {
        List<String> l = trimmed(report(List.of(tran("1", "10.00", CARD_A), tran("2", "5.00", CARD_A), tran("3", "1.00", CARD_B))));
        assertThat(l.get(6)).isEqualTo("Account Total" + ".".repeat(84) + "+         15.00");
        assertThat(l.get(7)).isEqualTo("-".repeat(133));
        assertThat(l.get(8)).startsWith("3                00000001002");
    }

    @Test
    void BR_POST_040_pageBreakWhenTheLineCounterIsAMultipleOf20() {
        List<String> trans = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            trans.add(tran(String.format("%016d", i), "1.00", CARD_A));
        }
        List<String> l = trimmed(report(trans));
        // 4 header lines + 16 details = 20 -> before detail 17: page total, dashes, 4 headers
        assertThat(l.get(20)).isEqualTo("Page Total" + " " + ".".repeat(86) + "+         16.00");
        assertThat(l.get(21)).isEqualTo("-".repeat(133));
        assertThat(l.get(22)).startsWith("DALYREPT");
        assertThat(l.get(26)).startsWith("0000000000000017");
    }

    @Test
    void BR_POST_041_pageTotalsRollIntoTheGrandTotal() {
        List<String> trans = new ArrayList<>();
        for (int i = 1; i <= 17; i++) {
            trans.add(tran(String.format("%016d", i), "1.00", CARD_A));
        }
        List<String> l = trimmed(report(trans));
        // page 1 = 16.00, page 2 = 1.00 + last amount again (1.00) = 2.00; grand = 18.00
        assertThat(l.get(l.size() - 1)).isEqualTo("Grand Total" + ".".repeat(86) + "+         18.00");
    }

    @Test
    void BR_POST_042_endOfFileAddsTheLastAmountAgain() {
        List<String> l = trimmed(report(List.of(tran("1", "125.00", CARD_A), tran("2", "-15.75", CARD_A))));
        assertThat(sysout.lines()).contains("TRAN-AMT 00000001575-", "WS-PAGE-TOTAL00000010925+");
        assertThat(l).contains("Page Total " + ".".repeat(86) + "+         93.50");
        assertThat(l.get(l.size() - 1)).isEqualTo("Grand Total" + ".".repeat(86) + "+         93.50");
    }

    @Test
    void BR_POST_043_lastAccountTotalIsNeverPrinted() {
        List<String> l = report(List.of(tran("1", "1.00", CARD_A), tran("2", "2.00", CARD_B)));
        assertThat(l.stream().filter(x -> x.startsWith("Account Total")).count()).isEqualTo(1);
        assertThat(Cobol.rstrip(l.get(l.size() - 3))).doesNotStartWith("Account Total");
    }

    @Test
    void BR_POST_044_startAndEndMessagesAndNoReturnCode() {
        report(List.of());
        assertThat(sysout.lines()).containsExactly("START OF EXECUTION OF PROGRAM CBTRN03C",
                "Reporting from 2022-01-01 to 2022-07-06", "END OF EXECUTION OF PROGRAM CBTRN03C");
    }
}
