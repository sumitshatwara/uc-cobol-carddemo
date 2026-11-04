package com.carddemo.posting;

import com.carddemo.common.codec.RecordCodec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PostingBusinessRulesTest {
    private static final String CLOCK = "2022070510000000+0000";
    private static final String CARD_1001 = "4000000000001001";
    private static final String CARD_1002 = "4000000000001002";
    private static final String DATE = "2022-07-04";

    @Test
    @DisplayName("BR-POST-001 CBTRN02C SYSOUT sequence and counters")
    void brPost001_sysoutSequenceAndCounters() {
        InMemoryPostingStore store = InMemoryPostingStore.fromFixture("p01-baseline-mixed");
        DailyPostingService.Result result = post(store, InMemoryPostingStore.dailyInput("p01-baseline-mixed"));
        assertThat(result.sysout()).containsExactly(
                "START OF EXECUTION OF PROGRAM CBTRN02C",
                "TRANSACTIONS PROCESSED :000000004",
                "TRANSACTIONS REJECTED  :000000000",
                "END OF EXECUTION OF PROGRAM CBTRN02C");
    }

    @Test
    @DisplayName("BR-POST-002 Sequential read loop over DALYTRAN")
    void brPost002_readsInInputOrder() {
        InMemoryPostingStore store = InMemoryPostingStore.defaults();
        post(store, List.of(daily("0000000000000002", CARD_1001, "01", 1, "2.00", DATE),
                daily("0000000000000001", CARD_1001, "01", 1, "3.00", DATE)));
        assertThat(store.transactionWriteOrder())
                .containsExactly("0000000000000002", "0000000000000001");
    }

    @Test
    @DisplayName("BR-POST-003 Validation order: card cross-reference first, then account")
    void brPost003_checksXrefBeforeAccount() {
        InMemoryPostingStore store = InMemoryPostingStore.defaults();
        DailyPostingService.Result result = post(store, List.of(
                daily("0000000000000001", "4000000000009999", "01", 1, "1.00", DATE)));
        assertThat(store.accountLookupCount()).isZero();
        assertThat(reason(result.rejects().getFirst())).isEqualTo(100);
    }

    @Test
    @DisplayName("BR-POST-004 Reject reason 100: card number not in CARDXREF")
    void brPost004_rejectsUnknownCard() {
        DailyPostingService.Result result = post(InMemoryPostingStore.defaults(), List.of(
                daily("0000000000000001", "4000000000009999", "01", 1, "1.00", DATE)));
        assertThat(reason(result.rejects().getFirst())).isEqualTo(100);
        assertThat(rejectDescription(result.rejects().getFirst())).startsWith("INVALID CARD NUMBER FOUND");
    }

    @Test
    @DisplayName("BR-POST-005 Reject reason 101: account record not found")
    void brPost005_rejectsMissingAccount() {
        InMemoryPostingStore store = InMemoryPostingStore.defaults();
        store.addXref("4000000000001999", bd("1999"));
        DailyPostingService.Result result = post(store, List.of(
                daily("0000000000000001", "4000000000001999", "01", 1, "1.00", DATE)));
        assertThat(reason(result.rejects().getFirst())).isEqualTo(101);
        assertThat(rejectDescription(result.rejects().getFirst())).startsWith("ACCOUNT RECORD NOT FOUND");
    }

    @Test
    @DisplayName("BR-POST-006 Credit-limit check uses cycle amounts, not the current balance")
    void brPost006_checksCycleAmountInsteadOfCurrentBalance() {
        InMemoryPostingStore store = InMemoryPostingStore.defaults();
        store.addAccount(new PostingStore.Account(bd("1001"), "Y", bd("900.00"), bd("200.00"),
                bd("0.00"), "2022-12-31", bd("0.00"), bd("0.00")));
        DailyPostingService.Result result = post(store, List.of(
                daily("0000000000000001", CARD_1001, "01", 1, "100.00", DATE)));
        assertThat(result.returnCode()).isZero();
        assertThat(result.rejects()).isEmpty();
    }

    @Test
    @DisplayName("BR-POST-007 Payments and credits make the limit check stricter")
    void brPost007_cycleDebitRaisesLimitBalance() {
        InMemoryPostingStore store = InMemoryPostingStore.defaults();
        store.addAccount(new PostingStore.Account(bd("1001"), "Y", bd("500.00"), bd("250.00"),
                bd("50.00"), "2022-12-31", bd("0.00"), bd("-200.00")));
        DailyPostingService.Result result = post(store, List.of(
                daily("0000000000000001", CARD_1001, "01", 1, "100.00", DATE)));
        assertThat(reason(result.rejects().getFirst())).isEqualTo(102);
    }

    @Test
    @DisplayName("BR-POST-008 WS-TEMP-BAL truncates the high-order digit")
    void brPost008_truncatesHighOrderDigit() {
        assertThat(CobolFormat.truncateHighOrder(bd("1000000000.25"), 9)).isEqualByComparingTo("0.25");
        assertThat(CobolFormat.truncateHighOrder(bd("-1000000001.25"), 9)).isEqualByComparingTo("-1.25");
    }

    @Test
    @DisplayName("BR-POST-009 Expiry check: expiration date >= origin date, compared as text")
    void brPost009_expiryBoundaryIsInclusive() {
        InMemoryPostingStore store = InMemoryPostingStore.defaults();
        store.addAccount(new PostingStore.Account(bd("1001"), "Y", bd("500.00"), bd("1000.00"),
                bd("50.00"), DATE, bd("0.00"), bd("0.00")));
        DailyPostingService.Result result = post(store, List.of(
                daily("0000000000000001", CARD_1001, "01", 1, "1.00", DATE)));
        assertThat(result.returnCode()).isZero();
        assertThat(result.rejects()).isEmpty();
    }

    @Test
    @DisplayName("BR-POST-010 Overlimit reason is overwritten by expiry reason")
    void brPost010_expiryReasonOverridesOverlimit() {
        InMemoryPostingStore store = InMemoryPostingStore.defaults();
        store.addAccount(new PostingStore.Account(bd("1001"), "Y", bd("500.00"), bd("1.00"),
                bd("0.00"), "2022-07-01", bd("0.00"), bd("0.00")));
        DailyPostingService.Result result = post(store, List.of(
                daily("0000000000000001", CARD_1001, "01", 1, "100.00", DATE)));
        assertThat(reason(result.rejects().getFirst())).isEqualTo(103);
        assertThat(rejectDescription(result.rejects().getFirst()))
                .startsWith("TRANSACTION RECEIVED AFTER ACCT EXPIRATION");
    }

    @Test
    @DisplayName("BR-POST-011 No active-status, cash-limit or type/category validation")
    void brPost011_ignoresUnimplementedValidations() {
        InMemoryPostingStore store = InMemoryPostingStore.defaults();
        store.addAccount(new PostingStore.Account(bd("1001"), "N", bd("500.00"), bd("1000.00"),
                bd("0.00"), "2022-12-31", bd("0.00"), bd("0.00")));
        DailyPostingService.Result result = post(store, List.of(
                daily("0000000000000001", CARD_1001, "99", 99, "1.00", DATE)));
        assertThat(result.returnCode()).isZero();
        assertThat(store.hasTransaction("0000000000000001")).isTrue();
    }

    @Test
    @DisplayName("BR-POST-012 430-byte reject record")
    void brPost012_writesExactRejectLength() {
        DailyPostingService.Result result = post(InMemoryPostingStore.defaults(), List.of(
                daily("0000000000000001", "4000000000009999", "01", 1, "1.00", DATE)));
        byte[] reject = result.rejects().getFirst();
        assertThat(reject).hasSize(430);
        assertThat(new String(reject, 350, 4, StandardCharsets.ISO_8859_1)).isEqualTo("0100");
        assertThat(rejectDescription(reject)).startsWith("INVALID CARD NUMBER FOUND");
    }

    @Test
    @DisplayName("BR-POST-013 Rejected transactions change no master file")
    void brPost013_rejectHasNoMasterChanges() {
        InMemoryPostingStore store = InMemoryPostingStore.defaults();
        store.addAccount(new PostingStore.Account(bd("1001"), "Y", bd("500.00"), bd("0.00"),
                bd("50.00"), "2022-12-31", bd("0.00"), bd("0.00")));
        DailyPostingService.Result result = post(store, List.of(
                daily("0000000000000001", CARD_1001, "01", 1, "100.00", DATE)));
        assertThat(result.rejects()).hasSize(1);
        assertThat(store.storedAccount(bd("1001")).orElseThrow().currentBalance()).isEqualByComparingTo("500.00");
        assertThat(store.storedCategoryBalance(bd("1001"), "01", bd("1")).orElseThrow())
                .isEqualByComparingTo("500.00");
        assertThat(store.transactionCount()).isZero();
    }

    @Test
    @DisplayName("BR-POST-014 Return code 4 when any record is rejected")
    void brPost014_returnsFourForReject() {
        DailyPostingService.Result result = post(InMemoryPostingStore.defaults(), List.of(
                daily("0000000000000001", "4000000000009999", "01", 1, "1.00", DATE)));
        assertThat(result.returnCode()).isEqualTo(4);
    }

    @Test
    @DisplayName("BR-POST-015 Posted transaction record mapping")
    void brPost015_mapsDailyTransactionFields() {
        InMemoryPostingStore store = InMemoryPostingStore.defaults();
        post(store, List.of(daily("0000000000000001", CARD_1001, "01", 1, "125.00", DATE)));
        Map<String, Object> transaction = store.transactions().getFirst();
        assertThat(transaction).containsEntry("TRAN-ID", "0000000000000001")
                .containsEntry("TRAN-TYPE-CD", "01").containsEntry("TRAN-SOURCE", "POS TERM")
                .containsEntry("TRAN-DESC", "Synthetic posting test")
                .containsEntry("TRAN-CARD-NUM", CARD_1001);
        assertThat((BigDecimal) transaction.get("TRAN-AMT")).isEqualByComparingTo("125.00");
    }

    @Test
    @DisplayName("BR-POST-016 PROC-TS from the frozen clock in DB2 timestamp format")
    void brPost016_formatsFrozenProcTimestamp() {
        InMemoryPostingStore store = InMemoryPostingStore.defaults();
        post(store, List.of(daily("0000000000000001", CARD_1001, "01", 1, "1.00", DATE)));
        assertThat(store.transactions().getFirst().get("TRAN-PROC-TS"))
                .isEqualTo("2022-07-05-10.00.00.000000");
    }

    @Test
    @DisplayName("BR-POST-017 Update order: TCATBAL, then account, then TRANSACT")
    void brPost017_updatesInLegacyOrder() {
        InMemoryPostingStore store = InMemoryPostingStore.defaults();
        post(store, List.of(daily("0000000000000001", CARD_1001, "01", 1, "1.00", DATE)));
        assertThat(store.writeOrder()).containsSubsequence("TCATBAL", "ACCOUNT", "TRANSACT");
    }

    @Test
    @DisplayName("BR-POST-018 TCATBAL update of an existing category balance")
    void brPost018_updatesExistingCategoryBalance() {
        InMemoryPostingStore store = InMemoryPostingStore.defaults();
        post(store, List.of(daily("0000000000000001", CARD_1001, "01", 1, "-200.00", DATE)));
        assertThat(store.storedCategoryBalance(bd("1001"), "01", bd("1")).orElseThrow())
                .isEqualByComparingTo("300.00");
    }

    @Test
    @DisplayName("BR-POST-019 TCATBAL creation for a new account/type/category")
    void brPost019_createsNewCategoryBalance() {
        InMemoryPostingStore store = InMemoryPostingStore.defaults();
        DailyPostingService.Result result = post(store, List.of(
                daily("0000000000000001", CARD_1001, "02", 1, "-300.00", DATE)));
        assertThat(store.storedCategoryBalance(bd("1001"), "02", bd("1")).orElseThrow())
                .isEqualByComparingTo("-300.00");
        assertThat(result.sysout()).contains("TCATBAL record not found for key : 00000001001020001.. Creating.");
    }

    @Test
    @DisplayName("BR-POST-020 Account balance and cycle-bucket update")
    void brPost020_updatesBalanceAndCycleBuckets() {
        InMemoryPostingStore store = InMemoryPostingStore.defaults();
        post(store, List.of(daily("0000000000000001", CARD_1001, "01", 1, "125.00", DATE),
                daily("0000000000000002", CARD_1001, "01", 1, "-200.00", DATE)));
        PostingStore.Account account = store.storedAccount(bd("1001")).orElseThrow();
        assertThat(account.currentBalance()).isEqualByComparingTo("425.00");
        assertThat(account.cycleCredit()).isEqualByComparingTo("125.00");
        assertThat(account.cycleDebit()).isEqualByComparingTo("-200.00");
    }

    @Test
    @DisplayName("BR-POST-021 Account REWRITE failure reason 109 is never acted on")
    void brPost021_ignoresAccountRewriteFailure() {
        InMemoryPostingStore store = InMemoryPostingStore.defaults();
        store.setIgnoreAccountRewrite(true);
        DailyPostingService.Result result = post(store, List.of(
                daily("0000000000000001", CARD_1001, "01", 1, "1.00", DATE)));
        assertThat(result.returnCode()).isZero();
        assertThat(result.rejects()).isEmpty();
        assertThat(store.hasTransaction("0000000000000001")).isTrue();
        assertThat(result.sysout()).contains("TRANSACTIONS REJECTED  :000000000");
    }

    @Test
    @DisplayName("BR-POST-022 Amounts are exact: no rounding anywhere in posting")
    void brPost022_keepsExactCentAmounts() {
        InMemoryPostingStore store = InMemoryPostingStore.defaults();
        store.addAccount(new PostingStore.Account(bd("1001"), "Y", bd("120.05"), bd("1000.00"),
                bd("0.00"), "2022-12-31", bd("0.00"), bd("0.00")));
        post(store, List.of(daily("0000000000000001", CARD_1001, "01", 1, "-15.75", DATE),
                daily("0000000000000002", CARD_1001, "01", 1, "80.25", DATE)));
        PostingStore.Account account = store.storedAccount(bd("1001")).orElseThrow();
        assertThat(account.currentBalance()).isEqualByComparingTo("184.55");
        assertThat(account.cycleDebit()).isEqualByComparingTo("-15.75");
        assertThat(account.cycleCredit()).isEqualByComparingTo("80.25");
    }

    @Test
    @DisplayName("BR-POST-023 TRANSACT is opened OUTPUT")
    void brPost023_truncatesOldTransactionsBeforePosting() {
        InMemoryPostingStore store = InMemoryPostingStore.defaults();
        store.addTransaction(InMemoryPostingStore.transaction("0000000000000009", CARD_1001, "01", 1,
                "10.00", "2022-07-04"));
        post(store, List.of(daily("0000000000000001", CARD_1001, "01", 1, "1.00", DATE)));
        assertThat(store.transactionCount()).isEqualTo(1);
        assertThat(store.hasTransaction("0000000000000009")).isFalse();
        assertThat(store.hasTransaction("0000000000000001")).isTrue();
    }

    @Test
    @DisplayName("BR-POST-024 TRANSACT write failure abends after partial updates")
    void brPost024_duplicateTransactionAbendsAfterUpdates() {
        InMemoryPostingStore store = InMemoryPostingStore.defaults();
        DailyPostingService.Result result = post(store, List.of(
                daily("0000000000000001", CARD_1001, "01", 1, "125.00", DATE),
                daily("0000000000000001", CARD_1001, "01", 1, "125.00", DATE)));
        assertThat(result.abended()).isTrue();
        assertThat(result.returnCode()).isEqualTo(12);
        assertThat(result.sysout()).containsSubsequence(
                "ERROR WRITING TO TRANSACTION FILE", "FILE STATUS IS: NNNN0022", "ABENDING PROGRAM",
                "CEE3ABD: USER ABEND U+0000000999");
        assertThat(result.sysout()).doesNotContain("END OF EXECUTION OF PROGRAM CBTRN02C");
        assertThat(store.storedCategoryBalance(bd("1001"), "01", bd("1")).orElseThrow())
                .isEqualByComparingTo("750.00");
        assertThat(store.storedAccount(bd("1001")).orElseThrow().currentBalance())
                .isEqualByComparingTo("750.00");
        assertThat(store.transactionCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("BR-POST-025 CBTRN02C I/O error messages")
    void brPost025_hasIoErrorMessageCatalogue() {
        assertThat(DailyPostingService.ioErrorMessages()).containsExactly(
                "ERROR OPENING DALYTRAN", "ERROR OPENING TRANSACTION FILE", "ERROR OPENING CROSS REF FILE",
                "ERROR OPENING DALY REJECTS FILE", "ERROR OPENING ACCOUNT MASTER FILE",
                "ERROR OPENING TRANSACTION BALANCE FILE", "ERROR READING DALYTRAN FILE",
                "ERROR WRITING TO REJECTS FILE", "ERROR CLOSING DALYTRAN FILE",
                "ERROR CLOSING TRANSACTION FILE", "ERROR CLOSING CROSS REF FILE",
                "ERROR CLOSING DAILY REJECTS FILE", "ERROR CLOSING ACCOUNT FILE",
                "ERROR CLOSING TRANSACTION BALANCE FILE");
        assertThat(DailyPostingService.ioError("ERROR OPENING ACCOUNT MASTER FILE", "35"))
                .containsExactly("ERROR OPENING ACCOUNT MASTER FILE", "FILE STATUS IS: NNNN0035",
                        "ABENDING PROGRAM", "CEE3ABD: USER ABEND U+0000000999");
    }

    @Test
    @DisplayName("BR-POST-026 DALYREJS close error reports the XREF file status")
    void brPost026_rejectCloseDisplaysXrefStatus() {
        assertThat(DailyPostingService.dalyrejsCloseError("30", "00"))
                .contains("ERROR CLOSING DAILY REJECTS FILE", "FILE STATUS IS: NNNN0000");
    }

    @Test
    @DisplayName("BR-POST-027 File-status display format")
    void brPost027_formatsFileStatusValues() {
        assertThat(CobolFormat.fileStatusLine("23")).isEqualTo("FILE STATUS IS: NNNN0023");
        assertThat(CobolFormat.fileStatusLine("9\u0004")).isEqualTo("FILE STATUS IS: NNNN9004");
    }

    @Test
    @DisplayName("BR-POST-028 Abend via CEE3ABD with code 999")
    void brPost028_emitsU999Abend() {
        assertThat(DailyPostingService.ioError("ERROR READING DALYTRAN FILE", "23"))
                .contains("ABENDING PROGRAM", "CEE3ABD: USER ABEND U+0000000999");
        assertThat(CobolFormat.abendLines("ERROR READING DALYTRAN FILE"))
                .containsExactly("ERROR READING DALYTRAN FILE", "ABENDING PROGRAM",
                        "CEE3ABD: USER ABEND U+0000000999");
    }

    @Test
    @DisplayName("BR-POST-029 Empty daily file posts nothing")
    void brPost029_emptyDailyFilePostsNothing() {
        InMemoryPostingStore store = InMemoryPostingStore.defaults();
        DailyPostingService.Result result = post(store, List.of());
        assertThat(result.returnCode()).isZero();
        assertThat(result.processed()).isZero();
        assertThat(result.rejected()).isZero();
        assertThat(result.rejects()).isEmpty();
        assertThat(store.transactionCount()).isZero();
        assertThat(result.sysout()).containsExactly("START OF EXECUTION OF PROGRAM CBTRN02C",
                "TRANSACTIONS PROCESSED :000000000", "TRANSACTIONS REJECTED  :000000000",
                "END OF EXECUTION OF PROGRAM CBTRN02C");
    }

    @Test
    @DisplayName("BR-POST-030 TRANREPT unloads TRANSACT with IDCAMS REPRO")
    void brPost030_reportsTransactionIdOrder() {
        InMemoryPostingStore store = InMemoryPostingStore.defaults();
        store.addTransaction(InMemoryPostingStore.transaction("0000000000000002", CARD_1001, "01", 1,
                "2.00", "2022-07-04"));
        store.addTransaction(InMemoryPostingStore.transaction("0000000000000001", CARD_1001, "01", 1,
                "1.00", "2022-07-04"));
        TransactionReportService.Result result = report(store, List.of(InMemoryPostingStore.dateParm(
                "2022-01-01", "2022-07-06")));
        assertThat(rawTransactionIds(result.sysout())).containsExactly("0000000000000001", "0000000000000002");
    }

    @Test
    @DisplayName("BR-POST-031 SORT window is hard-coded in JCL SYMNAMES")
    void brPost031_appliesLiteralSortWindow() {
        InMemoryPostingStore store = InMemoryPostingStore.defaults();
        store.addTransaction(InMemoryPostingStore.transaction("0000000000000001", CARD_1001, "01", 1,
                "1.00", "2021-12-31"));
        store.addTransaction(InMemoryPostingStore.transaction("0000000000000002", CARD_1001, "01", 1,
                "2.00", "2022-07-05"));
        store.addTransaction(InMemoryPostingStore.transaction("0000000000000003", CARD_1001, "01", 1,
                "3.00", "2022-07-07"));
        TransactionReportService.Result result = report(store, List.of(InMemoryPostingStore.dateParm(
                "2022-01-01", "2022-07-06")));
        assertThat(rawTransactionIds(result.sysout())).containsExactly("0000000000000002");
    }

    @Test
    @DisplayName("BR-POST-032 SORT by card number, stable")
    void brPost032_sortsByCardAndKeepsTiesStable() {
        InMemoryPostingStore store = InMemoryPostingStore.defaults();
        store.addXref(CARD_1002, bd("1002"));
        store.addTransaction(InMemoryPostingStore.transaction("0000000000000001", CARD_1002, "01", 1,
                "1.00", "2022-07-04"));
        store.addTransaction(InMemoryPostingStore.transaction("0000000000000002", CARD_1001, "01", 1,
                "2.00", "2022-07-04"));
        store.addTransaction(InMemoryPostingStore.transaction("0000000000000003", CARD_1002, "01", 1,
                "3.00", "2022-07-04"));
        TransactionReportService.Result result = report(store, List.of(InMemoryPostingStore.dateParm(
                "2022-01-01", "2022-07-06")));
        assertThat(rawTransactionIds(result.sysout())).containsExactly(
                "0000000000000002", "0000000000000001", "0000000000000003");
    }

    @Test
    @DisplayName("BR-POST-033 CBTRN03C SYSOUT banners and DATEPARM echo")
    void brPost033_reportsBannersAndDateRange() {
        InMemoryPostingStore store = reportWithOneTransaction("2022-07-04");
        TransactionReportService.Result result = report(store, List.of(InMemoryPostingStore.dateParm(
                "2022-01-01", "2022-07-06")));
        assertThat(result.sysout()).containsSubsequence("START OF EXECUTION OF PROGRAM CBTRN03C",
                "Reporting from 2022-01-01 to 2022-07-06", "END OF EXECUTION OF PROGRAM CBTRN03C");
    }

    @Test
    @DisplayName("BR-POST-034 Empty DATEPARM ends the report before any record")
    void brPost034_emptyDateParmStopsBeforeReport() {
        TransactionReportService.Result result = report(reportWithOneTransaction("2022-07-04"), List.of());
        assertThat(result.sysout()).containsExactly("START OF EXECUTION OF PROGRAM CBTRN03C",
                "END OF EXECUTION OF PROGRAM CBTRN03C");
        assertThat(result.records()).isEmpty();
    }

    @Test
    @DisplayName("BR-POST-035 Every reported record is echoed to SYSOUT")
    void brPost035_echoesRawTransactionRecords() {
        InMemoryPostingStore store = InMemoryPostingStore.defaults();
        byte[] record = InMemoryPostingStore.transaction("0000000000000001", CARD_1001, "01", 1,
                "1.00", "2022-07-04");
        store.addTransaction(record);
        TransactionReportService.Result result = report(store, List.of(InMemoryPostingStore.dateParm(
                "2022-01-01", "2022-07-06")));
        assertThat(result.sysout()).contains(new String(record, StandardCharsets.ISO_8859_1));
    }

    @Test
    @DisplayName("BR-POST-036 Out-of-window record ends the report via NEXT SENTENCE")
    void brPost036_outOfDateRecordStopsFurtherRows() {
        InMemoryPostingStore store = InMemoryPostingStore.defaults();
        store.addXref("4000000000001002", bd("1002"));
        store.addXref("4000000000001003", bd("1003"));
        store.addTransaction(InMemoryPostingStore.transaction("0000000000000001", CARD_1001, "01", 1,
                "1.00", "2022-07-02"));
        store.addTransaction(InMemoryPostingStore.transaction("0000000000000002", CARD_1002, "01", 1,
                "2.00", "2022-07-05"));
        store.addTransaction(InMemoryPostingStore.transaction("0000000000000003", "4000000000001003", "01", 1,
                "3.00", "2022-07-03"));
        TransactionReportService.Result result = report(store, List.of(InMemoryPostingStore.dateParm(
                "2022-07-01", "2022-07-04")));
        assertThat(rawTransactionIds(result.sysout())).containsExactly("0000000000000001");
    }

    @Test
    @DisplayName("BR-POST-037 Empty day produces a zero-record report")
    void brPost037_emptyFixtureHasNoReportRecords() {
        InMemoryPostingStore store = InMemoryPostingStore.fromFixture("p07-empty-day");
        post(store, InMemoryPostingStore.dailyInput("p07-empty-day"));
        TransactionReportService.Result result = report(store, InMemoryPostingStore.dateParm("p07-empty-day"));
        assertThat(result.returnCode()).isZero();
        assertThat(result.records()).isEmpty();
    }

    @Test
    @DisplayName("BR-POST-038 Report header block and 133-byte records")
    void brPost038_writes133ByteHeaderAndRecords() {
        TransactionReportService.Result result = report(reportWithOneTransaction("2022-07-04"),
                List.of(InMemoryPostingStore.dateParm("2022-01-01", "2022-07-06")));
        assertThat(result.records()).isNotEmpty().allSatisfy(line -> assertThat(line).hasSize(133));
        assertThat(result.records().getFirst()).startsWith("DALYREPT");
        assertThat(result.records()).contains(" ".repeat(133), "-".repeat(133));
    }

    @Test
    @DisplayName("BR-POST-039 Detail line layout")
    void brPost039_writesExpectedDetailColumns() {
        TransactionReportService.Result result = report(reportWithOneTransaction("2022-07-04"),
                List.of(InMemoryPostingStore.dateParm("2022-01-01", "2022-07-06")));
        assertThat(result.records().get(4)).startsWith("0000000000000001 00000001001 01-Purchase");
        assertThat(result.records().get(4)).contains("0001-Retail", "POS TERM");
    }

    @Test
    @DisplayName("BR-POST-040 Card break writes the Account Total")
    void brPost040_writesPreviousCardTotalOnBreak() {
        InMemoryPostingStore store = InMemoryPostingStore.defaults();
        store.addXref(CARD_1002, bd("1002"));
        store.addTransaction(InMemoryPostingStore.transaction("0000000000000001", CARD_1001, "01", 1,
                "10.00", "2022-07-04"));
        store.addTransaction(InMemoryPostingStore.transaction("0000000000000002", CARD_1002, "01", 1,
                "20.00", "2022-07-04"));
        TransactionReportService.Result result = report(store, List.of(InMemoryPostingStore.dateParm(
                "2022-01-01", "2022-07-06")));
        int accountTotal = indexStarting(result.records(), "Account Total");
        int secondDetail = indexStarting(result.records(), "0000000000000002");
        assertThat(accountTotal).isBetween(0, secondDetail);
        assertThat(result.records().get(accountTotal)).contains(CobolFormat.editSigned(bd("10.00")));
    }

    @Test
    @DisplayName("BR-POST-041 Page break every 20 lines; line counter never resets")
    void brPost041_keepsAccumulatingLineCounter() {
        InMemoryPostingStore store = InMemoryPostingStore.defaults();
        for (int index = 1; index <= 40; index++) {
            store.addTransaction(InMemoryPostingStore.transaction(String.format("%016d", index), CARD_1001, "01", 1,
                    "1.00", "2022-07-04"));
        }
        TransactionReportService.Result result = report(store, List.of(InMemoryPostingStore.dateParm(
                "2022-01-01", "2022-07-06")));
        assertThat(result.records().stream().filter(line -> line.startsWith("Page Total")).count()).isEqualTo(3);
        assertThat(result.records().stream().filter(line -> line.startsWith("DALYREPT")).count()).isEqualTo(3);
    }

    @Test
    @DisplayName("BR-POST-042 Page total rolls into the grand total")
    void brPost042_addsPageTotalToGrandTotal() {
        InMemoryPostingStore store = reportWithTransactions("100.00", "200.00");
        TransactionReportService.Result result = report(store, List.of(InMemoryPostingStore.dateParm(
                "2022-01-01", "2022-07-06")));
        assertThat(result.records().stream().filter(line -> line.startsWith("Grand Total"))
                .findFirst().orElseThrow()).contains("+        500.00");
    }

    @Test
    @DisplayName("BR-POST-043 EOF totals double-count the last amount and skip the last Account Total")
    void brPost043_doubleCountsLastAmountAndOmitsLastAccountTotal() {
        TransactionReportService.Result result = report(reportWithTransactions("100.00", "200.00"),
                List.of(InMemoryPostingStore.dateParm("2022-01-01", "2022-07-06")));
        assertThat(result.records()).noneMatch(line -> line.startsWith("Account Total"));
        assertThat(result.records().stream().filter(line -> line.startsWith("Page Total"))
                .findFirst().orElseThrow()).contains("+        500.00");
    }

    @Test
    @DisplayName("BR-POST-044 EOF SYSOUT shows TRAN-AMT and the pre-adjustment page total")
    void brPost044_logsEofAmountAndPreviousPageTotal() {
        TransactionReportService.Result result = report(reportWithTransactions("100.00", "200.00"),
                List.of(InMemoryPostingStore.dateParm("2022-01-01", "2022-07-06")));
        assertThat(result.sysout()).contains("TRAN-AMT 00000020000+", "WS-PAGE-TOTAL00000030000+");
    }

    @Test
    @DisplayName("BR-POST-045 Grand Total line")
    void brPost045_writesGrandTotalLine() {
        TransactionReportService.Result result = report(reportWithTransactions("100.00", "200.00"),
                List.of(InMemoryPostingStore.dateParm("2022-01-01", "2022-07-06")));
        assertThat(result.records().stream().filter(line -> line.startsWith("Grand Total")).count()).isEqualTo(1);
    }

    @Test
    @DisplayName("BR-POST-046 CBTRN03C lookup failures abend")
    void brPost046_abendsOnMissingReportLookups() {
        InMemoryPostingStore xrefMissing = InMemoryPostingStore.defaults();
        xrefMissing.addTransaction(InMemoryPostingStore.transaction("0000000000000001", "4000000000009999",
                "01", 1, "1.00", "2022-07-04"));
        assertReportAbend(xrefMissing, "INVALID CARD NUMBER : 4000000000009999");

        InMemoryPostingStore typeMissing = reportWithOneTransaction("2022-07-04");
        typeMissing.removeTypeDescription("01");
        assertReportAbend(typeMissing, "INVALID TRANSACTION TYPE : 01");

        InMemoryPostingStore categoryMissing = InMemoryPostingStore.defaults();
        categoryMissing.addTransaction(InMemoryPostingStore.transaction("0000000000000001", CARD_1001,
                "01", 2, "1.00", "2022-07-04"));
        assertReportAbend(categoryMissing, "INVALID TRAN CATG KEY : 010002");
    }

    @Test
    @DisplayName("BR-POST-047 CBTRN03C I/O error messages")
    void brPost047_hasReportIoErrorCatalogue() {
        assertThat(TransactionReportService.ioErrorMessages()).containsExactly(
                "ERROR OPENING TRANSACTION FILE", "ERROR OPENING REPORT FILE", "ERROR OPENING CROSS REF FILE",
                "ERROR OPENING TRANSACTION TYPE FILE", "ERROR OPENING TRANSACTION CATG FILE",
                "ERROR OPENING DATE PARM FILE", "ERROR READING TRANSACTION FILE", "ERROR WRITING REPTFILE",
                "ERROR CLOSING POSTED TRANSACTION FILE", "ERROR CLOSING REPORT FILE",
                "ERROR CLOSING CROSS REF FILE", "ERROR CLOSING TRANSACTION TYPE FILE",
                "ERROR CLOSING TRANSACTION CATG FILE", "ERROR CLOSING DATE PARM FILE");
        assertThat(TransactionReportService.ioError("ERROR READING TRANSACTION FILE", "23"))
                .containsExactly("ERROR READING TRANSACTION FILE", "FILE STATUS IS: NNNN0023",
                        "ABENDING PROGRAM", "CEE3ABD: USER ABEND U+0000000999");
    }

    private static DailyPostingService.Result post(InMemoryPostingStore store, List<byte[]> records) {
        return new DailyPostingService(store).post(records, CLOCK);
    }

    private static TransactionReportService.Result report(InMemoryPostingStore store, List<byte[]> dateParm) {
        return new TransactionReportService(store).report(dateParm);
    }

    private static InMemoryPostingStore reportWithOneTransaction(String date) {
        InMemoryPostingStore store = InMemoryPostingStore.defaults();
        store.addTransaction(InMemoryPostingStore.transaction("0000000000000001", CARD_1001, "01", 1,
                "100.00", date));
        return store;
    }

    private static InMemoryPostingStore reportWithTransactions(String first, String second) {
        InMemoryPostingStore store = InMemoryPostingStore.defaults();
        store.addTransaction(InMemoryPostingStore.transaction("0000000000000001", CARD_1001, "01", 1,
                first, "2022-07-04"));
        store.addTransaction(InMemoryPostingStore.transaction("0000000000000002", CARD_1001, "01", 1,
                second, "2022-07-04"));
        return store;
    }

    private static void assertReportAbend(InMemoryPostingStore store, String message) {
        TransactionReportService.Result result = report(store, List.of(InMemoryPostingStore.dateParm(
                "2022-01-01", "2022-07-06")));
        assertThat(result.abended()).isTrue();
        assertThat(result.returnCode()).isEqualTo(12);
        assertThat(result.sysout()).containsSubsequence(message, "FILE STATUS IS: NNNN0023",
                "ABENDING PROGRAM", "CEE3ABD: USER ABEND U+0000000999");
        assertThat(result.sysout()).doesNotContain("END OF EXECUTION OF PROGRAM CBTRN03C");
    }

    private static List<String> rawTransactionIds(List<String> sysout) {
        return sysout.stream().filter(line -> line.length() == 350)
                .map(line -> line.substring(0, 16)).toList();
    }

    private static int reason(byte[] reject) {
        return Integer.parseInt(new String(reject, 350, 4, StandardCharsets.ISO_8859_1));
    }

    private static String rejectDescription(byte[] reject) {
        return new String(reject, 354, 76, StandardCharsets.ISO_8859_1);
    }

    private static int indexStarting(List<String> records, String prefix) {
        for (int index = 0; index < records.size(); index++) {
            if (records.get(index).startsWith(prefix)) {
                return index;
            }
        }
        return -1;
    }

    private static byte[] daily(String id, String card, String type, int category, String amount, String date) {
        return InMemoryPostingStore.daily(id, card, type, category, amount, date);
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
