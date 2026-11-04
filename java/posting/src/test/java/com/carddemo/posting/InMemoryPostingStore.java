package com.carddemo.posting;

import com.carddemo.common.codec.RecordCodec;
import com.carddemo.common.estate.EstateRoot;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class InMemoryPostingStore implements PostingStore, ReportLookups {
    private final Map<String, BigDecimal> xrefs = new LinkedHashMap<>();
    private final Map<String, Account> accounts = new LinkedHashMap<>();
    private final Map<CategoryKey, BigDecimal> categoryBalances = new LinkedHashMap<>();
    private final Map<String, Map<String, Object>> transactions = new LinkedHashMap<>();
    private final Map<String, String> typeDescriptions = new LinkedHashMap<>();
    private final Map<CategoryKey, String> categoryDescriptions = new LinkedHashMap<>();
    private final List<String> writeOrder = new ArrayList<>();
    private final List<String> transactionWriteOrder = new ArrayList<>();
    private boolean ignoreAccountRewrite;
    private int accountLookupCount;

    static InMemoryPostingStore defaults() {
        InMemoryPostingStore store = new InMemoryPostingStore();
        store.addXref("4000000000001001", bd("1001"));
        store.addAccount(new Account(bd("1001"), "Y", bd("500.00"), bd("1000.00"), bd("50.00"),
                "2022-12-31", bd("0.00"), bd("0.00")));
        store.addCategoryBalance(bd("1001"), "01", bd("1"), bd("500.00"));
        store.typeDescriptions.put("01", "Purchase");
        store.categoryDescriptions.put(new CategoryKey("0", "01", "1"), "Retail");
        return store;
    }

    static InMemoryPostingStore fromFixture(String caseName) {
        InMemoryPostingStore store = new InMemoryPostingStore();
        Path input = EstateRoot.root().resolve("fixtures/posting").resolve(caseName).resolve("input");
        readDataset(input.resolve("AWS.M2.CARDDEMO.CARDXREF.PS.txt"), 50, "CVACT03Y")
                .forEach(row -> store.addXref(text(row.get("XREF-CARD-NUM")), decimal(row.get("XREF-ACCT-ID"))));
        readDataset(input.resolve("AWS.M2.CARDDEMO.ACCTDATA.PS.txt"), 300, "CVACT01Y")
                .forEach(row -> store.addAccount(new Account(
                        decimal(row.get("ACCT-ID")),
                        text(row.get("ACCT-ACTIVE-STATUS")),
                        decimal(row.get("ACCT-CURR-BAL")),
                        decimal(row.get("ACCT-CREDIT-LIMIT")),
                        decimal(row.get("ACCT-CASH-CREDIT-LIMIT")),
                        text(row.get("ACCT-EXPIRAION-DATE")),
                        decimal(row.get("ACCT-CURR-CYC-CREDIT")),
                        decimal(row.get("ACCT-CURR-CYC-DEBIT")))));
        readDataset(input.resolve("AWS.M2.CARDDEMO.TCATBALF.PS.txt"), 50, "CVTRA01Y")
                .forEach(row -> store.addCategoryBalance(
                        decimal(row.get("TRANCAT-ACCT-ID")),
                        text(row.get("TRANCAT-TYPE-CD")),
                        decimal(row.get("TRANCAT-CD")),
                        decimal(row.get("TRAN-CAT-BAL"))));
        readDataset(input.resolve("AWS.M2.CARDDEMO.TRANTYPE.PS.txt"), 60, "CVTRA03Y")
                .forEach(row -> store.typeDescriptions.put(
                        text(row.get("TRAN-TYPE")), text(row.get("TRAN-TYPE-DESC"))));
        readDataset(input.resolve("AWS.M2.CARDDEMO.TRANCATG.PS.txt"), 60, "CVTRA04Y")
                .forEach(row -> store.categoryDescriptions.put(
                        new CategoryKey("0", text(row.get("TRAN-TYPE-CD")), key(decimal(row.get("TRAN-CAT-CD")))),
                        text(row.get("TRAN-CAT-TYPE-DESC"))));
        readDataset(input.resolve("AWS.M2.CARDDEMO.DALYTRAN.PS.INIT.txt"), 350, "CVTRA05Y")
                .forEach(store::addTransaction);
        return store;
    }

    static List<byte[]> dailyInput(String caseName) {
        return readRecords(EstateRoot.root().resolve("fixtures/posting").resolve(caseName)
                .resolve("input/AWS.M2.CARDDEMO.DALYTRAN.PS.txt"), 350);
    }

    static List<byte[]> dateParm(String caseName) {
        return readRecords(EstateRoot.root().resolve("fixtures/posting").resolve(caseName)
                .resolve("input/AWS.M2.CARDDEMO.DATEPARM.txt"), 80);
    }

    static byte[] daily(String id, String cardNumber, String typeCode, int categoryCode,
                        String amount, String originDate) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("DALYTRAN-ID", id);
        row.put("DALYTRAN-TYPE-CD", typeCode);
        row.put("DALYTRAN-CAT-CD", bd(Integer.toString(categoryCode)));
        row.put("DALYTRAN-SOURCE", "POS TERM");
        row.put("DALYTRAN-DESC", "Synthetic posting test");
        row.put("DALYTRAN-AMT", bd(amount));
        row.put("DALYTRAN-MERCHANT-ID", bd("1"));
        row.put("DALYTRAN-MERCHANT-NAME", "Test Merchant");
        row.put("DALYTRAN-MERCHANT-CITY", "Test City");
        row.put("DALYTRAN-MERCHANT-ZIP", "12345");
        row.put("DALYTRAN-CARD-NUM", cardNumber);
        row.put("DALYTRAN-ORIG-TS", originDate + "-09.15.00.000000");
        row.put("DALYTRAN-PROC-TS", "");
        return RecordCodec.encode("CVTRA06Y", row);
    }

    static byte[] transaction(String id, String cardNumber, String typeCode, int categoryCode,
                              String amount, String procDate) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("TRAN-ID", id);
        row.put("TRAN-TYPE-CD", typeCode);
        row.put("TRAN-CAT-CD", bd(Integer.toString(categoryCode)));
        row.put("TRAN-SOURCE", "POS TERM");
        row.put("TRAN-DESC", "Synthetic report test");
        row.put("TRAN-AMT", bd(amount));
        row.put("TRAN-MERCHANT-ID", bd("1"));
        row.put("TRAN-MERCHANT-NAME", "Test Merchant");
        row.put("TRAN-MERCHANT-CITY", "Test City");
        row.put("TRAN-MERCHANT-ZIP", "12345");
        row.put("TRAN-CARD-NUM", cardNumber);
        row.put("TRAN-ORIG-TS", "2022-07-04-09.15.00.000000");
        row.put("TRAN-PROC-TS", procDate + "-10.00.00.000000");
        return RecordCodec.encode("CVTRA05Y", row);
    }

    static byte[] dateParm(String startDate, String endDate) {
        String record = CobolFormat.rightPad(startDate, 10) + " " + CobolFormat.rightPad(endDate, 10);
        return CobolFormat.rightPad(record, 80).getBytes(StandardCharsets.ISO_8859_1);
    }

    void addXref(String card, BigDecimal accountId) {
        xrefs.put(card, accountId);
    }

    void addAccount(Account account) {
        accounts.put(key(account.id()), account);
    }

    void addCategoryBalance(BigDecimal accountId, String type, BigDecimal category, BigDecimal amount) {
        categoryBalances.put(new CategoryKey(key(accountId), type, key(category)), amount);
    }

    void addTransaction(Map<String, Object> record) {
        transactions.put(text(record.get("TRAN-ID")), new LinkedHashMap<>(record));
    }

    void addTransaction(byte[] record) {
        addTransaction(RecordCodec.decode("CVTRA05Y", record));
    }

    void setIgnoreAccountRewrite(boolean value) {
        ignoreAccountRewrite = value;
    }

    Optional<Account> storedAccount(BigDecimal id) {
        return Optional.ofNullable(accounts.get(key(id)));
    }

    Optional<BigDecimal> storedCategoryBalance(BigDecimal acct, String type, BigDecimal cat) {
        return Optional.ofNullable(categoryBalances.get(new CategoryKey(key(acct), type, key(cat))));
    }

    List<String> writeOrder() {
        return List.copyOf(writeOrder);
    }

    List<String> transactionWriteOrder() {
        return List.copyOf(transactionWriteOrder);
    }

    int transactionCount() {
        return transactions.size();
    }

    int accountLookupCount() {
        return accountLookupCount;
    }

    boolean hasTransaction(String id) {
        return transactions.containsKey(id);
    }

    void removeTypeDescription(String code) {
        typeDescriptions.remove(code);
    }

    void removeCategoryDescription(String type, BigDecimal code) {
        categoryDescriptions.remove(new CategoryKey("0", type, key(code)));
    }

    @Override
    public Optional<BigDecimal> accountIdForCard(String cardNumber) {
        return Optional.ofNullable(xrefs.get(cardNumber));
    }

    @Override
    public Optional<Account> account(BigDecimal accountId) {
        accountLookupCount++;
        return storedAccount(accountId);
    }

    @Override
    public Optional<BigDecimal> categoryBalance(BigDecimal accountId, String typeCode, BigDecimal categoryCode) {
        return storedCategoryBalance(accountId, typeCode, categoryCode);
    }

    @Override
    public void createCategoryBalance(BigDecimal accountId, String typeCode, BigDecimal categoryCode,
                                      BigDecimal balance) {
        writeOrder.add("TCATBAL");
        addCategoryBalance(accountId, typeCode, categoryCode, balance);
    }

    @Override
    public int updateCategoryBalance(BigDecimal accountId, String typeCode, BigDecimal categoryCode,
                                     BigDecimal balance) {
        writeOrder.add("TCATBAL");
        CategoryKey key = new CategoryKey(key(accountId), typeCode, key(categoryCode));
        if (!categoryBalances.containsKey(key)) {
            return 0;
        }
        categoryBalances.put(key, balance);
        return 1;
    }

    @Override
    public int updateAccount(Account account) {
        writeOrder.add("ACCOUNT");
        if (ignoreAccountRewrite) {
            return 0;
        }
        accounts.put(key(account.id()), account);
        return 1;
    }

    @Override
    public void insertTransaction(Map<String, Object> transaction) {
        String id = text(transaction.get("TRAN-ID"));
        writeOrder.add("TRANSACT");
        transactionWriteOrder.add(id);
        if (transactions.containsKey(id)) {
            throw new DuplicateTransactionException();
        }
        transactions.put(id, new LinkedHashMap<>(transaction));
    }

    @Override
    public void truncateTransactions() {
        writeOrder.add("TRUNCATE");
        transactions.clear();
        transactionWriteOrder.clear();
    }

    @Override
    public List<Map<String, Object>> transactions() {
        List<Map<String, Object>> rows = new ArrayList<>();
        transactions.values().stream()
                .sorted(Comparator.comparing(transaction -> text(transaction.get("TRAN-ID"))))
                .forEach(transaction -> rows.add(new LinkedHashMap<>(transaction)));
        return rows;
    }

    @Override
    public Optional<String> transactionTypeDescription(String typeCode) {
        return Optional.ofNullable(typeDescriptions.get(typeCode));
    }

    @Override
    public Optional<String> transactionCategoryDescription(String typeCode, BigDecimal categoryCode) {
        return Optional.ofNullable(categoryDescriptions.get(
                new CategoryKey("0", typeCode, key(categoryCode))));
    }

    private static List<Map<String, Object>> readDataset(Path file, int lrecl, String copybook) {
        return readRecords(file, lrecl).stream()
                .<Map<String, Object>>map(record -> RecordCodec.decode(copybook, record)).toList();
    }

    private static List<byte[]> readRecords(Path file, int lrecl) {
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        try {
            List<byte[]> records = new ArrayList<>();
            for (String line : Files.readAllLines(file, StandardCharsets.ISO_8859_1)) {
                byte[] source = line.getBytes(StandardCharsets.ISO_8859_1);
                byte[] record = new byte[lrecl];
                java.util.Arrays.fill(record, (byte) ' ');
                System.arraycopy(source, 0, record, 0, Math.min(source.length, lrecl));
                records.add(record);
            }
            return records;
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read test fixture " + file, exception);
        }
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    private static BigDecimal decimal(Object value) {
        return value instanceof BigDecimal number ? number : new BigDecimal(String.valueOf(value));
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String key(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private record CategoryKey(String account, String type, String category) {
    }
}
