package com.carddemo.interest;

import com.carddemo.common.codec.RecordCodec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.carddemo.interest.InterestFiles.Account;
import static com.carddemo.interest.InterestFiles.CardXref;
import static com.carddemo.interest.InterestFiles.DisclosureGroup;
import static com.carddemo.interest.InterestFiles.ReadResult;
import static com.carddemo.interest.InterestFiles.TranCatBal;
import static com.carddemo.interest.InterestFiles.Transaction;
import static org.assertj.core.api.Assertions.assertThat;

class InterestCalculationServiceTest {
    private static final String CURRENT_DATE = "2022070510000000+0000";

    @Test
    @DisplayName("BR-INT-001 INTCALC uses the shipped PARM date")
    void brInt001_intcalcParmAndTransactionPrefix() {
        FakeInterestFiles files = new FakeInterestFiles();
        addAccount(files, 2001, "GOLD", "1000.00", "12.00");
        files.balances.add(balance(2001, "01", 1, "1000.00"));

        InterestRunOutcome outcome = run(files);

        assertThat(InterestHandler.PARM).isEqualTo("2022071800");
        assertThat(outcome.transactions().getFirst().id()).isEqualTo("2022071800000001");
    }

    @Test
    @DisplayName("BR-INT-002 start and end banners")
    void brInt002_normalRunHasStartAndEndBanners() {
        InterestRunOutcome outcome = run(new FakeInterestFiles());

        assertThat(outcome.sysoutLines()).containsExactly(
                "START OF EXECUTION OF PROGRAM CBACT04C",
                "END OF EXECUTION OF PROGRAM CBACT04C");
    }

    @Test
    @DisplayName("BR-INT-003 setup chain preserves the INTCALC return code")
    void brInt003_setupChainHasZeroReturnCodeOnNormalRun() {
        InterestRunOutcome outcome = run(new FakeInterestFiles());

        assertThat(outcome.stepRc()).isZero();
        assertThat(outcome.abended()).isFalse();
    }

    @Test
    @DisplayName("BR-INT-004 files open in COBOL order")
    void brInt004_filesOpenInCobolOrder() {
        FakeInterestFiles files = new FakeInterestFiles();

        run(files);

        assertThat(files.events.subList(0, 5)).containsExactly(
                "open:TcatBal", "open:Xref", "open:DiscGrp", "open:Account", "open:Transaction");
    }

    @Test
    @DisplayName("BR-INT-005 open failures use file-specific messages")
    void brInt005_eachOpenFailureAbendsWithItsMessageAndStatus() {
        Map<String, String> messages = Map.of(
                "TcatBal", "ERROR OPENING TRANSACTION CATEGORY BALANCE",
                "Xref", "ERROR OPENING CROSS REF FILE35",
                "DiscGrp", "ERROR OPENING DALY REJECTS FILE",
                "Account", "ERROR OPENING ACCOUNT MASTER FILE",
                "Transaction", "ERROR OPENING TRANSACTION FILE");
        for (String file : List.of("TcatBal", "Xref", "DiscGrp", "Account", "Transaction")) {
            FakeInterestFiles files = new FakeInterestFiles();
            files.openStatuses.put(file, "35");

            InterestRunOutcome outcome = run(files);

            assertThat(outcome.abended()).isTrue();
            assertThat(outcome.stepRc()).isEqualTo(12);
            assertThat(outcome.sysoutLines()).contains(messages.get(file), "FILE STATUS IS: NNNN0035");
            assertThat(outcome.sysoutLines().getLast()).isEqualTo(
                    "CEE3ABD: USER ABEND U+0000000999");
            assertThat(outcome.sysoutLines()).doesNotContain(
                    "END OF EXECUTION OF PROGRAM CBACT04C");
        }
    }

    @Test
    @DisplayName("BR-INT-006 category balances are processed in key order")
    void brInt006_categoryBalancesRetainFileKeyOrder() {
        FakeInterestFiles files = new FakeInterestFiles();
        addAccount(files, 2007, "GOLD", "1500.00", "12.00");
        files.addDisclosure("GOLD", "01", 2, "18.00");
        files.balances.add(balance(2007, "01", 1, "1000.00"));
        files.balances.add(balance(2007, "01", 2, "500.00"));

        InterestRunOutcome outcome = run(files);

        assertThat(outcome.transactions()).extracting(Transaction::id)
                .containsExactly("2022071800000001", "2022071800000002");
        assertThat(outcome.transactions()).extracting(Transaction::amount)
                .containsExactly(money("10.00"), money("7.50"));
    }

    @Test
    @DisplayName("BR-INT-007 EOF ends the read loop and other read errors abend")
    void brInt007_eofIsNotEchoedAndOtherReadErrorsAbend() {
        FakeInterestFiles eofFiles = new FakeInterestFiles();
        addAccount(eofFiles, 2001, "GOLD", "1000.00", "12.00");
        eofFiles.balances.add(balance(2001, "01", 1, "1000.00"));
        eofFiles.readStatuses.addAll(List.of("00", "10"));
        InterestRunOutcome eofOutcome = run(eofFiles);

        assertThat(eofOutcome.sysoutLines()).hasSize(3);
        assertThat(eofOutcome.abended()).isFalse();

        FakeInterestFiles errorFiles = new FakeInterestFiles();
        errorFiles.readStatuses.add("30");
        InterestRunOutcome errorOutcome = run(errorFiles);
        assertThat(errorOutcome.sysoutLines()).contains(
                "ERROR READING TRANSACTION CATEGORY FILE", "FILE STATUS IS: NNNN0030");
        assertThat(errorOutcome.abended()).isTrue();
    }

