package com.carddemo.interest;

import com.carddemo.common.cobol.CobolNumeric;
import com.carddemo.interest.IntCalcFiles.Dd;
import com.carddemo.interest.IntCalcFiles.Io;
import com.carddemo.interest.IntCalcFiles.TcatBal;
import com.carddemo.interest.IntCalcFiles.Xref;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Line-by-line port of {@code app/cbl/CBACT04C.cbl} (INTCALC STEP15): the PERFORM loop over the
 * transaction-category balances with its account break, the disclosure-group lookup with the
 * DEFAULT fallback, the un-ROUNDED interest COMPUTE, one SYSTRAN record per non-zero-rate balance
 * and the account rewrite - including the end-of-file behaviour that never rewrites the last
 * account (BR-INT-042).  Paragraph names are kept as method names so the two can be read side by side.
 * <p>
 * Working-storage fields are instance fields with the same PIC limits.  I/O goes through
 * {@link IntCalcFiles}, SYSOUT through a {@code Consumer<String>} (one call per {@code DISPLAY}).
 * The program is single-use: create one instance per run.
 */
public final class Cbact04c {

    /** {@code PARM='2022071800'} of {@code //STEP15 EXEC PGM=CBACT04C} in app/jcl/INTCALC.jcl. */
    public static final String JCL_PARM = "2022071800";

    /** Harness return code of a user abend ({@code tools/stubs/CEE3ABD.cbl: MOVE 12 TO RETURN-CODE}). */
    public static final int ABEND_RC = 12;

    private static final BigDecimal TWELVE_HUNDRED = BigDecimal.valueOf(1200);

    /** One balance record as processed: input to the console breakdown and to the unit tests. */
    public record CategoryResult(long acctId, String typeCd, long catCd, BigDecimal balance, String groupUsed,
                                 BigDecimal rate, BigDecimal exactInterest, BigDecimal monthlyInt, String tranId) {
        /** True when the rate was zero and no interest / transaction was produced (BR-INT-022). */
        public boolean skipped() { return tranId == null; }
    }

    private final IntCalcFiles files;
    private final Consumer<String> sysout;
    private final String parmDate;
    private final Supplier<String> db2Timestamp;

    // ---- WORKING-STORAGE
    private boolean endOfFile;                       // END-OF-FILE PIC X VALUE 'N'
    private String wsLastAcctNum = "           ";    // WS-LAST-ACCT-NUM PIC X(11) VALUE SPACES
    private BigDecimal wsMonthlyInt = BigDecimal.ZERO.setScale(2); // WS-MONTHLY-INT PIC S9(09)V99
    private BigDecimal wsTotalInt = BigDecimal.ZERO.setScale(2);   // WS-TOTAL-INT   PIC S9(09)V99
    private boolean wsFirstTime = true;              // WS-FIRST-TIME PIC X VALUE 'Y'
    private long wsRecordCount;                      // WS-RECORD-COUNT PIC 9(09)
    private int wsTranidSuffix;                      // WS-TRANID-SUFFIX PIC 9(06)
    private TcatBal tranCatBalRecord;                // TRAN-CAT-BAL-RECORD (CVTRA01Y)
    private AccountRecord accountRecord;             // ACCOUNT-RECORD (CVACT01Y)
    private Xref cardXrefRecord;                     // CARD-XREF-RECORD (CVACT03Y)
    private BigDecimal disIntRate = BigDecimal.ZERO.setScale(2); // DIS-INT-RATE (CVTRA02Y)
    private String disGroupUsed;

    private final List<CategoryResult> categories = new ArrayList<>();
    private final List<Long> rewrittenAccounts = new ArrayList<>();
    private int returnCode;

    public Cbact04c(IntCalcFiles files, Consumer<String> sysout, String parmDate, Supplier<String> db2Timestamp) {
        this.files = files;
        this.sysout = sysout;
        this.parmDate = parmDate;
        this.db2Timestamp = db2Timestamp;
    }

    /**
     * Runs the program; returns the step return code: 0 after GOBACK (BR-INT-046), 12 after a
     * user abend (BR-INT-047).  Updates already written stay written (BR-INT-048).
     */
    public int run() {
        try {
            procedureDivision();
            returnCode = 0;
        } catch (AbendException e) {
            returnCode = ABEND_RC;
        }
        return returnCode;
    }

