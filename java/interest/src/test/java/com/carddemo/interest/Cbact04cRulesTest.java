package com.carddemo.interest;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.common.TestRepo;
import com.carddemo.common.cobol.CobolNumeric;
import com.carddemo.common.codec.Copybook;
import com.carddemo.common.codec.CopybookField;
import com.carddemo.common.codec.RecordCodec;
import com.carddemo.interest.IntCalcFiles.Dd;
import com.carddemo.interest.IntCalcHarness.Result;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * One or more tests per business rule BR-INT-001 .. BR-INT-048 (docs/analysis/business-rules/interest.md).
 * Pure unit tests: {@link Cbact04c} runs against {@link FakeFiles}, no database, no Spring context.
 */
class Cbact04cRulesTest {

    private static final String START = "START OF EXECUTION OF PROGRAM CBACT04C";
    private static final String END = "END OF EXECUTION OF PROGRAM CBACT04C";

    private static BigDecimal d(String s) {
        return new BigDecimal(s);
    }

    /** Accounts 2001 (GOLD, 1000.00 at 12 %) and 2002 (STANDARD, 2400.00 at 18.50 %), as i01. */
    private static FakeFiles twoAccounts() {
        return IntCalcHarness.withStandardRates()
                .account(2001, "GOLD", "1000.00", "1000.00", "0.00").xref("4000000000002001", 2001)
                .account(2002, "STANDARD", "2400.00", "2400.00", "0.00").xref("4000000000002002", 2002)
                .balance(2001, "01", 1, "1000.00")
                .balance(2002, "01", 1, "2400.00");
    }

    private static void assertAbended(Result r) {
        assertThat(r.rc()).isEqualTo(12);
        assertThat(r.sysout()).endsWith("ABENDING PROGRAM", "CEE3ABD: USER ABEND U+0000000999");
        assertThat(r.sysout()).doesNotContain(END);
    }

    // ------------------------------------------------------------------ sysout / linkage / files

    @Test
    void BR_INT_001_startAndEndBannersFrameTheRun() {
        Result r = IntCalcHarness.run(twoAccounts());
        assertThat(r.sysout().get(0)).isEqualTo(START);
        assertThat(r.lastLine()).isEqualTo(END);
    }

    @Test
    void BR_INT_002_parmDateIsTheTranIdPrefixNotTheTimestamp() throws IOException {
        String jcl = Files.readString(TestRepo.root().resolve("app/jcl/INTCALC.jcl"));
        assertThat(jcl).contains("EXEC PGM=CBACT04C,PARM='" + Cbact04c.JCL_PARM + "'");
        Result r = IntCalcHarness.run(twoAccounts());
        InterestTran t = r.trans().get(0);
        assertThat(t.tranId()).startsWith("2022071800");
        assertThat(t.origTs()).isEqualTo("2022-07-05-10.00.00.000000");
    }

    @Test
    void BR_INT_003_fiveFilesAreOpenedInOrderAndClosedAtTheEnd() {
        Result r = IntCalcHarness.run(twoAccounts());
        assertThat(r.files().log.subList(0, 5)).containsExactly(
                "open TCATBALF", "open XREFFILE", "open DISCGRP", "open ACCTFILE", "open TRANSACT");
        assertThat(r.files().log.subList(r.files().log.size() - 5, r.files().log.size())).containsExactly(
                "close TCATBALF", "close XREFFILE", "close DISCGRP", "close ACCTFILE", "close TRANSACT");
        assertThat(r.rc()).isZero();
    }

    @ParameterizedTest
    @CsvSource({
        "TCATBALF, ERROR OPENING TRANSACTION CATEGORY BALANCE",
        "XREFFILE, ERROR OPENING CROSS REF FILE35",
        "DISCGRP,  ERROR OPENING DALY REJECTS FILE",
        "ACCTFILE, ERROR OPENING ACCOUNT MASTER FILE",
        "TRANSACT, ERROR OPENING TRANSACTION FILE",
    })
    void BR_INT_004_openFailureShowsFileSpecificMessageAndAbends(Dd dd, String message) {
        Result r = IntCalcHarness.run(twoAccounts().force("open:" + dd, "35"));
        assertThat(r.sysout()).containsSubsequence(START, message, "FILE STATUS IS: NNNN0035", "ABENDING PROGRAM");
        assertAbended(r);
        assertThat(r.trans()).isEmpty();
    }

