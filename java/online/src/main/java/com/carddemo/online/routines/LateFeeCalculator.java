package com.carddemo.online.routines;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Port of PL/I CBFEECAL (app/pli/CBFEECAL.pli).  DECIMAL FIXED arithmetic is {@link BigDecimal};
 * a PL/I assignment to a narrower DECIMAL FIXED target truncates, i.e. {@link RoundingMode#DOWN}.
 * The ON-units become early returns: ZERODIVIDE (RC 8, fee 0, average 0), SIZE (RC 8, fee 0) and
 * ERROR (RC 8, fee left as it was).  PL/I was not executed: verified against the worked examples
 * of docs/LEGACY-LANGUAGES.md.
 */
@Component
public class LateFeeCalculator {

    /** FEE_PCT DECIMAL FIXED(5,3) INIT(2.500). */
    static final BigDecimal FEE_PCT = new BigDecimal("2.500");
    /** FEE_FLOOR DECIMAL FIXED(9,2) INIT(5.00). */
    static final BigDecimal FEE_FLOOR = new BigDecimal("5.00");
    /** FEE_CAP DECIMAL FIXED(9,2) INIT(35.00). */
    static final BigDecimal FEE_CAP = new BigDecimal("35.00");
    /** WAIVE_BELOW DECIMAL FIXED(9,2) INIT(100.00). */
    static final BigDecimal WAIVE_BELOW = new BigDecimal("100.00");
    static final BigDecimal ZERO2 = new BigDecimal("0.00");

    /** Raised when a value does not fit its DECIMAL FIXED(p,q) target (the PL/I SIZE condition). */
    static final class SizeCondition extends RuntimeException {
        SizeCondition() {
            super(null, null, false, false);
        }
    }

    /**
     * Assignment to DECIMAL FIXED(p,q): truncate to q decimals, raise SIZE if the integer part
     * needs more than p-q digits ((SIZE) prefix enabled on the calculation block).
     */
    static BigDecimal assign(BigDecimal v, int p, int q) {
        BigDecimal t = v.setScale(q, RoundingMode.DOWN);
        if (t.abs().compareTo(BigDecimal.TEN.pow(p - q)) >= 0) {
            throw new SizeCondition();
        }
        return t;
    }

    /**
     * CBFEECAL main procedure.
     *
     * @see "BR-ONL-065 (request area), BR-ONL-066..073"
     */
    public FeeResult calculate(FeeRequest req) {
        List<String> sysprint = new ArrayList<>();
        BigDecimal feeAmt = ZERO2;
        BigDecimal avgDaily = ZERO2;
        int rc;
        try {
            // (SIZE): BEGIN;
            rc = 0;
            feeAmt = ZERO2;
            // BR-ONL-066 / BR-ONL-072: average daily balance first, ZERODIVIDE on 0 days (F-15)
            if (req.cycleDays() == 0) {
                sysprint.add(putSkipList("CBFEECAL: ZERODIVIDE FOR ACCOUNT ", req.acctId()));
                return new FeeResult(ZERO2, ZERO2, 8, sysprint);
            }
            avgDaily = assign(req.cycleBal().divide(BigDecimal.valueOf(req.cycleDays()), 20, RoundingMode.DOWN), 12, 2);
            // BR-ONL-067: nothing overdue
            if (req.lateDays() <= 0) {
                return new FeeResult(feeAmt, avgDaily, rc, sysprint);
            }
            // BR-ONL-068: GOLD small-balance waiver (case-sensitive compare, CHAR(10) blank-padded)
            if (pad(req.groupId(), 10).equals(pad("GOLD", 10)) && req.cycleBal().compareTo(WAIVE_BELOW) < 0) {
                return new FeeResult(feeAmt, avgDaily, 4, sysprint);
            }
            // BR-ONL-069: WORK_FEE DECIMAL FIXED(15,5) = bal * 2.500 / 100; FEE_AMT = WORK_FEE truncates
            BigDecimal workFee = assign(req.cycleBal().multiply(FEE_PCT).divide(BigDecimal.valueOf(100), 20,
                    RoundingMode.DOWN), 15, 5);
            feeAmt = assign(workFee, 9, 2);
            // BR-ONL-070 floor, BR-ONL-071 cap
            if (feeAmt.compareTo(FEE_FLOOR) < 0) {
                feeAmt = FEE_FLOOR;
            }
            if (feeAmt.compareTo(FEE_CAP) > 0) {
                feeAmt = FEE_CAP;
            }
            return new FeeResult(feeAmt, avgDaily, rc, sysprint);
        } catch (SizeCondition e) {
            // BR-ONL-073: ON SIZE
            sysprint.add(putSkipList("CBFEECAL: SIZE CONDITION FOR ACCOUNT ", req.acctId()));
            return new FeeResult(ZERO2, avgDaily, 8, sysprint);
        } catch (RuntimeException e) {
            // BR-ONL-073: ON ERROR - fee left as it was
            sysprint.add(putSkipList("CBFEECAL: UNEXPECTED ERROR, ACCOUNT ", req.acctId()));
            return new FeeResult(feeAmt, avgDaily, 8, sysprint);
        }
    }

    /** PUT SKIP LIST of a character constant and FEE_ACCT_ID CHAR(11). */
    static String putSkipList(String text, String acctId) {
        return text + pad(acctId, 11);
    }

    static String pad(String s, int n) {
        String v = s == null ? "" : s;
        return v.length() >= n ? v.substring(0, n) : v + " ".repeat(n - v.length());
    }
}
