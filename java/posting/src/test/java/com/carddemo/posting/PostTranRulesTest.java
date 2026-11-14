package com.carddemo.posting;

import static com.carddemo.posting.TestData.daily;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.common.TestRepo;
import com.carddemo.common.run.SysoutWriter;
import com.carddemo.posting.post.Account;
import com.carddemo.posting.post.DailyTranRecord;
import com.carddemo.posting.post.PostTranProgram;
import com.carddemo.posting.post.TcatKey;
import com.carddemo.posting.post.TransactionPoster;
import com.carddemo.posting.post.TransactionValidator;
import com.carddemo.posting.post.Validation;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.Test;

/** CBTRN02C (POSTTRAN STEP15) rules BR-POST-001 .. BR-POST-029, on in-memory files. */
class PostTranRulesTest {

    private static final String CARD = "4000000000001001";

    private final InMemoryPostingStore store = new InMemoryPostingStore()
            .card(CARD, 1001).account(1001, "1000.00", "0.00", "0.00", "2025-12-31");
    private final SysoutWriter sysout = new SysoutWriter();
    private final PostTranProgram program = new PostTranProgram(store, sysout, TestData.PROC_TS);

    private Validation validate(String raw) {
        return new TransactionValidator(store).validate(new DailyTranRecord(raw));
    }

    @Test
    void BR_POST_001_postTranJclRunsCbtrn02cWithTheSixDatasets() throws Exception {
        String jcl = Files.readString(TestRepo.root().resolve("app/jcl/POSTTRAN.jcl"));
        assertThat(jcl).contains("EXEC PGM=CBTRN02C");
        for (String dd : List.of("TRANFILE", "DALYTRAN", "XREFFILE", "DALYREJS", "ACCTFILE", "TCATBALF")) {
            assertThat(jcl).contains("//" + dd);
        }
        assertThat(jcl).contains("LRECL=430");
        assertThat(PostTranProgram.REJECT_LRECL).isEqualTo(430);
    }

    @Test
    void BR_POST_002_everyRecordIsReadAndCounted() {
        program.runAll(List.of(daily("1", "1.00", CARD), daily("2", "2.00", "9999"), daily("3", "3.00", CARD)));
        assertThat(program.processed()).isEqualTo(3);
        assertThat(sysout.lines()).contains("TRANSACTIONS PROCESSED :000000003");
    }

    @Test
    void BR_POST_003_cardIsCheckedBeforeTheAccount() {
        store.card("4000000000009999", 4242); // card known, account unknown
        assertThat(validate(daily("1", "1.00", "4000000000000000")).reason()).isEqualTo(100);
        assertThat(validate(daily("1", "1.00", "4000000000009999")).reason()).isEqualTo(101);
    }

    @Test
    void BR_POST_004_unknownCardRejects100() {
        Validation v = validate(daily("1", "1.00", "4000999999999999"));
        assertThat(v.reason()).isEqualTo(100);
        assertThat(v.trailer()).startsWith("0100INVALID CARD NUMBER FOUND").hasSize(80);
    }

    @Test
    void BR_POST_005_missingAccountRejects101WithoutLimitOrExpiryChecks() {
        store.card("4000000000001099", 1099);
        Validation v = validate(daily("1", "999999999.99", "4000000000001099"));
        assertThat(v.reason()).isEqualTo(101);
        assertThat(v.reasonDesc()).isEqualTo("ACCOUNT RECORD NOT FOUND");
    }

    @Test
    void BR_POST_006_limitCheckUsesCycleAmountsNotTheBalance() {
        Account a = new Account(1, new BigDecimal("99999.00"), new BigDecimal("1000.00"), new BigDecimal("950.00"), new BigDecimal("0.00"), "2025-12-31");
        assertThat(TransactionValidator.withinLimit(a, new BigDecimal("50.00"))).as("exactly on the limit").isTrue();
        assertThat(TransactionValidator.withinLimit(a, new BigDecimal("50.01"))).isFalse();
    }