    @Test
    void BR_INT_005_balancesAreProcessedInAscendingKeyOrder() {
        FakeFiles f = IntCalcHarness.withStandardRates()
                .account(2007, "GOLD", "1500.00", "0.00", "0.00").xref("4000000000002007", 2007)
                .balance(2007, "01", 2, "500.00")      // inserted out of order on purpose
                .balance(2007, "01", 1, "1000.00");
        Result r = IntCalcHarness.run(f);
        assertThat(r.trans()).extracting(InterestTran::tranId).containsExactly("2022071800000001", "2022071800000002");
        assertThat(r.trans()).extracting(InterestTran::amt).containsExactly(d("10.00"), d("7.50"));
    }

    @Test
    void BR_INT_006_endOfFileStatus10StopsTheLoop() {
        Result r = IntCalcHarness.run(twoAccounts());
        assertThat(r.files().log.stream().filter("read TCATBALF"::equals)).hasSize(3); // 2 records + status 10
        assertThat(r.program().recordCount()).isEqualTo(2);
        assertThat(r.lastLine()).isEqualTo(END);
    }

    @Test
    void BR_INT_007_balanceReadErrorAbends() {
        Result r = IntCalcHarness.run(twoAccounts().force("read:2", "30"));
        assertThat(r.sysout()).containsSubsequence("ERROR READING TRANSACTION CATEGORY FILE", "FILE STATUS IS: NNNN0030");
        assertAbended(r);
    }

    @Test
    void BR_INT_008_eachBalanceRecordIsEchoedAsItsRaw50ByteImage() {
        Result r = IntCalcHarness.run(twoAccounts());
        assertThat(r.sysout().get(1)).isEqualTo("000000020010100010000010000{" + " ".repeat(22)).hasSize(50);
        assertThat(r.sysout().get(2)).startsWith("000000020020100010000024000{");
    }

    @Test
    void BR_INT_009_accountBreakRewritesPreviousAccountThenReadsTheNewOne() {
        Result r = IntCalcHarness.run(twoAccounts());
        assertThat(r.files().log).containsSubsequence("rewrite ACCTFILE 2001", "read ACCTFILE 2002", "read XREFFILE 2002");
    }

    @Test
    void BR_INT_010_firstRecordDoesNotTriggerAnUpdate() {
        Result r = IntCalcHarness.run(twoAccounts());
        List<String> log = r.files().log;
        assertThat(log.indexOf("read ACCTFILE 2001")).isLessThan(log.indexOf("rewrite ACCTFILE 2001"));
        assertThat(log.subList(0, log.indexOf("read ACCTFILE 2001"))).noneMatch(s -> s.startsWith("rewrite"));
    }

    @Test
    void BR_INT_011_interestAccumulatorIsResetAtEveryAccountBreak() {
        FakeFiles f = twoAccounts().account(2003, "GOLD", "0.00", "0.00", "0.00").xref("4000000000002003", 2003)
                .balance(2003, "01", 1, "100.00");
        Result r = IntCalcHarness.run(f);
        assertThat(f.balanceOf(2001)).isEqualByComparingTo("1010.00");
        assertThat(f.balanceOf(2002)).isEqualByComparingTo("2437.00"); // 37.00 only, not 47.00
    }

    @Test
    void BR_INT_012_accountIsReadByTheBalanceAccountIdAndItsGroupDrivesTheRate() {
        FakeFiles f = IntCalcHarness.withStandardRates()
                .account(2006, "PLATINUM", "1000.00", "0.00", "0.00").xref("4000000000002006", 2006)
                .balance(2006, "01", 1, "1000.00");
        Result r = IntCalcHarness.run(f);
        assertThat(r.files().log).containsSubsequence("read ACCTFILE 2006", "read DISCGRP PLATINUM|01|1");
    }

    @Test
    void BR_INT_013_missingAccountAbends() {
        FakeFiles f = twoAccounts().balance(2099, "01", 1, "10.00");
        Result r = IntCalcHarness.run(f);
        assertThat(r.sysout()).containsSubsequence("ACCOUNT NOT FOUND: 00000002099", "ERROR READING ACCOUNT FILE",
                "FILE STATUS IS: NNNN0023");
        assertAbended(r);
    }

