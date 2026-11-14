package com.carddemo.online.routines;

import com.carddemo.online.store.AccountRecord;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Component;

/**
 * Port of the COBOL driver CBFEEDRV (FEECALC.jcl): builds FEE-REQ per account and formats the
 * 80-byte FEEOUT line written to the LATEFEES GDG.
 */
@Component
public class FeeDriver {

    /**
     * FEE-REQ from an account: cycle balance = cycle credit - cycle debit, late days 10 when that
     * balance is positive else 0, cycle days 30.
     *
     * @see "BR-ONL-065"
     */
    public FeeRequest buildRequest(AccountRecord acct) {
        BigDecimal bal = acct.currCycCredit().subtract(acct.currCycDebit()).setScale(2, RoundingMode.DOWN);
        int late = bal.signum() > 0 ? 10 : 0;
        return new FeeRequest(String.format("%011d", acct.acctId()), acct.groupId(), bal, late, 30);
    }

    /**
     * FEE-LINE: FL-ACCT-ID X(11), space, FL-GROUP-ID X(10), space, FL-CYCLE-BAL -(10)9.99, space,
     * FL-FEE-AMT -(7)9.99, space, FL-RC 99, filler to 80 bytes.
     *
     * @see "BR-ONL-074"
     */
    public String feeLine(FeeRequest req, FeeResult res) {
        StringBuilder b = new StringBuilder(80);
        b.append(LateFeeCalculator.pad(req.acctId(), 11)).append(' ');
        b.append(LateFeeCalculator.pad(req.groupId(), 10)).append(' ');
        b.append(floatingMinus(req.cycleBal(), 10)).append(' ');
        b.append(floatingMinus(res.feeAmt(), 7)).append(' ');
        b.append(String.format("%02d", Math.abs(res.rc()) % 100));
        return LateFeeCalculator.pad(b.toString(), 80);
    }

    /**
     * Edited picture {@code -(n)9.99}: n floating minus positions (one of them holds the sign),
     * then 9.99.  Total width n + 4.  High-order digits beyond the picture are dropped.
     */
    static String floatingMinus(BigDecimal v, int n) {
        BigDecimal t = v.setScale(2, RoundingMode.DOWN);
        String digits = t.abs().unscaledValue().toString();
        int intDigits = n; // n-1 floating digit positions + the 9
        String intPart = digits.length() > 2 ? digits.substring(0, digits.length() - 2) : "0";
        String frac = digits.length() >= 2 ? digits.substring(digits.length() - 2) : "0" + digits;
        if (intPart.length() > intDigits) {
            intPart = intPart.substring(intPart.length() - intDigits);
        }
        intPart = intPart.replaceFirst("^0+(?=\\d)", "");
        String body = (t.signum() < 0 ? "-" : "") + intPart + "." + frac;
        int width = n + 4;
        return " ".repeat(Math.max(0, width - body.length())) + body;
    }
}
