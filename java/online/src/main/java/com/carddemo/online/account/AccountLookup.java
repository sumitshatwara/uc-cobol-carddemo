package com.carddemo.online.account;

import com.carddemo.online.Pic;
import com.carddemo.online.store.AccountRecord;
import com.carddemo.online.store.CardXref;
import com.carddemo.online.store.CustomerRecord;
import com.carddemo.online.store.FileResult;
import com.carddemo.online.store.OnlineStore;
import com.carddemo.online.store.Resp;

/**
 * 9000-READ-ACCT of COACTVWC / COACTUPC: card xref by account (CXACAIX) -> account master
 * (ACCTDAT) -> customer master (CUSTDAT), with the legacy message texts.  Only the first message
 * of a request is kept (IF WS-RETURN-MSG-OFF), cut to WS-RETURN-MSG X(75).
 */
public final class AccountLookup {

    static final String XREF_FILE = "CXACAIX ";
    static final String ACCT_FILE = "ACCTDAT ";
    static final String CUST_FILE = "CUSTDAT ";

    /** Result of 9000-READ-ACCT. */
    public static final class Result {
        public String message = "";
        public boolean inputError;
        public boolean acctFilterNotOk;
        public boolean custFilterNotOk;
        public boolean foundAcct;
        public boolean foundCust;
        public CardXref xref;
        public AccountRecord account;
        public CustomerRecord customer;
        public boolean readAcct;
        public boolean readCust;

        void msg(String m) {
            if (Pic.isSpaces(message)) {
                message = Pic.x(m, 75);
            }
        }
    }

    private AccountLookup() {}

    /** MOVE S9(9) COMP to X(10): 9 digits, left-justified, one trailing blank. */
    static String respX(int v) {
        return Pic.x(String.format("%09d", Math.abs(v) % 1_000_000_000), 10);
    }

    /** WS-FILE-ERROR-MESSAGE. */
    static String fileError(String op, String file, int resp, int resp2) {
        return "File Error: " + Pic.x(op, 8) + " on " + Pic.x(file, 9) + " returned RESP " + respX(resp)
                + ",RESP2 " + respX(resp2) + "     ";
    }

    /**
     * @param acctIdX the validated 11-digit account id
     * @see "BR-ONL-024, BR-ONL-025, BR-ONL-026, F-01"
     */
    public static Result read(OnlineStore store, String acctIdX, String initialMessage) {
        Result r = new Result();
        r.message = initialMessage == null ? "" : initialMessage;
        // 9200-GETCARDXREF-BYACCT
        FileResult<CardXref> x = store.readXrefByAcct(acctIdX);
        if (x.resp() == Resp.NORMAL) {
            r.xref = x.record();
        } else if (x.resp() == Resp.NOTFND) {
            r.inputError = true;
            r.acctFilterNotOk = true;
            r.msg("Account:" + acctIdX + " not found in Cross ref file.  Resp:" + respX(x.resp()) + " Reas:"
                    + respX(x.resp2()));
        } else {
            r.inputError = true;
            r.acctFilterNotOk = true;
            r.message = Pic.x(fileError("READ", XREF_FILE, x.resp(), x.resp2()), 75);
        }
        if (r.acctFilterNotOk) {
            return r;
        }
        // 9300-GETACCTDATA-BYACCT
        r.readAcct = true;
        FileResult<AccountRecord> a = store.readAccount(acctIdX);
        if (a.resp() == Resp.NORMAL) {
            r.foundAcct = true;
            r.account = a.record();
        } else if (a.resp() == Resp.NOTFND) {
            r.inputError = true;
            r.acctFilterNotOk = true;
            r.msg("Account:" + acctIdX + " not found in Acct Master file.Resp:" + respX(a.resp()) + " Reas:"
                    + respX(a.resp2()));
        } else {
            r.inputError = true;
            r.acctFilterNotOk = true;
            r.message = Pic.x(fileError("READ", ACCT_FILE, a.resp(), a.resp2()), 75);
        }
        // F-01: IF DID-NOT-FIND-ACCT-IN-ACCTDAT never fires (the message is a STRING, not the 88),
        // so the customer read always follows.
        String custIdX = String.format("%09d", r.xref.custId());
        r.readCust = true;
        FileResult<CustomerRecord> c = store.readCustomer(custIdX);
        if (c.resp() == Resp.NORMAL) {
            r.foundCust = true;
            r.customer = c.record();
        } else if (c.resp() == Resp.NOTFND) {
            r.inputError = true;
            r.custFilterNotOk = true;
            r.msg("CustId:" + custIdX + " not found in customer master.Resp: " + respX(c.resp()) + " REAS:"
                    + respX(c.resp2()));
        } else {
            r.inputError = true;
            r.custFilterNotOk = true;
            r.message = Pic.x(fileError("READ", CUST_FILE, c.resp(), c.resp2()), 75);
        }
        return r;
    }
}