    @Test
    void BR_INT_014_cardIsLookedUpByAccountIdOnTheAlternateKey() {
        Result r = IntCalcHarness.run(twoAccounts());
        assertThat(r.files().log).contains("read XREFFILE 2001");
        assertThat(r.trans().get(0).cardNum()).isEqualTo("4000000000002001");
    }

    @Test
    void BR_INT_015_missingCardCrossReferenceAbends() {
        FakeFiles f = twoAccounts().account(2011, "GOLD", "0.00", "0.00", "0.00").balance(2011, "01", 1, "10.00");
        Result r = IntCalcHarness.run(f);
        assertThat(r.sysout()).containsSubsequence("ACCOUNT NOT FOUND: 00000002011", "ERROR READING XREF FILE",
                "FILE STATUS IS: NNNN0023");
        assertAbended(r);
    }

    @Test
    void BR_INT_016_accountsWithoutBalanceRowsAreUntouched() {
        FakeFiles f = twoAccounts().account(2050, "GOLD", "500.00", "100.00", "40.00");
        IntCalcHarness.run(f);
        assertThat(f.balanceOf(2050)).isEqualByComparingTo("500.00");
        assertThat(f.cycCreditOf(2050)).isEqualByComparingTo("100.00");
        assertThat(f.cycDebitOf(2050)).isEqualByComparingTo("40.00");
        assertThat(f.log).noneMatch(s -> s.endsWith(" 2050"));
    }

    // ------------------------------------------------------------------ rate lookup

    @Test
    void BR_INT_017_rateKeyIsGroupTypeAndCategory() {
        Result r = IntCalcHarness.run(twoAccounts());
        assertThat(r.files().log).contains("read DISCGRP STANDARD|01|1");
        assertThat(r.trans().get(1).amt()).isEqualByComparingTo("37.00");
    }

    @Test
    void BR_INT_018_missingGroupFallsBackToDefaultKeepingTypeAndCategory() {
        FakeFiles f = IntCalcHarness.withStandardRates()
                .account(2006, "PLATINUM", "1000.00", "0.00", "0.00").xref("4000000000002006", 2006)
                .balance(2006, "01", 1, "1000.00");
        Result r = IntCalcHarness.run(f);
        assertThat(r.sysout()).containsSubsequence("DISCLOSURE GROUP RECORD MISSING", "TRY WITH DEFAULT GROUP CODE");
        assertThat(f.log).containsSubsequence("read DISCGRP PLATINUM|01|1", "read DISCGRP DEFAULT|01|1");
        assertThat(r.trans().get(0).amt()).isEqualByComparingTo("17.50");
        assertThat(r.program().categories().get(0).groupUsed()).isEqualTo("DEFAULT");
    }

    @Test
    void BR_INT_019_accountInDefaultGroupUsesItWithoutFallbackMessages() {
        FakeFiles f = IntCalcHarness.withStandardRates()
                .account(2010, "DEFAULT", "777.77", "0.00", "0.00").xref("4000000000002010", 2010)
                .balance(2010, "01", 1, "777.77");
        Result r = IntCalcHarness.run(f);
        assertThat(r.sysout()).doesNotContain("DISCLOSURE GROUP RECORD MISSING");
        assertThat(r.trans().get(0).amt()).isEqualByComparingTo("13.61");
    }

    @Test
    void BR_INT_020_disclosureGroupReadErrorAbends() {
        Result r = IntCalcHarness.run(twoAccounts().force("rate", "30"));
        assertThat(r.sysout()).containsSubsequence("ERROR READING DISCLOSURE GROUP FILE", "FILE STATUS IS: NNNN0030");
        assertThat(r.sysout()).doesNotContain("DISCLOSURE GROUP RECORD MISSING");
        assertAbended(r);
    }

    @Test
    void BR_INT_021_missingDefaultRowAbendsWithoutFurtherFallback() {
        FakeFiles f = IntCalcHarness.withStandardRates()
                .account(2006, "PLATINUM", "1000.00", "0.00", "0.00").xref("4000000000002006", 2006)
                .balance(2006, "04", 1, "1000.00");
        Result r = IntCalcHarness.run(f);
        assertThat(r.sysout()).containsSubsequence("DISCLOSURE GROUP RECORD MISSING", "TRY WITH DEFAULT GROUP CODE",
                "ERROR READING DEFAULT DISCLOSURE GROUP", "FILE STATUS IS: NNNN0023");
        assertAbended(r);
    }

