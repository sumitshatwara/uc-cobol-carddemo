package com.carddemo.posting.post;

import com.carddemo.common.cobol.CobolNumeric;
import com.carddemo.posting.Cobol;
import java.math.BigDecimal;
import java.util.Optional;

/** CBTRN02C {@code 1500-VALIDATE-TRAN}: the legacy validation order and reject reasons 100-103. */
public class TransactionValidator {

    public static final int INVALID_CARD = 100;
    public static final int ACCOUNT_NOT_FOUND = 101;
    public static final int OVERLIMIT = 102;
    public static final int EXPIRED = 103;

    private final PostingStore store;

    public TransactionValidator(PostingStore store) {
        this.store = store;
    }

    /**
     * Card first, then account; limit and expiry checks only when the account exists.
     *
     * @see "BR-POST-003 BR-POST-004 BR-POST-005"
     */
    public Validation validate(DailyTranRecord tran) {
        Optional<Long> acctId = store.findXrefAcctId(tran.cardNum());
        if (acctId.isEmpty()) {
            return new Validation(tran, INVALID_CARD, "INVALID CARD NUMBER FOUND", null, null);
        }
        Optional<Account> account = store.findAccount(acctId.get());
        if (account.isEmpty()) {
            return new Validation(tran, ACCOUNT_NOT_FOUND, "ACCOUNT RECORD NOT FOUND", acctId.get(), null);
        }
        Account a = account.get();
        int reason = 0;
        String desc = "";
        if (!withinLimit(a, tran.amt())) {
            reason = OVERLIMIT;
            desc = "OVERLIMIT TRANSACTION";
        }
        if (!notExpired(a.expirationDate, tran.origTs())) {
            reason = EXPIRED;
            desc = "TRANSACTION RECEIVED AFTER ACCT EXPIRATION";
        }
        return new Validation(tran, reason, desc, acctId.get(), a);
    }

    /**
     * {@code COMPUTE WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT} into
     * S9(09)V99 (high-order truncation, no ROUNDED), accepted when {@code ACCT-CREDIT-LIMIT >= WS-TEMP-BAL}.
     * The current balance is not used.
     *
     * @see "BR-POST-006 BR-POST-007 BR-POST-023 BR-POST-029"
     */
    public static boolean withinLimit(Account a, BigDecimal amt) {
        BigDecimal tempBal = CobolNumeric.fit(a.cycCredit.subtract(a.cycDebit).add(amt), 9, 2);
        return a.creditLimit.compareTo(tempBal) >= 0;
    }

    /**
     * {@code ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS (1:10)} as an alphanumeric comparison, so a blank
     * or zero expiry date is "before" every origin date.
     *
     * @see "BR-POST-009 BR-POST-011"
     */
    public static boolean notExpired(String expirationDate, String origTs) {
        String exp = Cobol.pic(expirationDate, 10);
        String orig = Cobol.pic(origTs, 26).substring(0, 10);
        return exp.compareTo(orig) >= 0;
    }
}
