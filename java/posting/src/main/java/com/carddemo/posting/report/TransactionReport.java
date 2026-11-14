package com.carddemo.posting.report;

import com.carddemo.common.cobol.CobolNumeric;
import com.carddemo.common.run.SysoutWriter;
import com.carddemo.posting.Cobol;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * CBTRN03C (TRANREPT STEP10R): the 133-byte daily transaction report, reproduced with its legacy
 * control flow (NEXT SENTENCE on an out-of-range date, the last record re-added at end of file,
 * no total for the last account, page breaks on exact multiples of the line counter).
 */
public class TransactionReport {

    public static final int LRECL = 133;
    public static final int PAGE_SIZE = 20;
    static final String DASHES = "-".repeat(LRECL);
    private static final String SPACES10 = " ".repeat(10);

    private final ReportLookups lookups;
    private final SysoutWriter sysout;
    private final List<String> lines = new ArrayList<>();

    private String startDate = SPACES10;
    private String endDate = SPACES10;
    private boolean firstTime = true;
    private long lineCounter;
    private BigDecimal pageTotal = BigDecimal.ZERO.setScale(2);
    private BigDecimal accountTotal = BigDecimal.ZERO.setScale(2);
    private BigDecimal grandTotal = BigDecimal.ZERO.setScale(2);
    private String currCardNum = " ".repeat(16);
    private long xrefAcctId;
    private String tranTypeDesc = "";
    private String tranCatDesc = "";

    public TransactionReport(ReportLookups lookups, SysoutWriter sysout) {
        this.lookups = lookups;
        this.sysout = sysout;
    }

    /**
     * Runs the program over the sorted transaction file.
     *
     * @param tranRecords the 350-byte records of TRANSACT.DALY(+1), in file order
     * @param dateParms   the DATEPARM records (only the first is read)
     * @return the report lines (each at most 133 characters)
     */
    public List<String> run(List<String> tranRecords, List<String> dateParms) {
        sysout.display("START OF EXECUTION OF PROGRAM CBTRN03C");
        boolean eof = readDateParm(dateParms);
        String tranRecord = null; // WORKING-STORAGE TRAN-RECORD: spaces / zero until the first READ INTO
        int next = 0;
        while (!eof) {
            if (next < tranRecords.size()) {
                tranRecord = Cobol.pic(tranRecords.get(next++), 350);
            } else {
                eof = true; // READ ... INTO at end of file leaves the record area unchanged
            }
            String procDate = tranRecord == null ? SPACES10 : TranReptSort.procDate(tranRecord);
            if (!inRange(procDate)) {
                break; // NEXT SENTENCE: leaves the whole PERFORM UNTIL statement
            }
            if (!eof) {
                reportTransaction(tranRecord);
            } else {
                endOfFile(tranRecord);
            }
        }
        sysout.display("END OF EXECUTION OF PROGRAM CBTRN03C");
        return List.copyOf(lines);
    }

    /**
     * {@code 0550-DATEPARM-READ}: start date in columns 1-10, end date in 12-21; an empty file is EOF.
     *
     * @see "BR-POST-032"
     */
    boolean readDateParm(List<String> dateParms) {
        if (dateParms.isEmpty()) {
            return true;
        }
        String rec = Cobol.pic(dateParms.get(0), 80);
        startDate = rec.substring(0, 10);
        endDate = rec.substring(11, 21);
        sysout.display("Reporting from ", startDate, " to ", endDate);
        return false;
    }

    /** @see "BR-POST-033" */
    boolean inRange(String procDate) {
        return procDate.compareTo(startDate) >= 0 && procDate.compareTo(endDate) <= 0;
    }

    /** @see "BR-POST-034 BR-POST-038 BR-POST-039" */
    private void reportTransaction(String rec) {
        sysout.display(rec);
        String card = TranReptSort.cardNum(rec);
        if (!currCardNum.equals(card)) {
            if (!firstTime) {
                writeAccountTotals();
            }
            currCardNum = card;
            Optional<Long> acct = lookups.xrefAcctId(card);
            if (acct.isEmpty()) {
                sysout.display("INVALID CARD NUMBER : ", card);
                throw Cobol.abend(sysout, "23");
            }
            xrefAcctId = acct.get();
        }
        String typeCd = rec.substring(16, 18);
        Optional<String> type = lookups.tranTypeDesc(typeCd);
        if (type.isEmpty()) {
            sysout.display("INVALID TRANSACTION TYPE : ", typeCd);
            throw Cobol.abend(sysout, "23");
        }
        tranTypeDesc = type.get();
        String catRaw = rec.substring(18, 22);
        Optional<String> cat = lookups.tranCatDesc(typeCd, Cobol.zoned(catRaw, 0).longValue());
        if (cat.isEmpty()) {
            sysout.display("INVALID TRAN CATG KEY : ", typeCd, catRaw);
            throw Cobol.abend(sysout, "23");
        }
        tranCatDesc = cat.get();
        writeTransactionReport(rec);
    }