    @Test
    void BR_INT_022_zeroRateCategoriesAreSkipped() {
        FakeFiles f = IntCalcHarness.withStandardRates()
                .account(2005, "GOLD", "700.00", "0.00", "0.00").xref("4000000000002005", 2005)
                .balance(2005, "01", 1, "1000.00").balance(2005, "02", 1, "-300.00");
        Result r = IntCalcHarness.run(f);
        assertThat(r.trans()).hasSize(1);
        assertThat(r.trans().get(0).tranId()).isEqualTo("2022071800000001");
        assertThat(r.trans().get(0).amt()).isEqualByComparingTo("10.00");
        assertThat(r.program().categories().get(1).skipped()).isTrue();
    }

    // ------------------------------------------------------------------ calculation / rounding

    @Test
    void BR_INT_023_monthlyInterestIsBalanceTimesRateOver1200() {
        assertThat(Cbact04c.monthlyInterest(d("1000.00"), d("12.00"))).isEqualByComparingTo("10.00");
        assertThat(Cbact04c.monthlyInterest(d("2400.00"), d("18.50"))).isEqualByComparingTo("37.00");
    }

    @Test
    void BR_INT_024_interestIsTruncatedNotRounded() {
        assertThat(Cbact04c.monthlyInterest(d("1235.50"), d("12.00"))).isEqualTo(d("12.35")); // exact 12.355
        FakeFiles f = IntCalcHarness.withStandardRates()
                .account(2003, "GOLD", "1235.50", "1235.50", "0.00").xref("4000000000002003", 2003)
                .account(2004, "GOLD", "1234.50", "1234.50", "0.00").xref("4000000000002004", 2004)
                .balance(2003, "01", 1, "1235.50").balance(2004, "01", 1, "1234.50");
        Result r = IntCalcHarness.run(f);
        assertThat(r.trans().get(0).amt()).isEqualTo(d("12.35"));
        assertThat(f.balanceOf(2003)).isEqualByComparingTo("1247.85");
    }

    @Test
    void BR_INT_025_nonTieSubCentRemaindersAreDroppedAboveAndBelowHalf() {
        assertThat(Cbact04c.monthlyInterest(d("1000.33"), d("18.50"))).isEqualTo(d("15.42"));  // 15.42175
        assertThat(Cbact04c.monthlyInterest(d("777.77"), d("21.00"))).isEqualTo(d("13.61"));   // 13.610975
        assertThat(Cbact04c.monthlyInterest(d("1235.70"), d("12.00"))).isEqualTo(d("12.35"));  // 12.357
    }

    @Test
    void BR_INT_026_negativeBalancesProduceNegativeInterestTruncatedTowardZero() {
        assertThat(Cbact04c.monthlyInterest(d("-300.00"), d("12.00"))).isEqualTo(d("-3.00"));
        assertThat(Cbact04c.monthlyInterest(d("-1235.50"), d("12.00"))).isEqualTo(d("-12.35"));
        FakeFiles f = IntCalcHarness.withStandardRates()
                .account(2008, "GOLD", "-300.00", "0.00", "0.00").xref("4000000000002008", 2008)
                .balance(2008, "01", 1, "-300.00");
        assertThat(IntCalcHarness.run(f).trans().get(0).amt()).isEqualByComparingTo("-3.00");
    }

    @Test
    void BR_INT_027_interestIsAccumulatedPerAccountAndAppliedAtTheBreak() {
        FakeFiles f = IntCalcHarness.withStandardRates()
                .account(2007, "GOLD", "1500.00", "0.00", "0.00").xref("4000000000002007", 2007)
                .account(2008, "GOLD", "0.00", "0.00", "0.00").xref("4000000000002008", 2008)
                .balance(2007, "01", 1, "1000.00").balance(2007, "01", 2, "500.00")
                .balance(2008, "01", 1, "0.00");
        IntCalcHarness.run(f);
        assertThat(f.balanceOf(2007)).isEqualByComparingTo("1517.50");
    }