    @Test
    @DisplayName("BR-INT-008 every category balance is echoed as a 50-byte record")
    void brInt008_categoryBalanceEchoRetainsZonedSignAndTrailingSpaces() {
        FakeInterestFiles files = new FakeInterestFiles();
        addAccount(files, 2008, "GOLD", "-300.00", "12.00");
        files.balances.add(balance(2008, "01", 1, "-300.00"));

        InterestRunOutcome outcome = run(files);

        assertThat(outcome.sysoutLines().get(1))
                .isEqualTo("000000020080100010000003000}" + " ".repeat(22));
        assertThat(outcome.sysoutLines().get(1)).hasSize(50);
    }

    @Test
    @DisplayName("BR-INT-009 account break rewrites only the previous account")
    void brInt009_previousAccountIsRewrittenBeforeNextAccountRead() {
        FakeInterestFiles files = new FakeInterestFiles();
        addAccount(files, 2001, "GOLD", "1000.00", "12.00");
        addAccount(files, 2002, "GOLD", "2400.00", "18.50");
        files.balances.add(balance(2001, "01", 1, "1000.00"));
        files.balances.add(balance(2002, "01", 1, "2400.00"));

        run(files);

        assertThat(files.events.indexOf("rewrite:2001"))
                .isLessThan(files.events.indexOf("account:2002"));
    }

    @Test
    @DisplayName("BR-INT-010 account lookup supplies the disclosure group")
    void brInt010_accountReadSuppliesGroupForRateLookup() {
        FakeInterestFiles files = new FakeInterestFiles();
        addAccount(files, 2002, "STANDARD", "1000.00", "18.50");
        files.balances.add(balance(2002, "01", 1, "2400.00"));

        InterestRunOutcome outcome = run(files);

        assertThat(files.disclosureCalls.getFirst()).isEqualTo("STANDARD|01|1");
        assertThat(outcome.transactions().getFirst().amount()).isEqualTo(money("37.00"));
    }

    @Test
    @DisplayName("BR-INT-011 a missing account abends with its 11-digit id")
    void brInt011_missingAccountDisplaysIdAndAbends() {
        FakeInterestFiles files = new FakeInterestFiles();
        files.balances.add(balance(3001, "01", 1, "1000.00"));

        InterestRunOutcome outcome = run(files);

        assertThat(outcome.sysoutLines()).contains(
                "ACCOUNT NOT FOUND: 00000003001",
                "ERROR READING ACCOUNT FILE",
                "FILE STATUS IS: NNNN0023",
                "ABENDING PROGRAM");
        assertThat(outcome.transactions()).isEmpty();
    }

    @Test
    @DisplayName("BR-INT-012 the card number comes from the account cross-reference")
    void brInt012_cardNumberIsReadByAccountId() {
        FakeInterestFiles files = new FakeInterestFiles();
        addAccount(files, 2001, "GOLD", "1000.00", "12.00");
        files.xrefs.put(2001L, new CardXref("4000000000002001", 3001, 2001));
        files.balances.add(balance(2001, "01", 1, "1000.00"));

        InterestRunOutcome outcome = run(files);

        assertThat(outcome.transactions().getFirst().cardNumber()).isEqualTo("4000000000002001");
    }

    @Test
    @DisplayName("BR-INT-013 a missing cross-reference uses the legacy account-not-found text")
    void brInt013_missingXrefUsesAccountNotFoundMessage() {
        FakeInterestFiles files = new FakeInterestFiles();
        files.accounts.put(2001L, account(2001, "GOLD", "1000.00", "100.00", "100.00", "Y"));
        files.disclosures.put(key("GOLD", "01", 1), disclosure("GOLD", "01", 1, "12.00"));
        files.balances.add(balance(2001, "01", 1, "1000.00"));

        InterestRunOutcome outcome = run(files);

        assertThat(outcome.sysoutLines()).contains(
                "ACCOUNT NOT FOUND: 00000002001",
                "ERROR READING XREF FILE",
                "FILE STATUS IS: NNNN0023",
                "ABENDING PROGRAM");
    }

    @Test
    @DisplayName("BR-INT-014 disclosure key contains group, type, and category")
    void brInt014_disclosureLookupUsesCompleteKey() {
        FakeInterestFiles files = new FakeInterestFiles();
        addAccount(files, 2002, "STANDARD  ", "1000.00", "18.50");
        files.addDisclosure("STANDARD  ", "01", 1, "18.50");
        files.balances.add(balance(2002, "01", 1, "2400.00"));

        InterestRunOutcome outcome = run(files);

        assertThat(files.disclosureCalls.getFirst()).isEqualTo("STANDARD  |01|1");
        assertThat(outcome.transactions().getFirst().amount()).isEqualTo(money("37.00"));
    }

    @Test
    @DisplayName("BR-INT-015 interest rates differ by account group")
    void brInt015_interestRatesDifferByAccountGroup() {
        FakeInterestFiles files = new FakeInterestFiles();
        addAccount(files, 2001, "GOLD", "1000.00", "12.00");
        addAccount(files, 2002, "STANDARD", "2400.00", "18.50");
        files.balances.add(balance(2001, "01", 1, "1000.00"));
        files.balances.add(balance(2002, "01", 1, "2400.00"));

        InterestRunOutcome outcome = run(files);

        assertThat(outcome.transactions()).extracting(Transaction::amount)
                .containsExactly(money("10.00"), money("37.00"));
    }

    @Test
    @DisplayName("BR-INT-016 interest rates differ by transaction category")
    void brInt016_interestRatesDifferByCategory() {
        FakeInterestFiles files = new FakeInterestFiles();
        addAccount(files, 2007, "GOLD", "1500.00", "12.00");
        files.addDisclosure("GOLD", "01", 2, "18.00");
        files.balances.add(balance(2007, "01", 1, "1000.00"));
        files.balances.add(balance(2007, "01", 2, "500.00"));

        InterestRunOutcome outcome = run(files);

        assertThat(outcome.transactions()).extracting(Transaction::amount)
                .containsExactly(money("10.00"), money("7.50"));
    }