    /** @see "BR-INT-001, BR-INT-003, BR-INT-006, BR-INT-008 .. BR-INT-011, BR-INT-042, BR-INT-044" */
    private void procedureDivision() {
        display("START OF EXECUTION OF PROGRAM CBACT04C");
        tcatbalfOpen();
        xreffileOpen();
        discgrpOpen();
        acctfileOpen();
        tranfileOpen();

        // PERFORM UNTIL END-OF-FILE = 'Y': the condition is tested *before* each iteration, so
        // once 1000-TCATBALF-GET-NEXT sets END-OF-FILE the loop ends and the ELSE branch that
        // would update the last account is never reached (BR-INT-042, reproduced on purpose).
        while (!endOfFile) {
            if (!endOfFile) {
                tcatbalfGetNext();
                if (!endOfFile) {
                    wsRecordCount = (wsRecordCount + 1) % 1_000_000_000L;
                    display(balanceRecordImage(tranCatBalRecord));
                    String acct = String.format("%011d", tranCatBalRecord.acctId());
                    if (!acct.equals(wsLastAcctNum)) {
                        if (wsFirstTime) {
                            wsFirstTime = false;
                        } else {
                            updateAccount();
                        }
                        wsTotalInt = BigDecimal.ZERO.setScale(2);
                        wsLastAcctNum = acct;
                        getAcctData(tranCatBalRecord.acctId());
                        getXrefData(tranCatBalRecord.acctId());
                    }
                    getInterestRate(accountRecord.groupId(), tranCatBalRecord.typeCd(), tranCatBalRecord.catCd());
                    if (disIntRate.signum() != 0) {
                        computeInterest();
                        computeFees();
                    } else {
                        categories.add(new CategoryResult(tranCatBalRecord.acctId(), tranCatBalRecord.typeCd(),
                                tranCatBalRecord.catCd(), tranCatBalRecord.bal(), disGroupUsed, disIntRate,
                                exactInterest(tranCatBalRecord.bal(), disIntRate), null, null));
                    }
                }
            } else {
                updateAccount(); // unreachable, as in the COBOL source
            }
        }

        tcatbalfClose();
        xreffileClose();
        discgrpClose();
        acctfileClose();
        tranfileClose();
        display("END OF EXECUTION OF PROGRAM CBACT04C");
    }

    // ------------------------------------------------------------------ open / close (BR-INT-003/004/044)

    private void tcatbalfOpen() { openOrAbend(Dd.TCATBALF, "ERROR OPENING TRANSACTION CATEGORY BALANCE", false); }
    private void xreffileOpen() { openOrAbend(Dd.XREFFILE, "ERROR OPENING CROSS REF FILE", true); }
    /** The message really says DALY REJECTS (copied from CBTRN02C in the legacy source). */
    private void discgrpOpen() { openOrAbend(Dd.DISCGRP, "ERROR OPENING DALY REJECTS FILE", false); }
    private void acctfileOpen() { openOrAbend(Dd.ACCTFILE, "ERROR OPENING ACCOUNT MASTER FILE", false); }
    private void tranfileOpen() { openOrAbend(Dd.TRANSACT, "ERROR OPENING TRANSACTION FILE", false); }

    private void openOrAbend(Dd dd, String message, boolean appendStatus) {
        String status = files.open(dd);
        if (!"00".equals(status)) {
            display(appendStatus ? message + status : message);
            displayIoStatus(status);
            abendProgram();
        }
    }

    private void tcatbalfClose() { closeOrAbend(Dd.TCATBALF, "ERROR CLOSING TRANSACTION BALANCE FILE"); }
    private void xreffileClose() { closeOrAbend(Dd.XREFFILE, "ERROR CLOSING CROSS REF FILE"); }
    private void discgrpClose() { closeOrAbend(Dd.DISCGRP, "ERROR CLOSING DISCLOSURE GROUP FILE"); }
    private void acctfileClose() { closeOrAbend(Dd.ACCTFILE, "ERROR CLOSING ACCOUNT FILE"); }
    private void tranfileClose() { closeOrAbend(Dd.TRANSACT, "ERROR CLOSING TRANSACTION FILE"); }

    private void closeOrAbend(Dd dd, String message) {
        String status = files.close(dd);
        if (!"00".equals(status)) {
            display(message);
            displayIoStatus(status);
            abendProgram();
        }
    }

    // ------------------------------------------------------------------ 1000 .. 1110

    /** 1000-TCATBALF-GET-NEXT. @see "BR-INT-005, BR-INT-006, BR-INT-007" */
    private void tcatbalfGetNext() {
        Io<TcatBal> r = files.readNextBalance();
        if (r.isOk()) {
            tranCatBalRecord = r.value();
        } else if ("10".equals(r.status())) {
            endOfFile = true;
        } else {
            display("ERROR READING TRANSACTION CATEGORY FILE");
            displayIoStatus(r.status());
            abendProgram();
        }
    }