    @Test
    void BR_INT_028_zeroBalanceWithNonZeroRateStillPostsAZeroTransaction() {
        FakeFiles f = IntCalcHarness.withStandardRates()
                .account(2001, "GOLD", "0.00", "0.00", "0.00").xref("4000000000002001", 2001)
                .balance(2001, "01", 1, "0.00");
        Result r = IntCalcHarness.run(f);
        assertThat(r.trans()).hasSize(1);
        assertThat(r.trans().get(0).amt()).isEqualByComparingTo("0.00");
        assertThat(r.trans().get(0).tranId()).isEqualTo("2022071800000001");
    }

    @Test
    void BR_INT_029_overflowSilentlyDropsHighOrderDigits() {
        // 999,999,999.99 x 9,999.99 / 1200 = 8,333,324,999.9166...: only 9 integer digits survive
        assertThat(Cbact04c.monthlyInterest(d("999999999.99"), d("9999.99"))).isEqualTo(d("333324999.91"));
        FakeFiles f = IntCalcHarness.withStandardRates().rate("GOLD", "01", 3, "9999.99")
                .account(2001, "GOLD", "9999999999.99", "0.00", "0.00").xref("4000000000002001", 2001)
                .account(2002, "GOLD", "0.00", "0.00", "0.00").xref("4000000000002002", 2002)
                .balance(2001, "01", 1, "0.02").balance(2001, "01", 3, "999999999.99")
                .balance(2002, "01", 1, "0.00");
        Result r = IntCalcHarness.run(f);
        assertThat(r.rc()).isZero();
        // ACCT-CURR-BAL S9(10)V99: 9,999,999,999.99 + 333,324,999.91 loses its 11th digit
        assertThat(f.balanceOf(2001)).isEqualTo(CobolNumeric.fit(d("9999999999.99").add(d("333324999.91")), 10, 2));
        assertThat(f.balanceOf(2001)).isEqualByComparingTo("333324999.90");
    }

    @Test
    void BR_INT_030_feesStepIsAStubThatChangesNothing() {
        Result r = IntCalcHarness.run(twoAccounts());
        assertThat(r.trans()).hasSize(2); // interest only, no fee transactions
        assertThat(r.trans()).allMatch(t -> t.desc().startsWith("Int. for a/c "));
    }

    // ------------------------------------------------------------------ SYSTRAN record

    @Test
    void BR_INT_031_oneTransactionPerNonZeroRateBalance() {
        FakeFiles f = IntCalcHarness.withStandardRates()
                .account(2007, "GOLD", "1500.00", "0.00", "0.00").xref("4000000000002007", 2007)
                .balance(2007, "01", 1, "1000.00").balance(2007, "01", 2, "500.00");
        Result r = IntCalcHarness.run(f);
        assertThat(r.trans()).extracting(InterestTran::amt).containsExactly(d("10.00"), d("7.50"));
    }

    @Test
    void BR_INT_032_tranIdIsParmPlusRunWideSixDigitSequence() {
        Result r = IntCalcHarness.run(twoAccounts());
        assertThat(r.trans()).extracting(InterestTran::tranId).containsExactly("2022071800000001", "2022071800000002");
        assertThat(Cbact04c.transactionId("2022071800", 1_000_000)).isEqualTo("2022071800000000"); // PIC 9(06) wraps
    }

    @Test
    void BR_INT_033_interestTransactionsAreType01Category0005SourceSystem() {
        FakeFiles f = IntCalcHarness.withStandardRates()
                .account(2007, "GOLD", "500.00", "0.00", "0.00").xref("4000000000002007", 2007)
                .balance(2007, "01", 2, "500.00");
        InterestTran t = IntCalcHarness.run(f).trans().get(0);
        assertThat(t.typeCd()).isEqualTo("01");
        assertThat(t.catCd()).isEqualTo(5);
        assertThat(t.source()).isEqualTo("System");
    }

    @Test
    void BR_INT_034_descriptionIsIntForAcPlusElevenDigitAccountId() {
        assertThat(IntCalcHarness.run(twoAccounts()).trans().get(0).desc()).isEqualTo("Int. for a/c 00000002001");
    }