    @Test
    @DisplayName("BR-INT-017 a missing group uses DEFAULT with the legacy warnings")
    void brInt017_missingGroupFallsBackToDefault() {
        FakeInterestFiles files = new FakeInterestFiles();
        files.accounts.put(2006L, account(2006, "PLATINUM", "1000.00", "5000.00", "1200.00", "Y"));
        files.xrefs.put(2006L, new CardXref("4000000000002006", 3001, 2006));
        files.addDisclosure("DEFAULT", "01", 1, "21.00");
        files.balances.add(balance(2006, "01", 1, "1000.00"));

        InterestRunOutcome outcome = run(files);

        assertThat(outcome.sysoutLines()).containsSubsequence(
                "DISCLOSURE GROUP RECORD MISSING", "TRY WITH DEFAULT GROUP CODE");
        assertThat(outcome.sysoutLines().get(1)).startsWith("00000002006");
        assertThat(outcome.transactions().getFirst().amount()).isEqualTo(money("17.50"));
    }

    @Test
    @DisplayName("BR-INT-018 DEFAULT accounts match directly")
    void brInt018_defaultGroupMatchesWithoutFallback() {
        FakeInterestFiles files = new FakeInterestFiles();
        addAccount(files, 2010, "DEFAULT", "777.77", "21.00");
        files.balances.add(balance(2010, "01", 1, "777.77"));

        InterestRunOutcome outcome = run(files);

        assertThat(outcome.sysoutLines()).doesNotContain("DISCLOSURE GROUP RECORD MISSING");
        assertThat(outcome.transactions().getFirst().amount()).isEqualTo(money("13.61"));
    }

    @Test
    @DisplayName("BR-INT-019 disclosure lookup failures abend with the fallback message")
    void brInt019_defaultDisclosureReadFailureAbends() {
        FakeInterestFiles files = new FakeInterestFiles();
        addAccount(files, 2001, "PLATINUM", "1000.00", "0.00");
        files.disclosureStatuses.put(key("DEFAULT", "01", 3), "23");
        files.balances.add(balance(2001, "01", 3, "1000.00"));

        InterestRunOutcome outcome = run(files);

        assertThat(outcome.sysoutLines()).contains(
                "DISCLOSURE GROUP RECORD MISSING",
                "TRY WITH DEFAULT GROUP CODE",
                "ERROR READING DEFAULT DISCLOSURE GROUP",
                "FILE STATUS IS: NNNN0023",
                "ABENDING PROGRAM");

        FakeInterestFiles initialReadFailure = new FakeInterestFiles();
        addAccount(initialReadFailure, 2001, "PLATINUM", "1000.00", "0.00");
        initialReadFailure.disclosureStatuses.put(key("PLATINUM", "01", 3), "30");
        initialReadFailure.balances.add(balance(2001, "01", 3, "1000.00"));
        InterestRunOutcome initialOutcome = run(initialReadFailure);
        assertThat(initialOutcome.sysoutLines()).contains(
                "ERROR READING DISCLOSURE GROUP FILE", "FILE STATUS IS: NNNN0030");
    }

    @Test
    @DisplayName("BR-INT-020 zero-rate categories do not create transactions")
    void brInt020_zeroRateSkipsWriteAndSequence() {
        FakeInterestFiles files = new FakeInterestFiles();
        addAccount(files, 2005, "GOLD", "1000.00", "12.00");
        files.addDisclosure("GOLD", "02", 1, "0.00");
        files.balances.add(balance(2005, "01", 1, "1000.00"));
        files.balances.add(balance(2005, "02", 1, "-300.00"));

        InterestRunOutcome outcome = run(files);

        assertThat(outcome.transactions()).hasSize(1);
        assertThat(outcome.transactions().getFirst().id()).isEqualTo("2022071800000001");
        assertThat(outcome.transactions().getFirst().amount()).isEqualTo(money("10.00"));
    }

    @Test
    @DisplayName("BR-INT-021 monthly interest uses balance times rate divided by 1200")
    void brInt021_monthlyInterestFormula() {
        FakeInterestFiles files = new FakeInterestFiles();
        addAccount(files, 2002, "STANDARD", "2400.00", "18.50");
        files.balances.add(balance(2002, "01", 1, "2400.00"));

        InterestRunOutcome outcome = run(files);

        assertThat(outcome.transactions().getFirst().amount()).isEqualTo(money("37.00"));
    }

    @Test
    @DisplayName("BR-INT-022 half-cent interest is truncated toward zero")
    void brInt022_halfCentInterestTruncatesTowardZero() {
        FakeInterestFiles files = new FakeInterestFiles();
        addAccount(files, 2003, "GOLD", "1235.50", "12.00");
        addAccount(files, 2004, "GOLD", "1234.50", "12.00");
        files.balances.add(balance(2003, "01", 1, "1235.50"));
        files.balances.add(balance(2004, "01", 1, "1234.50"));

        InterestRunOutcome outcome = run(files);

        assertThat(outcome.transactions()).extracting(Transaction::amount)
                .containsExactly(money("12.35"), money("12.34"));
    }

