package com.carddemo.interest;

import com.carddemo.common.codec.RecordCodec;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.carddemo.interest.InterestFiles.Account;
import static com.carddemo.interest.InterestFiles.CardXref;
import static com.carddemo.interest.InterestFiles.DisclosureGroup;
import static com.carddemo.interest.InterestFiles.ReadResult;
import static com.carddemo.interest.InterestFiles.TranCatBal;
import static com.carddemo.interest.InterestFiles.Transaction;

@Component
public class InterestCalculationService {
    private static final String START_BANNER = "START OF EXECUTION OF PROGRAM CBACT04C";
    private static final String END_BANNER = "END OF EXECUTION OF PROGRAM CBACT04C";
    private static final String ABEND_MESSAGE = "ABENDING PROGRAM";
    private static final String ABEND_DETAIL = "CEE3ABD: USER ABEND U+0000000999";
    private static final BigDecimal MONTHLY_LIMIT = new BigDecimal("1000000000");
    private static final BigDecimal ACCOUNT_LIMIT = new BigDecimal("10000000000");

    public InterestRunOutcome run(InterestFiles files, String parmDate, String cobolCurrentDate) {
        Run run = new Run(files, parmDate, cobolCurrentDate);
        try {
            return run.execute();
        } catch (AbendException ignored) {
            return run.outcome(12, true);
        }
    }

    private static final class Run {
        private final InterestFiles files;
        private final String parmDate;
        private final String timestamp;
        private final List<String> sysout = new ArrayList<>();
        private final List<Transaction> transactions = new ArrayList<>();
        private long lastAccountId;
        private boolean firstTime = true;
        private BigDecimal totalInterest = BigDecimal.ZERO.setScale(2);
        private int transactionSuffix;
        private Account account;
        private CardXref xref;

        private Run(InterestFiles files, String parmDate, String cobolCurrentDate) {
            this.files = files;
            this.parmDate = parmDate;
            this.timestamp = db2Timestamp(cobolCurrentDate);
        }

        private InterestRunOutcome execute() {
            sysout.add(START_BANNER);
            open(files.openTcatBal(), "ERROR OPENING TRANSACTION CATEGORY BALANCE");
            String xrefStatus = files.openXref();
            if (!"00".equals(xrefStatus)) {
                sysout.add("ERROR OPENING CROSS REF FILE" + statusBytes(xrefStatus));
                displayStatus(xrefStatus);
                abend();
            }
            open(files.openDiscGrp(), "ERROR OPENING DALY REJECTS FILE");
            open(files.openAccount(), "ERROR OPENING ACCOUNT MASTER FILE");
            open(files.openTransaction(), "ERROR OPENING TRANSACTION FILE");

            while (true) {
                ReadResult<TranCatBal> result = files.readNextTcatBal();
                if ("10".equals(result.status())) {
                    break;
                }
                if (!"00".equals(result.status())) {
                    fail("ERROR READING TRANSACTION CATEGORY FILE", result.status());
                }

                TranCatBal balance = result.record();
                sysout.add(rawTranCatBalance(balance));
                if (balance.accountId() != lastAccountId) {
                    if (!firstTime) {
                        updateAccount();
                    } else {
                        firstTime = false;
                    }
                    totalInterest = BigDecimal.ZERO.setScale(2);
                    lastAccountId = balance.accountId();
                    account = readAccount(balance.accountId());
                    xref = readXref(balance.accountId());
                }

                DisclosureGroup disclosure = readDisclosure(balance);
                if (disclosure.interestRate().signum() != 0) {
                    computeInterest(balance, disclosure);
                }
            }

            close(files.closeTcatBal(), "ERROR CLOSING TRANSACTION BALANCE FILE");
            close(files.closeXref(), "ERROR CLOSING CROSS REF FILE");
            close(files.closeDiscGrp(), "ERROR CLOSING DISCLOSURE GROUP FILE");
            close(files.closeAccount(), "ERROR CLOSING ACCOUNT FILE");
            close(files.closeTransaction(), "ERROR CLOSING TRANSACTION FILE");
            sysout.add(END_BANNER);
            return outcome(0, false);
        }

        private void open(String status, String message) {
            if (!"00".equals(status)) {
                fail(message, status);
            }
        }

        private void close(String status, String message) {
            if (!"00".equals(status)) {
                fail(message, status);
            }
        }

        private Account readAccount(long accountId) {
            ReadResult<Account> result = files.readAccount(accountId);
            if (!"00".equals(result.status())) {
                if ("23".equals(result.status())) {
                    sysout.add("ACCOUNT NOT FOUND: " + accountId(accountId));
                }
                fail("ERROR READING ACCOUNT FILE", result.status());
            }
            return result.record();
        }

        private CardXref readXref(long accountId) {
            ReadResult<CardXref> result = files.readXrefByAccount(accountId);
            if (!"00".equals(result.status())) {
                if ("23".equals(result.status())) {
                    sysout.add("ACCOUNT NOT FOUND: " + accountId(accountId));
                }
                fail("ERROR READING XREF FILE", result.status());
            }
            return result.record();
        }