    @Test
    void BR_INT_035_amountMerchantAndCardFields() {
        FakeFiles f = IntCalcHarness.withStandardRates()
                .account(2006, "PLATINUM", "1000.00", "0.00", "0.00").xref("4000000000002006", 2006)
                .balance(2006, "01", 1, "1000.00");
        InterestTran t = IntCalcHarness.run(f).trans().get(0);
        assertThat(t.amt()).isEqualTo(d("17.50"));
        assertThat(t.merchantId()).isZero();
        assertThat(t.merchantName()).isBlank();
        assertThat(t.merchantCity()).isBlank();
        assertThat(t.merchantZip()).isBlank();
        assertThat(t.cardNum()).isEqualTo("4000000000002006");
    }

    @Test
    void BR_INT_036_bothTimestampsComeFromTheFrozenClockInDb2Format() {
        InterestTran t = IntCalcHarness.run(twoAccounts()).trans().get(0);
        assertThat(t.origTs()).isEqualTo("2022-07-05-10.00.00.000000");
        assertThat(t.procTs()).isEqualTo(t.origTs());
    }

    @Test
    void BR_INT_037_transactionWriteErrorAbends() {
        Result r = IntCalcHarness.run(twoAccounts().force("write", "34"));
        assertThat(r.sysout()).containsSubsequence("ERROR WRITING TRANSACTION RECORD", "FILE STATUS IS: NNNN0034");
        assertAbended(r);
    }

    @Test
    void BR_INT_038_amountsAreZonedWithEbcdicOverpunchSign() {
        Copybook cb = TestRepo.copybooks().get("CVTRA05Y");
        CopybookField amt = cb.field("TRAN-AMT");
        for (String[] c : new String[][] {{"12.35", "0000000123E"}, {"12.34", "0000000123D"}, {"-3.00", "0000000030}"}}) {
            InterestTran t = new InterestTran("x", "01", 5, "System", "", d(c[0]), 0, "", "", "", "", "", "");
            String line = RecordCodec.encodeLine(cb, t.toFields());
            assertThat(line.substring(amt.offset(), amt.offset() + amt.length())).isEqualTo(c[1]);
        }
    }

    // ------------------------------------------------------------------ account update

    @Test
    void BR_INT_039_accountBreakAddsInterestAndResetsCycleAmounts() {
        FakeFiles f = twoAccounts();
        IntCalcHarness.run(f);
        assertThat(f.balanceOf(2001)).isEqualByComparingTo("1010.00");
        assertThat(f.cycCreditOf(2001)).isEqualByComparingTo("0.00");
        assertThat(f.cycDebitOf(2001)).isEqualByComparingTo("0.00");
    }

    @Test
    void BR_INT_040_accountWithOnlyZeroRateCategoriesIsStillReset() {
        FakeFiles f = IntCalcHarness.withStandardRates()
                .account(2005, "GOLD", "-300.00", "0.00", "300.00").xref("4000000000002005", 2005)
                .account(2006, "GOLD", "0.00", "0.00", "0.00").xref("4000000000002006", 2006)
                .balance(2005, "02", 1, "-300.00").balance(2006, "01", 1, "0.00");
        Result r = IntCalcHarness.run(f);
        assertThat(f.log).contains("rewrite ACCTFILE 2005");
        assertThat(f.balanceOf(2005)).isEqualByComparingTo("-300.00");
        assertThat(f.cycDebitOf(2005)).isEqualByComparingTo("0.00");
        assertThat(r.trans()).noneMatch(t -> t.desc().endsWith("2005"));
    }

    @Test
    void BR_INT_041_accountRewriteErrorAbends() {
        Result r = IntCalcHarness.run(twoAccounts().force("rewrite", "22"));
        assertThat(r.sysout()).containsSubsequence("ERROR RE-WRITING ACCOUNT FILE", "FILE STATUS IS: NNNN0022");
        assertAbended(r);
    }

    @Test
    void BR_INT_042_lastAccountOnTheBalanceFileIsNeverUpdated() {
        FakeFiles f = twoAccounts();
        Result r = IntCalcHarness.run(f);
        assertThat(r.trans()).hasSize(2);                       // 2002's interest is written ...
        assertThat(f.balanceOf(2002)).isEqualByComparingTo("2400.00"); // ... but never added
        assertThat(f.cycCreditOf(2002)).isEqualByComparingTo("2400.00");
        assertThat(r.program().rewrittenAccounts()).containsExactly(2001L);
        assertThat(r.rc()).isZero();
    }