    @Test
    void BR_POST_007_overLimitRejects102() {
        store.account(1001, "1000.00", "950.00", "0.00", "2025-12-31");
        Validation v = validate(daily("2", "100.00", CARD));
        assertThat(v.reason()).isEqualTo(102);
        assertThat(v.reasonDesc()).isEqualTo("OVERLIMIT TRANSACTION");
    }

    @Test
    void BR_POST_008_limitCheckSeesEarlierPostingsOfTheSameRun() {
        store.account(1001, "100.00", "0.00", "0.00", "2025-12-31");
        program.runAll(List.of(daily("1", "60.00", CARD), daily("2", "60.00", CARD)));
        assertThat(program.rejected()).isEqualTo(1);
        assertThat(program.rejects().get(0)).contains("0102OVERLIMIT TRANSACTION");
    }

    @Test
    void BR_POST_009_expiryBeforeOriginDateRejects103_onTheDayIsAccepted() {
        store.account(1001, "1000.00", "0.00", "0.00", "2022-07-04");
        assertThat(validate(daily("1", "01", 1, "1.00", CARD, "2022-07-04-23.59.59.000000")).reason()).isZero();
        Validation v = validate(daily("1", "01", 1, "1.00", CARD, "2022-07-05-00.00.00.000000"));
        assertThat(v.reason()).isEqualTo(103);
        assertThat(v.reasonDesc()).isEqualTo("TRANSACTION RECEIVED AFTER ACCT EXPIRATION");
    }

    @Test
    void BR_POST_010_expiryReasonOverridesOverLimit() {
        store.account(1001, "10.00", "0.00", "0.00", "2022-01-31");
        Validation v = validate(daily("1", "500.00", CARD));
        assertThat(v.reason()).isEqualTo(103);
        assertThat(v.trailer()).startsWith("0103TRANSACTION RECEIVED AFTER ACCT EXPIRATION");
    }

    @Test
    void BR_POST_011_blankOrZeroExpiryRejectsEveryTransaction() {
        assertThat(TransactionValidator.notExpired("", "2022-07-04-09.15.00.000000")).isFalse();
        assertThat(TransactionValidator.notExpired("0000-00-00", "2022-07-04-09.15.00.000000")).isFalse();
        store.account(1001, "1000.00", "0.00", "0.00", "          ");
        assertThat(validate(daily("1", "1.00", CARD)).reason()).isEqualTo(103);
    }

    @Test
    void BR_POST_012_rejectRecordIsTheInputPlusAn80ByteTrailer() {
        String raw = daily("7", "1.00", "4000999999999999");
        program.runAll(List.of(raw));
        String rej = program.rejects().get(0);
        assertThat(rej).hasSize(430);
        assertThat(rej.substring(0, 350)).isEqualTo(raw);
        assertThat(rej.substring(350, 354)).isEqualTo("0100");
        assertThat(rej.substring(354)).isEqualTo(Cobol.pic("INVALID CARD NUMBER FOUND", 76));
    }

    @Test
    void BR_POST_013_rejectsAreCountedAndPostingContinues() {
        int rc = program.runAll(List.of(daily("1", "1.00", "4000999999999999"), daily("2", "2.00", CARD)));
        assertThat(program.rejected()).isEqualTo(1);
        assertThat(store.transact).containsKey("2");
        assertThat(rc).isEqualTo(4);
    }

    @Test
    void BR_POST_014_returnCode4OnlyWhenSomethingWasRejected() {
        assertThat(program.runAll(List.of(daily("1", "1.00", CARD)))).isZero();
        PostTranProgram other = new PostTranProgram(store, new SysoutWriter(), TestData.PROC_TS);
        assertThat(other.runAll(List.of(daily("9", "1.00", "4000999999999999")))).isEqualTo(4);
    }