    @Test
    @DisplayName("BR-INT-023 other sub-cent remainders are truncated")
    void brInt023_nonTieSubCentAmountsAreTruncated() {
        FakeInterestFiles files = new FakeInterestFiles();
        addAccount(files, 2009, "STANDARD", "1000.33", "18.50");
        addAccount(files, 2010, "DEFAULT", "777.77", "21.00");
        files.balances.add(balance(2009, "01", 1, "1000.33"));
        files.balances.add(balance(2010, "01", 1, "777.77"));

        InterestRunOutcome outcome = run(files);

        assertThat(outcome.transactions()).extracting(Transaction::amount)
                .containsExactly(money("15.42"), money("13.61"));
    }

    @Test
    @DisplayName("BR-INT-024 negative balances generate negative interest")
    void brInt024_negativeBalanceProducesNegativeInterest() {
        FakeInterestFiles files = new FakeInterestFiles();
        addAccount(files, 2008, "GOLD", "-300.00", "12.00");
        files.balances.add(balance(2008, "01", 1, "-300.00"));

        InterestRunOutcome outcome = run(files);

        assertThat(outcome.transactions().getFirst().amount()).isEqualTo(money("-3.00"));
        assertThat(new String(InterestHandler.encodeTransaction(outcome.transactions().getFirst()),
                StandardCharsets.ISO_8859_1)).contains("0000000030}");
    }

    @Test
    @DisplayName("BR-INT-025 monetary amounts use zoned decimal signs")
    void brInt025_accountAndTransactionAmountsUseZonedDecimal() {
        Account account = account(2003, "GOLD", "1247.85", "0.00", "0.00", "Y");
        byte[] accountRecord = InterestHandler.encodeAccount(account);
        FakeInterestFiles files = new FakeInterestFiles();
        addAccount(files, 2003, "GOLD", "1235.50", "12.00");
        files.addDisclosure("GOLD", "01", 1, "18.50");
        files.balances.add(balance(2003, "01", 1, "1000.33"));
        Transaction transaction = run(files).transactions().getFirst();
        byte[] transactionRecord = InterestHandler.encodeTransaction(transaction);

        assertThat(new String(accountRecord, StandardCharsets.ISO_8859_1)).contains("00000012478E");
        assertThat(new String(transactionRecord, StandardCharsets.ISO_8859_1)).contains("0000000154B");
    }

    @Test
    @DisplayName("BR-INT-026 account total adds truncated category amounts")
    void brInt026_accountTotalUsesTruncatedMonthlyAmounts() {
        FakeInterestFiles files = new FakeInterestFiles();
        addAccount(files, 2009, "STANDARD", "1000.33", "18.50");
        addAccount(files, 2010, "DEFAULT", "777.77", "21.00");
        files.balances.add(balance(2009, "01", 1, "1000.33"));
        files.balances.add(balance(2010, "01", 1, "777.77"));

        run(files);

        assertThat(files.rewrites.getFirst().currentBalance()).isEqualTo(money("1015.75"));
    }

    @Test
    @DisplayName("BR-INT-027 arithmetic overflow silently drops high-order digits")
    void brInt027_overflowDropsHighOrderDigits() {
        FakeInterestFiles files = new FakeInterestFiles();
        addAccount(files, 2001, "GOLD", "9999999999.99", "9999.99");
        addAccount(files, 2002, "STANDARD", "100.00", "12.00");
        files.balances.add(balance(2001, "01", 1, "999999999.99"));
        files.balances.add(balance(2002, "01", 1, "100.00"));

        InterestRunOutcome outcome = run(files);

        assertThat(outcome.transactions().getFirst().amount()).isEqualTo(money("333324999.91"));
        assertThat(files.rewrites.getFirst().currentBalance()).isEqualTo(money("333324999.90"));
        assertThat(outcome.stepRc()).isZero();
    }

    @Test
    @DisplayName("BR-INT-028 account update adds the account's total interest")
    void brInt028_updateAddsInterestToPreviousAccount() {
        FakeInterestFiles files = new FakeInterestFiles();
        addAccount(files, 2003, "GOLD", "1235.50", "12.00");
        addAccount(files, 2004, "GOLD", "1234.50", "12.00");
        files.balances.add(balance(2003, "01", 1, "1235.50"));
        files.balances.add(balance(2004, "01", 1, "100.00"));

        run(files);

        assertThat(files.rewrites.getFirst().currentBalance()).isEqualTo(money("1247.85"));
    }

    @Test
    @DisplayName("BR-INT-029 account update zeros cycle credit and debit")
    void brInt029_updateZerosCycleFields() {
        FakeInterestFiles files = new FakeInterestFiles();
        files.accounts.put(2001L, account(2001, "GOLD", "1000.00", "1000.00", "300.00", "Y"));
        files.xrefs.put(2001L, new CardXref("4000000000002001", 3001, 2001));
        addAccount(files, 2002, "GOLD", "100.00", "12.00");
        files.balances.add(balance(2001, "01", 1, "1000.00"));
        files.balances.add(balance(2002, "01", 1, "100.00"));

        run(files);

        assertThat(files.rewrites.getFirst().currentCycleCredit()).isEqualTo(money("0.00"));
        assertThat(files.rewrites.getFirst().currentCycleDebit()).isEqualTo(money("0.00"));
    }

    @Test
    @DisplayName("BR-INT-030 zero-rate-only accounts are still rewritten at a break")
    void brInt030_zeroRateAccountStillRewritten() {
        FakeInterestFiles files = new FakeInterestFiles();
        files.accounts.put(2001L, account(2001, "GOLD", "1000.00", "1000.00", "300.00", "Y"));
        files.xrefs.put(2001L, new CardXref("4000000000002001", 3001, 2001));
        addAccount(files, 2002, "GOLD", "100.00", "12.00");
        files.addDisclosure("GOLD", "02", 1, "0.00");
        files.balances.add(balance(2001, "02", 1, "1000.00"));
        files.balances.add(balance(2002, "01", 1, "100.00"));

        InterestRunOutcome outcome = run(files);

        assertThat(files.rewrites.getFirst().currentBalance()).isEqualTo(money("1000.00"));
        assertThat(files.rewrites.getFirst().currentCycleCredit()).isEqualTo(money("0.00"));
        assertThat(outcome.transactions().getFirst().description()).isEqualTo("Int. for a/c 00000002002");
    }