        private DisclosureGroup readDisclosure(TranCatBal balance) {
            ReadResult<DisclosureGroup> result =
                    files.readDiscGrp(account.groupId(), balance.typeCd(), balance.categoryCd());
            if ("23".equals(result.status())) {
                sysout.add("DISCLOSURE GROUP RECORD MISSING");
                sysout.add("TRY WITH DEFAULT GROUP CODE");
                result = files.readDiscGrp("DEFAULT", balance.typeCd(), balance.categoryCd());
                if (!"00".equals(result.status())) {
                    fail("ERROR READING DEFAULT DISCLOSURE GROUP", result.status());
                }
            } else if (!"00".equals(result.status())) {
                fail("ERROR READING DISCLOSURE GROUP FILE", result.status());
            }
            return result.record();
        }

        private void computeInterest(TranCatBal balance, DisclosureGroup disclosure) {
            BigDecimal monthlyInterest = balance.balance().multiply(disclosure.interestRate())
                    .divide(BigDecimal.valueOf(1200), 2, RoundingMode.DOWN)
                    .remainder(MONTHLY_LIMIT)
                    .setScale(2, RoundingMode.DOWN);
            totalInterest = totalInterest.add(monthlyInterest)
                    .remainder(MONTHLY_LIMIT)
                    .setScale(2, RoundingMode.DOWN);

            transactionSuffix = (transactionSuffix + 1) % 1_000_000;
            Transaction transaction = new Transaction(
                    parmDate + String.format(Locale.ROOT, "%06d", transactionSuffix),
                    "01",
                    5,
                    "System",
                    "Int. for a/c " + accountId(account.accountId()),
                    monthlyInterest,
                    0,
                    "",
                    "",
                    "",
                    xref.cardNumber(),
                    timestamp,
                    timestamp);
            String writeStatus = files.writeTransaction(transaction);
            if (!"00".equals(writeStatus)) {
                fail("ERROR WRITING TRANSACTION RECORD", writeStatus);
            }
            transactions.add(transaction);
        }

        private void updateAccount() {
            BigDecimal newBalance = account.currentBalance().add(totalInterest)
                    .remainder(ACCOUNT_LIMIT)
                    .setScale(2, RoundingMode.DOWN);
            Account updated = new Account(
                    account.accountId(),
                    account.activeStatus(),
                    newBalance,
                    account.creditLimit(),
                    account.cashCreditLimit(),
                    account.openDate(),
                    account.expirationDate(),
                    account.reissueDate(),
                    BigDecimal.ZERO.setScale(2),
                    BigDecimal.ZERO.setScale(2),
                    account.zip(),
                    account.groupId());
            String status = files.rewriteAccount(updated);
            if (!"00".equals(status)) {
                fail("ERROR RE-WRITING ACCOUNT FILE", status);
            }
            account = updated;
        }

        private void fail(String message, String status) {
            sysout.add(message);
            displayStatus(status);
            abend();
        }

        private void displayStatus(String status) {
            sysout.add("FILE STATUS IS: NNNN" + displayStatusValue(status));
        }

        private InterestRunOutcome outcome(int stepRc, boolean abended) {
            return new InterestRunOutcome(sysout, stepRc, abended, transactions);
        }

        private void abend() {
            sysout.add(ABEND_MESSAGE);
            sysout.add(ABEND_DETAIL);
            throw AbendException.INSTANCE;
        }

        private static String rawTranCatBalance(TranCatBal balance) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("TRANCAT-ACCT-ID", balance.accountId());
            values.put("TRANCAT-TYPE-CD", balance.typeCd());
            values.put("TRANCAT-CD", balance.categoryCd());
            values.put("TRAN-CAT-BAL", balance.balance());
            return new String(RecordCodec.encode("CVTRA01Y", values), StandardCharsets.ISO_8859_1);
        }
    }

    static String displayStatusValue(String status) {
        String value = statusBytes(status);
        if (value.matches("[0-9]{2}") && value.charAt(0) != '9') {
            return "00" + value;
        }
        return value.charAt(0) + String.format(Locale.ROOT, "%03d", (int) value.charAt(1));
    }

    private static String statusBytes(String status) {
        if (status == null) {
            return "  ";
        }
        if (status.length() == 1) {
            return status + " ";
        }
        return status.substring(0, 2);
    }

    private static String db2Timestamp(String cobolCurrentDate) {
        return String.format(Locale.ROOT, "%s-%s-%s-%s.%s.%s.%s0000",
                cobolCurrentDate.substring(0, 4),
                cobolCurrentDate.substring(4, 6),
                cobolCurrentDate.substring(6, 8),
                cobolCurrentDate.substring(8, 10),
                cobolCurrentDate.substring(10, 12),
                cobolCurrentDate.substring(12, 14),
                cobolCurrentDate.substring(14, 16));
    }

    private static String accountId(long accountId) {
        return String.format(Locale.ROOT, "%011d", accountId);
    }

    private static final class AbendException extends RuntimeException {
        private static final AbendException INSTANCE = new AbendException();

        private AbendException() {
            super(null, null, false, false);
        }
    }
}