    @Test
    void BR_POST_015_sysoutMessagesAndCounters() {
        store.tcat.put(new TcatKey(1001, "01", 1), new BigDecimal("0.00"));
        program.runAll(List.of(daily("1", "1.00", CARD), daily("2", "1.00", "4000999999999999")));
        assertThat(sysout.lines()).containsExactly(
                "START OF EXECUTION OF PROGRAM CBTRN02C",
                "TRANSACTIONS PROCESSED :000000002",
                "TRANSACTIONS REJECTED  :000000001",
                "END OF EXECUTION OF PROGRAM CBTRN02C");
    }

    @Test
    void BR_POST_016_postedTransactionCopiesTheDailyFields() {
        store.tcat.put(new TcatKey(1001, "01", 1), new BigDecimal("0.00"));
        program.runAll(List.of(daily("0000000000000042", "12.34", CARD)));
        var t = store.transact.get("0000000000000042");
        assertThat(t.amt()).isEqualByComparingTo("12.34");
        assertThat(t.cardNum()).isEqualTo(CARD);
        assertThat(t.merchantName()).isEqualTo("Test Merchant");
        assertThat(t.origTs()).isEqualTo("2022-07-04-09.15.00.000000");
        assertThat(t.typeCd()).isEqualTo("01");
        assertThat(t.catCd()).isEqualTo(1);
    }

    @Test
    void BR_POST_017_processingTimestampIsTheRunClock() {
        String raw = daily("5", "1.00", CARD);
        raw = raw.substring(0, 304) + Cobol.pic("1999-01-01-00.00.00.000000", 26) + raw.substring(330);
        program.runAll(List.of(raw));
        assertThat(store.transact.get("5").procTs()).isEqualTo("2022-07-05-10.00.00.000000");
    }

    @Test
    void BR_POST_018_categoryBalanceKeyIsAccountTypeCategory() {
        program.runAll(List.of(daily("1", "02", 3, "5.00", CARD, "2022-07-04-09.15.00.000000")));
        assertThat(store.tcat).containsKey(new TcatKey(1001, "02", 3));
        assertThat(new TcatKey(1006, "02", 1).display()).isEqualTo("00000001006020001");
    }

    @Test
    void BR_POST_019_missingCategoryBalanceIsCreatedWithTheAmount() {
        program.runAll(List.of(daily("1", "-25.50", CARD)));
        assertThat(store.tcat.get(new TcatKey(1001, "01", 1))).isEqualByComparingTo("-25.50");
        assertThat(sysout.lines()).contains("TCATBAL record not found for key : 00000001001010001.. Creating.");
    }

    @Test
    void BR_POST_020_existingCategoryBalanceIsAddedTo() {
        store.tcat.put(new TcatKey(1001, "01", 1), new BigDecimal("100.00"));
        program.runAll(List.of(daily("1", "-30.25", CARD), daily("2", "5.00", CARD)));
        assertThat(store.tcat.get(new TcatKey(1001, "01", 1))).isEqualByComparingTo("74.75");
        assertThat(sysout.lines()).noneMatch(l -> l.startsWith("TCATBAL"));
    }

    @Test
    void BR_POST_021_categoryBalanceWriteFailureAbends() {
        InMemoryPostingStore failing = new InMemoryPostingStore() {
            @Override
            public boolean writeTcatBal(TcatKey k, BigDecimal b) {
                return false;
            }
        };
        SysoutWriter out = new SysoutWriter();
        TransactionPoster poster = new TransactionPoster(failing, out, TestData.PROC_TS);
        assertThatThrownBy(() -> poster.updateTcatBal(new TcatKey(1, "01", 1), BigDecimal.ONE)).isInstanceOf(CobolAbend.class);
        assertThat(out.lines()).endsWith("ERROR WRITING TRANSACTION BALANCE FILE", "FILE STATUS IS: NNNN0022", "ABENDING PROGRAM");
    }