    @Test
    @DisplayName("BR-INT-031 account rewrite preserves all non-cycle fields")
    void brInt031_rewritePreservesUnmodifiedAccountFields() {
        FakeInterestFiles files = new FakeInterestFiles();
        Account original = new Account(2009, "Y", money("1000.33"), money("5000.00"),
                money("1200.00"), "2019-03-15", "2028-03-15", "2024-03-15",
                money("0.00"), money("300.00"), "10115", "STANDARD");
        files.accounts.put(2009L, original);
        files.xrefs.put(2009L, new CardXref("4000000000002009", 3009, 2009));
        files.addDisclosure("STANDARD", "01", 1, "18.50");
        addAccount(files, 2010, "DEFAULT", "100.00", "12.00");
        files.balances.add(balance(2009, "01", 1, "1000.33"));
        files.balances.add(balance(2010, "01", 1, "100.00"));

        run(files);
        Account rewritten = files.rewrites.getFirst();

        assertThat(rewritten.activeStatus()).isEqualTo(original.activeStatus());
        assertThat(rewritten.creditLimit()).isEqualTo(original.creditLimit());
        assertThat(rewritten.cashCreditLimit()).isEqualTo(original.cashCreditLimit());
        assertThat(rewritten.openDate()).isEqualTo(original.openDate());
        assertThat(rewritten.expirationDate()).isEqualTo(original.expirationDate());
        assertThat(rewritten.reissueDate()).isEqualTo(original.reissueDate());
        assertThat(rewritten.zip()).isEqualTo(original.zip());
        assertThat(rewritten.groupId()).isEqualTo(original.groupId());
    }

    @Test
    @DisplayName("BR-INT-032 inactive and expired accounts still accrue interest")
    void brInt032_noStatusOrDateEligibilityChecks() {
        FakeInterestFiles files = new FakeInterestFiles();
        files.accounts.put(2002L, new Account(2002, "N", money("1000.00"), money("5000.00"),
                money("1200.00"), "2000-01-01", "2001-01-01", "2001-01-01",
                money("1000.00"), money("300.00"), "10115", "GOLD"));
        files.xrefs.put(2002L, new CardXref("4000000000002002", 3002, 2002));
        files.addDisclosure("GOLD", "01", 1, "12.00");
        addAccount(files, 2003, "GOLD", "100.00", "12.00");
        files.balances.add(balance(2002, "01", 1, "-300.00"));
        files.balances.add(balance(2003, "01", 1, "100.00"));

        InterestRunOutcome outcome = run(files);

        assertThat(outcome.transactions().getFirst().amount()).isEqualTo(money("-3.00"));
        assertThat(files.rewrites.getFirst().currentBalance()).isEqualTo(money("997.00"));
        assertThat(files.rewrites.getFirst().currentCycleCredit()).isEqualTo(money("0.00"));
        assertThat(files.rewrites.getFirst().currentCycleDebit()).isEqualTo(money("0.00"));
    }

    @Test
    @DisplayName("BR-INT-033 account rewrite failures abend")
    void brInt033_rewriteFailureAbendsWithStatus() {
        FakeInterestFiles files = new FakeInterestFiles();
        addAccount(files, 2001, "GOLD", "1000.00", "12.00");
        addAccount(files, 2002, "GOLD", "100.00", "12.00");
        files.rewriteStatuses.put(2001L, "22");
        files.balances.add(balance(2001, "01", 1, "1000.00"));
        files.balances.add(balance(2002, "01", 1, "100.00"));

        InterestRunOutcome outcome = run(files);

        assertThat(outcome.sysoutLines()).contains(
                "ERROR RE-WRITING ACCOUNT FILE",
                "FILE STATUS IS: NNNN0022",
                "ABENDING PROGRAM");
        assertThat(outcome.abended()).isTrue();
    }

    @Test
    @DisplayName("BR-INT-034 the final account is not rewritten at EOF")
    void brInt034_lastAccountIsNeverUpdatedAtEof() {
        FakeInterestFiles files = new FakeInterestFiles();
        files.accounts.put(2001L, account(2001, "GOLD", "1000.00", "0.00", "0.00", "Y"));
        files.accounts.put(2002L, account(2002, "STANDARD", "2400.00", "2400.00", "2400.00", "Y"));
        files.xrefs.put(2001L, new CardXref("4000000000002001", 3001, 2001));
        files.xrefs.put(2002L, new CardXref("4000000000002002", 3002, 2002));
        files.addDisclosure("GOLD", "01", 1, "12.00");
        files.addDisclosure("STANDARD", "01", 1, "18.50");
        files.balances.add(balance(2001, "01", 1, "1000.00"));
        files.balances.add(balance(2002, "01", 1, "2400.00"));

        InterestRunOutcome outcome = run(files);

        assertThat(files.rewrites).extracting(Account::accountId).containsExactly(2001L);
        assertThat(outcome.transactions()).extracting(Transaction::amount)
                .containsExactly(money("10.00"), money("37.00"));
        assertThat(files.accounts.get(2002L).currentBalance()).isEqualTo(money("2400.00"));
        assertThat(files.accounts.get(2002L).currentCycleCredit()).isEqualTo(money("2400.00"));
    }

