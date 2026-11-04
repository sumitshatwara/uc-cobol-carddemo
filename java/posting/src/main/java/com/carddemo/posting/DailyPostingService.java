package com.carddemo.posting;

import com.carddemo.common.codec.RecordCodec;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class DailyPostingService {
    private static final String START = "START OF EXECUTION OF PROGRAM CBTRN02C";
    private static final String END = "END OF EXECUTION OF PROGRAM CBTRN02C";
    private static final Map<Integer, String> REASONS = Map.of(
            100, "INVALID CARD NUMBER FOUND",
            101, "ACCOUNT RECORD NOT FOUND",
            102, "OVERLIMIT TRANSACTION",
            103, "TRANSACTION RECEIVED AFTER ACCT EXPIRATION",
            109, "ACCOUNT RECORD NOT FOUND");
    private static final List<String> IO_ERRORS = List.of(
            "ERROR OPENING DALYTRAN",
            "ERROR OPENING TRANSACTION FILE",
            "ERROR OPENING CROSS REF FILE",
            "ERROR OPENING DALY REJECTS FILE",
            "ERROR OPENING ACCOUNT MASTER FILE",
            "ERROR OPENING TRANSACTION BALANCE FILE",
            "ERROR READING DALYTRAN FILE",
            "ERROR WRITING TO REJECTS FILE",
            "ERROR CLOSING DALYTRAN FILE",
            "ERROR CLOSING TRANSACTION FILE",
            "ERROR CLOSING CROSS REF FILE",
            "ERROR CLOSING DAILY REJECTS FILE",
            "ERROR CLOSING ACCOUNT FILE",
            "ERROR CLOSING TRANSACTION BALANCE FILE");

    private final PostingStore store;

    public DailyPostingService(PostingStore store) {
        this.store = store;
    }

    public Result post(List<byte[]> rawRecords, String cobolCurrentDate) {
        store.truncateTransactions();
        List<String> sysout = new ArrayList<>();
        List<byte[]> rejects = new ArrayList<>();
        sysout.add(START);
        int processed = 0;
        int rejected = 0;

        for (byte[] source : rawRecords) {
            byte[] raw = fixedRecord(source, 350);
            Map<String, Object> daily = RecordCodec.decode("CVTRA06Y", raw);
            processed++;
            Validation validation = validate(daily);
            if (validation.reason() != 0) {
                rejected++;
                rejects.add(rejectRecord(raw, validation.reason()));
                continue;
            }

            BigDecimal accountId = validation.accountId();
            BigDecimal amount = decimal(daily.get("DALYTRAN-AMT"));
            String typeCode = text(daily.get("DALYTRAN-TYPE-CD"));
            BigDecimal categoryCode = decimal(daily.get("DALYTRAN-CAT-CD"));
            Optional<BigDecimal> categoryBalance = store.categoryBalance(accountId, typeCode, categoryCode);
            if (categoryBalance.isPresent()) {
                store.updateCategoryBalance(accountId, typeCode, categoryCode,
                        CobolFormat.truncateHighOrder(categoryBalance.get().add(amount), 9));
            } else {
                String key = CobolFormat.zeroPad(accountId, 11) + CobolFormat.rightPad(typeCode, 2)
                        + CobolFormat.zeroPad(categoryCode, 4);
                sysout.add("TCATBAL record not found for key : " + key + ".. Creating.");
                store.createCategoryBalance(accountId, typeCode, categoryCode,
                        CobolFormat.truncateHighOrder(amount, 9));
            }

            PostingStore.Account account = validation.account();
            BigDecimal balance = CobolFormat.truncateHighOrder(account.currentBalance().add(amount), 10);
            BigDecimal cycleCredit = account.cycleCredit();
            BigDecimal cycleDebit = account.cycleDebit();
            if (amount.signum() >= 0) {
                cycleCredit = CobolFormat.truncateHighOrder(cycleCredit.add(amount), 10);
            } else {
                cycleDebit = CobolFormat.truncateHighOrder(cycleDebit.add(amount), 10);
            }
            store.updateAccount(account.withBalances(balance, cycleCredit, cycleDebit));

            Map<String, Object> transaction = new LinkedHashMap<>();
            transaction.put("TRAN-ID", daily.get("DALYTRAN-ID"));
            transaction.put("TRAN-TYPE-CD", daily.get("DALYTRAN-TYPE-CD"));
            transaction.put("TRAN-CAT-CD", daily.get("DALYTRAN-CAT-CD"));
            transaction.put("TRAN-SOURCE", daily.get("DALYTRAN-SOURCE"));
            transaction.put("TRAN-DESC", daily.get("DALYTRAN-DESC"));
            transaction.put("TRAN-AMT", daily.get("DALYTRAN-AMT"));
            transaction.put("TRAN-MERCHANT-ID", daily.get("DALYTRAN-MERCHANT-ID"));
            transaction.put("TRAN-MERCHANT-NAME", daily.get("DALYTRAN-MERCHANT-NAME"));
            transaction.put("TRAN-MERCHANT-CITY", daily.get("DALYTRAN-MERCHANT-CITY"));
            transaction.put("TRAN-MERCHANT-ZIP", daily.get("DALYTRAN-MERCHANT-ZIP"));
            transaction.put("TRAN-CARD-NUM", daily.get("DALYTRAN-CARD-NUM"));
            transaction.put("TRAN-ORIG-TS", daily.get("DALYTRAN-ORIG-TS"));
            transaction.put("TRAN-PROC-TS", CobolFormat.db2Timestamp(cobolCurrentDate));
            try {
                store.insertTransaction(transaction);
            } catch (PostingStore.DuplicateTransactionException exception) {
                sysout.addAll(CobolFormat.abendLines("ERROR WRITING TO TRANSACTION FILE", "22"));
                return new Result(sysout, 12, true, rejects, processed, rejected);
            }
        }

        sysout.add("TRANSACTIONS PROCESSED :" + String.format("%09d", processed));
        sysout.add("TRANSACTIONS REJECTED  :" + String.format("%09d", rejected));
        sysout.add(END);
        return new Result(sysout, rejected == 0 ? 0 : 4, false, rejects, processed, rejected);
    }

    public static List<String> ioErrorMessages() {
        return IO_ERRORS;
    }

    public static List<String> ioError(String message, String status) {
        return CobolFormat.abendLines(message, status);
    }

    public static List<String> dalyrejsCloseError(String dalyrejsStatus, String xrefStatus) {
        return ioError("ERROR CLOSING DAILY REJECTS FILE", xrefStatus);
    }

    private Validation validate(Map<String, Object> daily) {
        String cardNumber = text(daily.get("DALYTRAN-CARD-NUM"));
        Optional<BigDecimal> accountId = store.accountIdForCard(cardNumber);
        if (accountId.isEmpty()) {
            return new Validation(null, null, 100);
        }

        Optional<PostingStore.Account> account = store.account(accountId.get());
        if (account.isEmpty()) {
            return new Validation(accountId.get(), null, 101);
        }

        BigDecimal amount = decimal(daily.get("DALYTRAN-AMT"));
        BigDecimal temporaryBalance = CobolFormat.truncateHighOrder(
                account.get().cycleCredit().subtract(account.get().cycleDebit()).add(amount), 9);
        int reason = account.get().creditLimit().compareTo(temporaryBalance) < 0 ? 102 : 0;
        String expiration = CobolFormat.rightPad(account.get().expirationDate(), 10);
        String transactionDate = CobolFormat.rightPad(text(daily.get("DALYTRAN-ORIG-TS")).substring(0, 10), 10);
        if (expiration.compareTo(transactionDate) < 0) {
            reason = 103;
        }
        return new Validation(accountId.get(), account.get(), reason);
    }

    private static byte[] rejectRecord(byte[] raw, int reason) {
        byte[] trailer = (String.format("%04d", reason)
                + CobolFormat.rightPad(REASONS.get(reason), 76)).getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        byte[] rejected = new byte[430];
        System.arraycopy(raw, 0, rejected, 0, raw.length);
        System.arraycopy(trailer, 0, rejected, raw.length, trailer.length);
        return rejected;
    }

    private static byte[] fixedRecord(byte[] source, int length) {
        byte[] result = new byte[length];
        java.util.Arrays.fill(result, (byte) ' ');
        System.arraycopy(source, 0, result, 0, Math.min(source.length, length));
        return result;
    }

    private static BigDecimal decimal(Object value) {
        return value instanceof BigDecimal number ? number : new BigDecimal(String.valueOf(value));
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private record Validation(BigDecimal accountId, PostingStore.Account account, int reason) {
    }

    public record Result(List<String> sysout, int returnCode, boolean abended, List<byte[]> rejects,
                         int processed, int rejected) {
        public Result {
            sysout = List.copyOf(sysout);
            rejects = rejects.stream().map(byte[]::clone).toList();
        }
    }
}