    @Test
    void BR_POST_022_balanceAndCycleTotalsUpdatedBySign() {
        program.runAll(List.of(daily("1", "100.00", CARD), daily("2", "-40.00", CARD), daily("3", "0.00", CARD)));
        Account a = store.accounts.get(1001L);
        assertThat(a.currBal).isEqualByComparingTo("60.00");
        assertThat(a.cycCredit).isEqualByComparingTo("100.00");
        assertThat(a.cycDebit).isEqualByComparingTo("-40.00");
    }

    @Test
    void BR_POST_023_paymentsRaiseTheLimitCheckAmount() {
        // credit 0, debit -900 (payments): 0 - (-900) + 200 = 1100 > 1000
        Account a = new Account(1, BigDecimal.ZERO, new BigDecimal("1000.00"), new BigDecimal("0.00"), new BigDecimal("-900.00"), "2025-12-31");
        assertThat(TransactionValidator.withinLimit(a, new BigDecimal("200.00"))).isFalse();
    }

    @Test
    void BR_POST_024_accountRewriteFailureSets109ButStillPosts() {
        store.failAccountRewrite = true;
        TransactionPoster poster = new TransactionPoster(store, sysout, TestData.PROC_TS);
        int reason = poster.post(validate(daily("1", "1.00", CARD)));
        assertThat(reason).isEqualTo(109);
        assertThat(store.transact).containsKey("1");
        int rc = new PostTranProgram(store, new SysoutWriter(), TestData.PROC_TS).runAll(List.of(daily("2", "1.00", CARD)));
        assertThat(rc).isZero();
    }

    @Test
    void BR_POST_025_postingOrderCategoryThenAccountThenTransaction() {
        program.runAll(List.of(daily("1", "1.00", CARD)));
        assertThat(store.calls).containsExactly("open-output", "tcat", "account", "transact");
    }

    @Test
    void BR_POST_026_transactionMasterIsOpenedForOutput() {
        store.transact.put("OLD", new TransactionPoster(store, sysout, "x").toTransaction(new DailyTranRecord(daily("OLD", "1.00", CARD))));
        program.runAll(List.of(daily("1", "1.00", CARD)));
        assertThat(store.transact.keySet()).containsExactly("1");
    }

    @Test
    void BR_POST_027_emptyDailyFilePostsNothingRc0() {
        int rc = program.runAll(List.of());
        assertThat(rc).isZero();
        assertThat(store.transact).isEmpty();
        assertThat(program.rejects()).isEmpty();
        assertThat(sysout.lines()).contains("TRANSACTIONS PROCESSED :000000000", "TRANSACTIONS REJECTED  :000000000");
    }

    @Test
    void BR_POST_028_ioErrorsDisplayStatusAndAbendRc12() {
        program.runAll(List.of(daily("1", "1.00", CARD)));
        SysoutWriter out = new SysoutWriter();
        TransactionPoster poster = new TransactionPoster(store, out, TestData.PROC_TS);
        assertThatThrownBy(() -> poster.writeTransaction(poster.toTransaction(new DailyTranRecord(daily("1", "1.00", CARD)))))
                .isInstanceOf(CobolAbend.class);
        assertThat(out.lines()).containsExactly("ERROR WRITING TO TRANSACTION FILE", "FILE STATUS IS: NNNN0022", "ABENDING PROGRAM");
        assertThat(CobolAbend.ABEND_RC).isEqualTo(12);
    }

    @Test
    void BR_POST_029_noRoundingAndHighOrderTruncation() {
        store.account(1001, "9999999999.99", "0.00", "0.00", "2025-12-31");
        store.accounts.get(1001L).currBal = new BigDecimal("9999999999.99");
        program.runAll(List.of(daily("1", "0.01", CARD)));
        // S9(10)V99 overflows silently: 10000000000.00 keeps its low-order 10 integer digits
        assertThat(store.accounts.get(1001L).currBal).isEqualByComparingTo("0.00");
        assertThat(store.accounts.get(1001L).cycCredit.scale()).isEqualTo(2);
    }
}
