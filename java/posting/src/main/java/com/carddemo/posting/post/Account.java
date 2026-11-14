package com.carddemo.posting.post;

import java.math.BigDecimal;

/** The ACCOUNT-RECORD fields CBTRN02C reads and rewrites (CVACT01Y). */
public class Account {

    public final long acctId;
    public BigDecimal currBal;
    public final BigDecimal creditLimit;
    public BigDecimal cycCredit;
    public BigDecimal cycDebit;
    /** ACCT-EXPIRAION-DATE PIC X(10) (sic), compared as text. */
    public final String expirationDate;

    public Account(long acctId, BigDecimal currBal, BigDecimal creditLimit, BigDecimal cycCredit, BigDecimal cycDebit, String expirationDate) {
        this.acctId = acctId;
        this.currBal = currBal;
        this.creditLimit = creditLimit;
        this.cycCredit = cycCredit;
        this.cycDebit = cycDebit;
        this.expirationDate = expirationDate;
    }
}