    @Test
    @DisplayName("BR-INT-035 single-account runs rewrite no account")
    void brInt035_singleAccountIsNotRewritten() {
        FakeInterestFiles files = new FakeInterestFiles();
        files.accounts.put(2007L, account(2007, "GOLD", "1500.00", "1500.00", "1500.00", "Y"));
        files.xrefs.put(2007L, new CardXref("4000000000002007", 3007, 2007));
        files.addDisclosure("GOLD", "01", 1, "12.00");
        files.addDisclosure("GOLD", "01", 2, "18.00");
        files.balances.add(balance(2007, "01", 1, "1000.00"));
        files.balances.add(balance(2007, "01", 2, "500.00"));

        InterestRunOutcome outcome = run(files);

        assertThat(outcome.transactions()).hasSize(2);
        assertThat(files.rewrites).isEmpty();
        assertThat(files.accounts.get(2007L).currentBalance()).isEqualTo(money("1500.00"));
        assertThat(files.accounts.get(2007L).currentCycleCredit()).isEqualTo(money("1500.00"));
    }

    @Test
    @DisplayName("BR-INT-036 transaction IDs use a run-wide six-digit sequence")
    void brInt036_transactionIdsUseRunWideSequence() {
        FakeInterestFiles files = new FakeInterestFiles();
        addAccount(files, 2001, "GOLD", "1000.00", "12.00");
        addAccount(files, 2002, "STANDARD", "2400.00", "18.50");
        files.balances.add(balance(2001, "01", 1, "1000.00"));
        files.balances.add(balance(2002, "01", 1, "2400.00"));

        InterestRunOutcome outcome = run(files);

        assertThat(outcome.transactions()).extracting(Transaction::id)
                .containsExactly("2022071800000001", "2022071800000002");
    }