    /** 1050-UPDATE-ACCOUNT. @see "BR-INT-039, BR-INT-040, BR-INT-041, BR-INT-029" */
    private void updateAccount() {
        accountRecord.setCurrBal(CobolNumeric.fit(accountRecord.currBal().add(wsTotalInt), 10, 2));
        accountRecord.setCurrCycCredit(BigDecimal.ZERO.setScale(2));
        accountRecord.setCurrCycDebit(BigDecimal.ZERO.setScale(2));
        String status = files.rewriteAccount(accountRecord);
        if (!"00".equals(status)) {
            display("ERROR RE-WRITING ACCOUNT FILE");
            displayIoStatus(status);
            abendProgram();
        }
        rewrittenAccounts.add(accountRecord.acctId());
    }

    /** 1100-GET-ACCT-DATA. @see "BR-INT-012, BR-INT-013" */
    private void getAcctData(long acctId) {
        Io<AccountRecord> r = files.readAccount(acctId);
        if (isInvalidKey(r.status())) {
            display("ACCOUNT NOT FOUND: " + String.format("%011d", acctId));
        }
        if (r.isOk()) {
            accountRecord = r.value();
        } else {
            display("ERROR READING ACCOUNT FILE");
            displayIoStatus(r.status());
            abendProgram();
        }
    }

    /** 1110-GET-XREF-DATA (alternate key). @see "BR-INT-014, BR-INT-015" */
    private void getXrefData(long acctId) {
        Io<Xref> r = files.readXrefByAcct(acctId);
        if (isInvalidKey(r.status())) {
            display("ACCOUNT NOT FOUND: " + String.format("%011d", acctId));
        }
        if (r.isOk()) {
            cardXrefRecord = r.value();
        } else {
            display("ERROR READING XREF FILE");
            displayIoStatus(r.status());
            abendProgram();
        }
    }

    // ------------------------------------------------------------------ 1200 rate lookup

    /** 1200-GET-INTEREST-RATE. @see "BR-INT-017, BR-INT-018, BR-INT-019, BR-INT-020" */
    private void getInterestRate(String groupId, String typeCd, long catCd) {
        Io<BigDecimal> r = files.readRate(groupId, typeCd, catCd);
        if (isInvalidKey(r.status())) {
            display("DISCLOSURE GROUP RECORD MISSING");
            display("TRY WITH DEFAULT GROUP CODE");
        }
        if (!r.isOk() && !"23".equals(r.status())) {
            display("ERROR READING DISCLOSURE GROUP FILE");
            displayIoStatus(r.status());
            abendProgram();
        }
        if (r.isOk()) {
            disIntRate = r.value();
            disGroupUsed = groupId;
        }
        if ("23".equals(r.status())) {
            getDefaultIntRate(typeCd, catCd);
        }
    }

    /** 1200-A-GET-DEFAULT-INT-RATE: only the group id is replaced. @see "BR-INT-018, BR-INT-021" */
    private void getDefaultIntRate(String typeCd, long catCd) {
        Io<BigDecimal> r = files.readRate("DEFAULT", typeCd, catCd);
        if (r.isOk()) {
            disIntRate = r.value();
            disGroupUsed = "DEFAULT";
        } else {
            display("ERROR READING DEFAULT DISCLOSURE GROUP");
            displayIoStatus(r.status());
            abendProgram();
        }
    }

    // ------------------------------------------------------------------ 1300 / 1400

    /**
     * 1300-COMPUTE-INTEREST: {@code COMPUTE WS-MONTHLY-INT = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200}
     * with no ROUNDED and no ON SIZE ERROR, then {@code ADD WS-MONTHLY-INT TO WS-TOTAL-INT}.
     * @see "BR-INT-023, BR-INT-024, BR-INT-025, BR-INT-026, BR-INT-027, BR-INT-028, BR-INT-029"
     */
    private void computeInterest() {
        wsMonthlyInt = monthlyInterest(tranCatBalRecord.bal(), disIntRate);
        wsTotalInt = CobolNumeric.fit(wsTotalInt.add(wsMonthlyInt), 9, 2);
        String tranId = writeTx();
        categories.add(new CategoryResult(tranCatBalRecord.acctId(), tranCatBalRecord.typeCd(), tranCatBalRecord.catCd(),
                tranCatBalRecord.bal(), disGroupUsed, disIntRate, exactInterest(tranCatBalRecord.bal(), disIntRate),
                wsMonthlyInt, tranId));
    }