    /**
     * End of file: the record area still holds the last transaction, whose amount is displayed and
     * added a second time before the page and grand totals; no account total for the last card.
     *
     * @see "BR-POST-042 BR-POST-043 BR-POST-041"
     */
    private void endOfFile(String lastRecord) {
        BigDecimal amt = lastRecord == null ? BigDecimal.ZERO.setScale(2) : Cobol.zoned(lastRecord.substring(132, 143), 2);
        sysout.display("TRAN-AMT ", Cobol.displaySigned(amt, 9, 2));
        sysout.display("WS-PAGE-TOTAL", Cobol.displaySigned(pageTotal, 9, 2));
        pageTotal = add(pageTotal, amt);
        accountTotal = add(accountTotal, amt);
        writePageTotals();
        writeGrandTotals();
    }

    /** {@code 1100-WRITE-TRANSACTION-REPORT}. @see "BR-POST-035 BR-POST-040" */
    private void writeTransactionReport(String rec) {
        if (firstTime) {
            firstTime = false;
            writeHeaders();
        }
        if (lineCounter % PAGE_SIZE == 0) {
            writePageTotals();
            writeHeaders();
        }
        BigDecimal amt = Cobol.zoned(rec.substring(132, 143), 2);
        pageTotal = add(pageTotal, amt);
        accountTotal = add(accountTotal, amt);
        writeDetail(rec, amt);
    }

    /** @see "BR-POST-041" */
    private void writePageTotals() {
        write(Cobol.pic("Page Total", 11) + ".".repeat(86) + NumericEdit.edit(pageTotal, '+'));
        grandTotal = add(grandTotal, pageTotal);
        pageTotal = BigDecimal.ZERO.setScale(2);
        lineCounter++;
        write(DASHES);
        lineCounter++;
    }

    /** @see "BR-POST-039" */
    private void writeAccountTotals() {
        write(Cobol.pic("Account Total", 13) + ".".repeat(84) + NumericEdit.edit(accountTotal, '+'));
        accountTotal = BigDecimal.ZERO.setScale(2);
        lineCounter++;
        write(DASHES);
        lineCounter++;
    }

    private void writeGrandTotals() {
        write(Cobol.pic("Grand Total", 11) + ".".repeat(86) + NumericEdit.edit(grandTotal, '+'));
    }

    /** @see "BR-POST-035" */
    private void writeHeaders() {
        write(Cobol.pic("DALYREPT", 38) + Cobol.pic("Daily Transaction Report", 41) + Cobol.pic("Date Range: ", 12)
                + startDate + " to " + endDate);
        lineCounter++;
        write("");
        lineCounter++;
        write(Cobol.pic("Transaction ID", 17) + Cobol.pic("Account ID", 12) + Cobol.pic("Transaction Type", 19)
                + Cobol.pic("Tran Category", 35) + Cobol.pic("Tran Source", 14) + " " + Cobol.pic("        Amount", 16));
        lineCounter++;
        write(DASHES);
        lineCounter++;
    }

    /** {@code 1120-WRITE-DETAIL}. @see "BR-POST-036" */
    private void writeDetail(String rec, BigDecimal amt) {
        write(detailLine(rec.substring(0, 16), xrefAcctId, rec.substring(16, 18), tranTypeDesc, rec.substring(18, 22),
                tranCatDesc, rec.substring(22, 32), amt));
        lineCounter++;
    }

    static String detailLine(String tranId, long acctId, String typeCd, String typeDesc, String catCd, String catDesc,
                             String source, BigDecimal amt) {
        return Cobol.pic(tranId, 16) + " " + Cobol.nines(acctId, 11) + " " + Cobol.pic(typeCd, 2) + "-" + Cobol.pic(typeDesc, 15)
                + " " + Cobol.pic(catCd, 4) + "-" + Cobol.pic(catDesc, 29) + " " + Cobol.pic(source, 10) + "    "
                + NumericEdit.edit(amt, '-') + "  ";
    }

    private void write(String line) {
        lines.add(Cobol.pic(line, LRECL));
    }

    private static BigDecimal add(BigDecimal total, BigDecimal amt) {
        return CobolNumeric.fit(total.add(amt), 9, 2);
    }

    public List<String> lines() { return List.copyOf(lines); }
    long lineCounter() { return lineCounter; }
}