    @Test
    @DisplayName("BR-INT-037 transaction fixed codes and merchant fields match the copybook")
    void brInt037_transactionHasFixedCodesAndBlankMerchantFields() {
        FakeInterestFiles files = new FakeInterestFiles();
        addAccount(files, 2001, "GOLD", "1000.00", "12.00");
        files.balances.add(balance(2001, "01", 1, "1000.00"));
        Transaction transaction = run(files).transactions().getFirst();
        byte[] record = InterestHandler.encodeTransaction(transaction);

        assertThat(new String(record, StandardCharsets.ISO_8859_1).substring(16, 32))
                .isEqualTo("010005System    ");
        assertThat(RecordCodec.decode("CVTRA05Y", record))
                .containsEntry("TRAN-MERCHANT-NAME", "")
                .containsEntry("TRAN-MERCHANT-CITY", "")
                .containsEntry("TRAN-MERCHANT-ZIP", "");
        assertThat(RecordCodec.decode("CVTRA05Y", record).get("TRAN-MERCHANT-ID"))
                .isEqualTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("BR-INT-038 transaction description includes the padded account id")
    void brInt038_transactionDescriptionContainsElevenDigitAccountId() {
        FakeInterestFiles files = new FakeInterestFiles();
        addAccount(files, 2001, "GOLD", "1000.00", "12.00");
        files.balances.add(balance(2001, "01", 1, "1000.00"));
        byte[] record = InterestHandler.encodeTransaction(run(files).transactions().getFirst());

        assertThat(RecordCodec.decode("CVTRA05Y", record))
                .containsEntry("TRAN-DESC", "Int. for a/c 00000002001");
    }

    @Test
    @DisplayName("BR-INT-039 each category writes its own monthly amount")
    void brInt039_eachCategoryWritesSeparateTransactionAmount() {
        FakeInterestFiles files = new FakeInterestFiles();
        addAccount(files, 2007, "GOLD", "1500.00", "12.00");
        files.addDisclosure("GOLD", "01", 2, "18.00");
        files.balances.add(balance(2007, "01", 1, "1000.00"));
        files.balances.add(balance(2007, "01", 2, "500.00"));

        InterestRunOutcome outcome = run(files);

        assertThat(outcome.transactions()).extracting(Transaction::amount)
                .containsExactly(money("10.00"), money("7.50"));
    }

    @Test
    @DisplayName("BR-INT-040 both transaction timestamps use the frozen clock")
    void brInt040_timestampsComeFromFrozenClock() {
        FakeInterestFiles files = new FakeInterestFiles();
        addAccount(files, 2001, "GOLD", "1000.00", "12.00");
        files.balances.add(balance(2001, "01", 1, "1000.00"));

        Transaction transaction = run(files).transactions().getFirst();

        assertThat(transaction.originalTimestamp()).isEqualTo("2022-07-05-10.00.00.000000");
        assertThat(transaction.processTimestamp()).isEqualTo(transaction.originalTimestamp());
    }

    @Test
    @DisplayName("BR-INT-041 SYSTRAN records use the 350-byte copybook")
    void brInt041_systranRecordUses350ByteLayout() {
        FakeInterestFiles files = new FakeInterestFiles();
        addAccount(files, 2007, "GOLD", "1500.00", "10.00");
        files.addDisclosure("GOLD", "01", 2, "18.00");
        files.balances.add(balance(2007, "01", 1, "1000.00"));
        files.balances.add(balance(2007, "01", 2, "500.00"));

        InterestRunOutcome outcome = run(files);

        assertThat(outcome.transactions()).hasSize(2);
        assertThat(outcome.transactions().stream().map(InterestHandler::encodeTransaction))
                .allSatisfy(record -> assertThat(record).hasSize(350));

        FakeInterestFiles failedWrite = new FakeInterestFiles();
        addAccount(failedWrite, 2001, "GOLD", "1000.00", "12.00");
        failedWrite.writeStatus = "28";
        failedWrite.balances.add(balance(2001, "01", 1, "1000.00"));
        InterestRunOutcome failedOutcome = run(failedWrite);
        assertThat(failedOutcome.sysoutLines()).contains(
                "ERROR WRITING TRANSACTION RECORD", "FILE STATUS IS: NNNN0028", "ABENDING PROGRAM");
    }

    @Test
    @DisplayName("BR-INT-042 fee computation remains an empty stub")
    void brInt042_noFeeTransactionsAreGenerated() {
        FakeInterestFiles files = new FakeInterestFiles();
        addAccount(files, 2001, "GOLD", "1000.00", "12.00");
        files.balances.add(balance(2001, "01", 1, "1000.00"));

        InterestRunOutcome outcome = run(files);

        assertThat(outcome.transactions()).hasSize(1);
        assertThat(outcome.transactions().getFirst().typeCd()).isEqualTo("01");
        assertThat(outcome.transactions().getFirst().categoryCd()).isEqualTo(5);
    }

    @Test
    @DisplayName("BR-INT-043 empty category input completes with only the banners")
    void brInt043_emptyInputCompletesWithoutWrites() {
        FakeInterestFiles files = new FakeInterestFiles();

        InterestRunOutcome outcome = run(files);

        assertThat(outcome.sysoutLines()).containsExactly(
                "START OF EXECUTION OF PROGRAM CBACT04C",
                "END OF EXECUTION OF PROGRAM CBACT04C");
        assertThat(outcome.transactions()).isEmpty();
        assertThat(files.rewrites).isEmpty();
        assertThat(outcome.stepRc()).isZero();
    }

    @Test
    @DisplayName("BR-INT-044 files close in order and close failures abend")
    void brInt044_closeOrderAndFailuresArePreserved() {
        FakeInterestFiles normal = new FakeInterestFiles();
        run(normal);
        assertThat(normal.events.subList(6, 11)).containsExactly(
                "close:TcatBal", "close:Xref", "close:DiscGrp", "close:Account", "close:Transaction");

        Map<String, String> messages = Map.of(
                "TcatBal", "ERROR CLOSING TRANSACTION BALANCE FILE",
                "Xref", "ERROR CLOSING CROSS REF FILE",
                "DiscGrp", "ERROR CLOSING DISCLOSURE GROUP FILE",
                "Account", "ERROR CLOSING ACCOUNT FILE",
                "Transaction", "ERROR CLOSING TRANSACTION FILE");
        for (String file : List.of("TcatBal", "Xref", "DiscGrp", "Account", "Transaction")) {
            FakeInterestFiles files = new FakeInterestFiles();
            files.closeStatuses.put(file, "22");

            InterestRunOutcome outcome = run(files);

            assertThat(outcome.abended()).isTrue();
            assertThat(outcome.sysoutLines()).contains(messages.get(file), "FILE STATUS IS: NNNN0022");
            assertThat(outcome.sysoutLines()).doesNotContain("END OF EXECUTION OF PROGRAM CBACT04C");
        }
    }

    @Test
    @DisplayName("BR-INT-045 file status retains the literal NNNN prefix")
    void brInt045_fileStatusUsesLegacyFourCharacterFormatting() {
        assertThat(InterestCalculationService.displayStatusValue("23")).isEqualTo("0023");
        assertThat(InterestCalculationService.displayStatusValue("9A")).isEqualTo("9065");

        FakeInterestFiles files = new FakeInterestFiles();
        files.balances.add(balance(3001, "01", 1, "1000.00"));
        InterestRunOutcome outcome = run(files);
        assertThat(outcome.sysoutLines()).contains("FILE STATUS IS: NNNN0023");
    }

    @Test
    @DisplayName("BR-INT-046 abend keeps earlier rewrites and transactions without end banner")
    void brInt046_abendDoesNotRollBackPriorWrites() {
        FakeInterestFiles files = new FakeInterestFiles();
        addAccount(files, 2001, "GOLD", "1000.00", "12.00");
        files.balances.add(balance(2001, "01", 1, "1000.00"));
        files.balances.add(balance(3001, "01", 1, "100.00"));

        InterestRunOutcome outcome = run(files);

        assertThat(files.rewrites).hasSize(1);
        assertThat(files.rewrites.getFirst().currentBalance()).isEqualTo(money("1010.00"));
        assertThat(outcome.transactions()).hasSize(1);
        assertThat(outcome.stepRc()).isEqualTo(12);
        assertThat(outcome.abended()).isTrue();
        assertThat(outcome.sysoutLines()).endsWith(
                "ABENDING PROGRAM", "CEE3ABD: USER ABEND U+0000000999");
        assertThat(outcome.sysoutLines()).doesNotContain("END OF EXECUTION OF PROGRAM CBACT04C");
    }

    @Test
    @DisplayName("BR-INT-047 successful runs return RC zero")
    void brInt047_normalCompletionReturnsZero() {
        FakeInterestFiles files = new FakeInterestFiles();
        addAccount(files, 2006, "PLATINUM", "1000.00", "0.00");
        files.addDisclosure("DEFAULT", "01", 1, "21.00");
        files.balances.add(balance(2006, "01", 1, "1000.00"));

        InterestRunOutcome outcome = run(files);

        assertThat(outcome.stepRc()).isZero();
        assertThat(outcome.abended()).isFalse();
        assertThat(outcome.sysoutLines().getLast()).isEqualTo("END OF EXECUTION OF PROGRAM CBACT04C");
    }

    private static InterestRunOutcome run(FakeInterestFiles files) {
        return new InterestCalculationService().run(files, InterestHandler.PARM, CURRENT_DATE);
    }

    private static void addAccount(
            FakeInterestFiles files, long id, String group, String balance, String rate) {
        files.accounts.put(id, account(id, group, balance, "5000.00", "1200.00", "Y"));
        files.xrefs.put(id, new CardXref(String.format("%016d", 4_000_000_000_000_000L + id), 3001, id));
        files.addDisclosure(group, "01", 1, rate);
    }

    private static Account account(
            long id,
            String group,
            String balance,
            String credit,
            String cycleCredit,
            String activeStatus) {
        return new Account(id, activeStatus, money(balance), money(credit), money("0.00"),
                "2019-03-15", "2028-03-15", "2024-03-15", money(cycleCredit),
                money("300.00"), "10115", group);
    }

    private static TranCatBal balance(long accountId, String typeCd, int categoryCd, String amount) {
        return new TranCatBal(accountId, typeCd, categoryCd, money(amount));
    }

    private static DisclosureGroup disclosure(String group, String type, int category, String rate) {
        return new DisclosureGroup(group, type, category, money(rate));
    }

    private static BigDecimal money(String amount) {
        return new BigDecimal(amount).setScale(2);
    }

    private static String key(String group, String type, int category) {
        return group + "|" + type + "|" + category;
    }

    private static final class FakeInterestFiles implements InterestFiles {
        private final List<TranCatBal> balances = new ArrayList<>();
        private final Map<Long, Account> accounts = new HashMap<>();
        private final Map<Long, CardXref> xrefs = new HashMap<>();
        private final Map<String, DisclosureGroup> disclosures = new HashMap<>();
        private final Map<String, String> openStatuses = new HashMap<>();
        private final Map<String, String> closeStatuses = new HashMap<>();
        private final Map<Long, String> accountStatuses = new HashMap<>();
        private final Map<Long, String> xrefStatuses = new HashMap<>();
        private final Map<String, String> disclosureStatuses = new HashMap<>();
        private final Map<Long, String> rewriteStatuses = new HashMap<>();
        private final List<String> readStatuses = new ArrayList<>();
        private final List<String> events = new ArrayList<>();
        private final List<String> disclosureCalls = new ArrayList<>();
        private final List<Account> rewrites = new ArrayList<>();
        private final List<Transaction> writtenTransactions = new ArrayList<>();
        private String writeStatus = "00";
        private int readIndex;

        private void addDisclosure(String group, String type, int category, String rate) {
            disclosures.put(key(group, type, category), disclosure(group, type, category, rate));
        }

        private String open(String file) {
            events.add("open:" + file);
            return openStatuses.getOrDefault(file, "00");
        }

        private String close(String file) {
            events.add("close:" + file);
            return closeStatuses.getOrDefault(file, "00");
        }

        @Override
        public String openTcatBal() {
            return open("TcatBal");
        }

        @Override
        public String openXref() {
            return open("Xref");
        }

        @Override
        public String openDiscGrp() {
            return open("DiscGrp");
        }

        @Override
        public String openAccount() {
            return open("Account");
        }

        @Override
        public String openTransaction() {
            return open("Transaction");
        }

        @Override
        public ReadResult<TranCatBal> readNextTcatBal() {
            events.add("read:TcatBal");
            int index = readIndex++;
            String status = index < readStatuses.size() ? readStatuses.get(index) : "00";
            if (!"00".equals(status)) {
                return new ReadResult<>(status, null);
            }
            return index < balances.size()
                    ? new ReadResult<>("00", balances.get(index))
                    : new ReadResult<>("10", null);
        }

        @Override
        public ReadResult<Account> readAccount(long accountId) {
            events.add("account:" + accountId);
            String status = accountStatuses.get(accountId);
            if (status != null && !"00".equals(status)) {
                return new ReadResult<>(status, null);
            }
            Account account = accounts.get(accountId);
            return account == null
                    ? new ReadResult<>("23", null)
                    : new ReadResult<>("00", account);
        }

        @Override
        public ReadResult<CardXref> readXrefByAccount(long accountId) {
            events.add("xref:" + accountId);
            String status = xrefStatuses.get(accountId);
            if (status != null && !"00".equals(status)) {
                return new ReadResult<>(status, null);
            }
            CardXref xref = xrefs.get(accountId);
            return xref == null
                    ? new ReadResult<>("23", null)
                    : new ReadResult<>("00", xref);
        }

        @Override
        public ReadResult<DisclosureGroup> readDiscGrp(String groupId, String typeCd, int categoryCd) {
            String disclosureKey = key(groupId, typeCd, categoryCd);
            events.add("disclosure:" + disclosureKey);
            disclosureCalls.add(disclosureKey);
            String status = disclosureStatuses.get(disclosureKey);
            if (status != null && !"00".equals(status)) {
                return new ReadResult<>(status, null);
            }
            DisclosureGroup disclosure = disclosures.get(disclosureKey);
            return disclosure == null
                    ? new ReadResult<>("23", null)
                    : new ReadResult<>("00", disclosure);
        }

        @Override
        public String rewriteAccount(Account account) {
            events.add("rewrite:" + account.accountId());
            String status = rewriteStatuses.getOrDefault(account.accountId(), "00");
            if ("00".equals(status)) {
                accounts.put(account.accountId(), account);
                rewrites.add(account);
            }
            return status;
        }

        @Override
        public String writeTransaction(Transaction transaction) {
            events.add("write:Transaction");
            if ("00".equals(writeStatus)) {
                writtenTransactions.add(transaction);
            }
            return writeStatus;
        }

        @Override
        public String closeTcatBal() {
            return close("TcatBal");
        }

        @Override
        public String closeXref() {
            return close("Xref");
        }

        @Override
        public String closeDiscGrp() {
            return close("DiscGrp");
        }

        @Override
        public String closeAccount() {
            return close("Account");
        }

        @Override
        public String closeTransaction() {
            return close("Transaction");
        }
    }
}
