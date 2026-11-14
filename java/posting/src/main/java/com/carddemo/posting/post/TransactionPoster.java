package com.carddemo.posting.post;

import com.carddemo.common.cobol.CobolNumeric;
import com.carddemo.common.run.SysoutWriter;
import com.carddemo.posting.Cobol;
import java.math.BigDecimal;
import java.util.Optional;

/** CBTRN02C {@code 2000-POST-TRANSACTION}: category balance, then account, then transaction master. */
public class TransactionPoster {

    private final PostingStore store;
    private final SysoutWriter sysout;
    private final String procTs;

    /** @param procTs the run clock in Db2 timestamp form ({@code FrozenClock.db2Timestamp()}) */
    public TransactionPoster(PostingStore store, SysoutWriter sysout, String procTs) {
        this.store = store;
        this.sysout = sysout;
        this.procTs = procTs;
    }

    /**
     * Posts a validated transaction in the legacy order.  Returns the reason stored in
     * {@code WS-VALIDATION-FAIL-REASON} afterwards (109 when the account rewrite fails, which is
     * neither a reject nor a stop).
     *
     * @see "BR-POST-025 BR-POST-024"
     */
    public int post(Validation v) {
        DailyTranRecord t = v.tran();
        updateTcatBal(new TcatKey(v.xrefAcctId(), Cobol.rstrip(t.typeCd()), t.catCd()), t.amt());
        int reason = updateAccount(v.account(), t.amt()) ? 0 : 109;
        writeTransaction(toTransaction(t));
        return reason;
    }

    /**
     * Copies the daily record field by field and stamps {@code TRAN-PROC-TS} with the run clock.
     *
     * @see "BR-POST-016 BR-POST-017"
     */
    public PostedTransaction toTransaction(DailyTranRecord t) {
        return new PostedTransaction(Cobol.rstrip(t.id()), Cobol.rstrip(t.typeCd()), t.catCd(), Cobol.rstrip(t.source()),
                Cobol.rstrip(t.desc()), t.amt(), t.merchantId(), Cobol.rstrip(t.merchantName()), Cobol.rstrip(t.merchantCity()),
                Cobol.rstrip(t.merchantZip()), Cobol.rstrip(t.cardNum()), Cobol.rstrip(t.origTs()), procTs);
    }

    /**
     * {@code 2700-UPDATE-TCATBAL}: create the record (balance = amount) when the key is missing,
     * otherwise add the amount; TRAN-CAT-BAL is S9(09)V99.
     *
     * @see "BR-POST-018 BR-POST-019 BR-POST-020 BR-POST-021"
     */
    public void updateTcatBal(TcatKey key, BigDecimal amt) {
        Optional<BigDecimal> bal = store.findTcatBal(key);
        if (bal.isEmpty()) {
            sysout.display("TCATBAL record not found for key : ", key.display(), ".. Creating.");
            if (!store.writeTcatBal(key, CobolNumeric.fit(amt, 9, 2))) {
                sysout.display("ERROR WRITING TRANSACTION BALANCE FILE");
                throw Cobol.abend(sysout, "22");
            }
        } else if (!store.rewriteTcatBal(key, CobolNumeric.fit(bal.get().add(amt), 9, 2))) {
            sysout.display("ERROR REWRITING TRANSACTION BALANCE FILE");
            throw Cobol.abend(sysout, "23");
        }
    }

    /**
     * {@code 2800-UPDATE-ACCOUNT-REC}: amount to the current balance, and to cycle credit when
     * {@code >= 0} or (as a negative number) to cycle debit.  Fields are S9(10)V99.
     *
     * @see "BR-POST-022 BR-POST-023"
     */
    public boolean updateAccount(Account a, BigDecimal amt) {
        a.currBal = CobolNumeric.fit(a.currBal.add(amt), 10, 2);
        if (amt.signum() >= 0) {
            a.cycCredit = CobolNumeric.fit(a.cycCredit.add(amt), 10, 2);
        } else {
            a.cycDebit = CobolNumeric.fit(a.cycDebit.add(amt), 10, 2);
        }
        return store.rewriteAccount(a);
    }

    /** {@code 2900-WRITE-TRANSACTION-FILE}: a failed write abends after the balances changed. */
    public void writeTransaction(PostedTransaction tran) {
        if (!store.writeTransaction(tran)) {
            sysout.display("ERROR WRITING TO TRANSACTION FILE");
            throw Cobol.abend(sysout, "22");
        }
    }
}
