package com.carddemo.interest;

import com.carddemo.common.codec.RecordCodec;
import com.carddemo.common.export.JobRc;
import com.carddemo.common.run.ChainHandler;
import com.carddemo.common.run.RunContext;
import com.carddemo.common.run.RunResult;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

import static com.carddemo.interest.InterestFiles.Account;
import static com.carddemo.interest.InterestFiles.Transaction;

@Component
public class InterestHandler implements ChainHandler {
    public static final String PARM = "2022071800";

    private final InterestCalculationService calculationService;

    public InterestHandler(InterestCalculationService calculationService) {
        this.calculationService = calculationService;
    }

    @Override
    public String chain() {
        return "interest";
    }

    @Override
    public RunResult run(RunContext context) {
        JdbcInterestFiles files = new JdbcInterestFiles(context.jdbcTemplate());
        InterestRunOutcome outcome =
                calculationService.run(files, PARM, context.clock().cobolCurrentDate());

        context.outputWriter().writeSysout("INTCALC", "STEP15", outcome.sysoutLines());
        List<byte[]> transactions = outcome.transactions().stream()
                .map(InterestHandler::encodeTransaction)
                .toList();
        context.outputWriter().writeDataset(
                context.outputWriter().gdgName("AWS.M2.CARDDEMO.SYSTRAN", 1),
                "CVTRA05Y",
                350,
                transactions);

        List<byte[]> accounts = files.accountsInOrder().stream()
                .map(InterestHandler::encodeAccount)
                .toList();
        context.outputWriter().writeDataset(
                "AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS",
                "CVACT01Y",
                300,
                accounts);

        SortedMap<String, Integer> steps = new TreeMap<>();
        steps.put("STEP05", 0);
        steps.put("STEP10", 0);
        steps.put("STEP15", outcome.stepRc());
        steps.put("STEP20", 0);
        steps.put("STEP25", 0);
        steps.put("STEP30", 0);
        return new RunResult(Map.of("INTCALC", new JobRc(outcome.stepRc(), steps)));
    }

    static byte[] encodeTransaction(Transaction transaction) {
        return RecordCodec.encode("CVTRA05Y", Map.ofEntries(
                Map.entry("TRAN-ID", transaction.id()),
                Map.entry("TRAN-TYPE-CD", transaction.typeCd()),
                Map.entry("TRAN-CAT-CD", transaction.categoryCd()),
                Map.entry("TRAN-SOURCE", transaction.source()),
                Map.entry("TRAN-DESC", transaction.description()),
                Map.entry("TRAN-AMT", transaction.amount()),
                Map.entry("TRAN-MERCHANT-ID", transaction.merchantId()),
                Map.entry("TRAN-MERCHANT-NAME", transaction.merchantName()),
                Map.entry("TRAN-MERCHANT-CITY", transaction.merchantCity()),
                Map.entry("TRAN-MERCHANT-ZIP", transaction.merchantZip()),
                Map.entry("TRAN-CARD-NUM", transaction.cardNumber()),
                Map.entry("TRAN-ORIG-TS", transaction.originalTimestamp()),
                Map.entry("TRAN-PROC-TS", transaction.processTimestamp())));
    }

    static byte[] encodeAccount(Account account) {
        return RecordCodec.encode("CVACT01Y", Map.ofEntries(
                Map.entry("ACCT-ID", account.accountId()),
                Map.entry("ACCT-ACTIVE-STATUS", account.activeStatus()),
                Map.entry("ACCT-CURR-BAL", account.currentBalance()),
                Map.entry("ACCT-CREDIT-LIMIT", account.creditLimit()),
                Map.entry("ACCT-CASH-CREDIT-LIMIT", account.cashCreditLimit()),
                Map.entry("ACCT-OPEN-DATE", account.openDate()),
                Map.entry("ACCT-EXPIRAION-DATE", account.expirationDate()),
                Map.entry("ACCT-REISSUE-DATE", account.reissueDate()),
                Map.entry("ACCT-CURR-CYC-CREDIT", account.currentCycleCredit()),
                Map.entry("ACCT-CURR-CYC-DEBIT", account.currentCycleDebit()),
                Map.entry("ACCT-ADDR-ZIP", account.zip()),
                Map.entry("ACCT-GROUP-ID", account.groupId())));
    }
}