    /**
     * The interest as CBACT04C stores it in WS-MONTHLY-INT PIC S9(09)V99: the exact quotient
     * truncated toward zero to cents ({@link RoundingMode#DOWN}, never half-up or half-even),
     * high-order digits beyond 9 dropped.
     * @see "BR-INT-023, BR-INT-024, BR-INT-025, BR-INT-026, BR-INT-029"
     */
    public static BigDecimal monthlyInterest(BigDecimal balance, BigDecimal annualRatePercent) {
        return CobolNumeric.fit(exactInterest(balance, annualRatePercent), 9, 2);
    }

    /** {@code balance * rate / 1200} kept at full precision (scale 12, toward zero) for display. */
    public static BigDecimal exactInterest(BigDecimal balance, BigDecimal annualRatePercent) {
        return balance.multiply(annualRatePercent).divide(TWELVE_HUNDRED, 12, RoundingMode.DOWN).stripTrailingZeros();
    }

    /** 1300-B-WRITE-TX. @see "BR-INT-031 .. BR-INT-037" */
    private String writeTx() {
        wsTranidSuffix = (wsTranidSuffix + 1) % 1_000_000;
        String tranId = transactionId(parmDate, wsTranidSuffix);
        String ts = db2Timestamp.get();
        InterestTran tran = new InterestTran(
                tranId,
                "01",
                5,
                "System",
                "Int. for a/c " + String.format("%011d", accountRecord.acctId()),
                wsMonthlyInt,
                0,
                "",
                "",
                "",
                cardXrefRecord.cardNum(),
                ts,
                ts);
        String status = files.writeTran(tran);
        if (!"00".equals(status)) {
            display("ERROR WRITING TRANSACTION RECORD");
            displayIoStatus(status);
            abendProgram();
        }
        return tranId;
    }

    /** {@code STRING PARM-DATE, WS-TRANID-SUFFIX DELIMITED BY SIZE INTO TRAN-ID}. @see "BR-INT-002, BR-INT-032" */
    public static String transactionId(String parmDate, int suffix) {
        String parm = parmDate.length() >= 10 ? parmDate.substring(0, 10) : parmDate + " ".repeat(10 - parmDate.length());
        return parm + String.format("%06d", suffix % 1_000_000);
    }

    /** 1400-COMPUTE-FEES: an empty stub in the legacy program ("To be implemented"). @see "BR-INT-030" */
    private void computeFees() {
        // intentionally empty
    }

    // ------------------------------------------------------------------ 9910 / 9999

    /** 9910-DISPLAY-IO-STATUS. @see "BR-INT-045" */
    private void displayIoStatus(String status) {
        display(formatIoStatus(status));
    }

    /** The line 9910-DISPLAY-IO-STATUS displays for a two-character file status. @see "BR-INT-045" */
    public static String formatIoStatus(String status) {
        String s = (status + "  ").substring(0, 2);
        boolean numeric = Character.isDigit(s.charAt(0)) && Character.isDigit(s.charAt(1));
        if (!numeric || s.charAt(0) == '9') {
            return "FILE STATUS IS: NNNN" + s.charAt(0) + String.format("%03d", (s.charAt(1) & 0xFF) % 1000);
        }
        return "FILE STATUS IS: NNNN" + "00" + s;
    }

    /** 9999-ABEND-PROGRAM + the CEE3ABD stand-in. @see "BR-INT-047" */
    private void abendProgram() {
        display("ABENDING PROGRAM");
        display("CEE3ABD: USER ABEND U+0000000999");
        throw new AbendException(999);
    }

    // ------------------------------------------------------------------ helpers

    /** {@code DISPLAY TRAN-CAT-BAL-RECORD}: the raw 50-byte record (zoned overpunch, 22-byte filler). @see "BR-INT-008" */
    public static String balanceRecordImage(TcatBal r) {
        String type = (r.typeCd() + "  ").substring(0, 2);
        return String.format("%011d", r.acctId()) + type + String.format("%04d", r.catCd())
                + CobolNumeric.zonedStorage(r.bal(), 9, 2, true) + " ".repeat(22);
    }

    private static boolean isInvalidKey(String status) {
        return status != null && status.startsWith("2");
    }

    private void display(String line) {
        sysout.accept(line);
    }

    // ------------------------------------------------------------------ results

    public int returnCode() { return returnCode; }
    public List<CategoryResult> categories() { return List.copyOf(categories); }
    /** Account ids rewritten by 1050-UPDATE-ACCOUNT, in order. */
    public List<Long> rewrittenAccounts() { return List.copyOf(rewrittenAccounts); }
    public long recordCount() { return wsRecordCount; }
}