    @Test
    void BR_INT_043_emptyBalanceFileWritesOnlyTheBannersAndEndsRc0() {
        FakeFiles f = IntCalcHarness.withStandardRates().account(2001, "GOLD", "1.00", "2.00", "3.00");
        Result r = IntCalcHarness.run(f);
        assertThat(r.sysout()).containsExactly(START, END);
        assertThat(r.trans()).isEmpty();
        assertThat(f.log).noneMatch(s -> s.startsWith("rewrite") || s.startsWith("read ACCTFILE"));
        assertThat(r.rc()).isZero();
    }

    @ParameterizedTest
    @CsvSource({
        "TCATBALF, ERROR CLOSING TRANSACTION BALANCE FILE",
        "XREFFILE, ERROR CLOSING CROSS REF FILE",
        "DISCGRP,  ERROR CLOSING DISCLOSURE GROUP FILE",
        "ACCTFILE, ERROR CLOSING ACCOUNT FILE",
        "TRANSACT, ERROR CLOSING TRANSACTION FILE",
    })
    void BR_INT_044_closeFailureShowsFileSpecificMessageAndAbends(Dd dd, String message) {
        Result r = IntCalcHarness.run(twoAccounts().force("close:" + dd, "42"));
        assertThat(r.sysout()).containsSubsequence(message, "FILE STATUS IS: NNNN0042", "ABENDING PROGRAM");
        assertAbended(r);
    }

    @Test
    void BR_INT_045_fileStatusDisplayFormat() {
        assertThat(Cbact04c.formatIoStatus("23")).isEqualTo("FILE STATUS IS: NNNN0023");
        assertThat(Cbact04c.formatIoStatus("35")).isEqualTo("FILE STATUS IS: NNNN0035");
        // status 9x / non-numeric: first byte, then the binary value of the second byte as 3 digits
        assertThat(Cbact04c.formatIoStatus("9" + (char) 7)).isEqualTo("FILE STATUS IS: NNNN9007");
        assertThat(Cbact04c.formatIoStatus("90")).isEqualTo("FILE STATUS IS: NNNN9048");
    }

    // ------------------------------------------------------------------ return codes

    @Test
    void BR_INT_046_normalCompletionReturnsRc0AndRcJsonListsTheMergedSetupSteps() {
        assertThat(IntCalcHarness.run(twoAccounts()).rc()).isZero();
        String rc = TestRepo.expectedText("interest", "i01-baseline", "rc.json");
        for (String step : InterestChainHandler.RC_STEPS) {
            assertThat(rc).contains("\"" + step + "\": 0");
        }
        assertThat(rc).contains("\"maxrc\": 0");
    }

    @Test
    void BR_INT_047_abendRoutineDisplaysAndEndsWithRc12() {
        Result r = IntCalcHarness.run(twoAccounts().force("open:ACCTFILE", "93"));
        assertThat(r.sysout()).containsSubsequence("ERROR OPENING ACCOUNT MASTER FILE", "FILE STATUS IS: NNNN9051");
        assertAbended(r);
        assertThat(r.program().returnCode()).isEqualTo(Cbact04c.ABEND_RC);
    }

    @Test
    void BR_INT_048_rewritesBeforeAnAbendAreNotRolledBack() {
        FakeFiles f = twoAccounts().balance(2099, "01", 1, "10.00");
        Result r = IntCalcHarness.run(f);
        assertAbended(r);
        assertThat(f.balanceOf(2001)).isEqualByComparingTo("1010.00");
        assertThat(f.balanceOf(2002)).isEqualByComparingTo("2437.00");
        assertThat(r.program().rewrittenAccounts()).containsExactly(2001L, 2002L);
    }

    @Test
    void BR_INT_024_categoryBreakdownKeepsTheExactQuotientForTheConsole() {
        FakeFiles f = IntCalcHarness.withStandardRates()
                .account(2003, "GOLD", "1235.50", "0.00", "0.00").xref("4000000000002003", 2003)
                .balance(2003, "01", 1, "1235.50");
        Cbact04c.CategoryResult c = IntCalcHarness.run(f).program().categories().get(0);
        assertThat(c.exactInterest()).isEqualByComparingTo("12.355");
        assertThat(c.monthlyInt()).isEqualTo(d("12.35"));
        assertThat(Map.of("rate", c.rate())).containsEntry("rate", d("12.00"));
    }
}
