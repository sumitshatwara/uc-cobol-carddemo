package com.carddemo.interest;

import java.math.BigDecimal;

/**
 * The part of ACCOUNT-RECORD (CVACT01Y) that CBACT04C reads or changes.  The REWRITE changes
 * only these three amounts; every other field of the record is written back unchanged.
 */
public final class AccountRecord {

    private final long acctId;
    private final String groupId;
    private BigDecimal currBal;
    private BigDecimal currCycCredit;
    private BigDecimal currCycDebit;

    public AccountRecord(long acctId, String groupId, BigDecimal currBal, BigDecimal currCycCredit, BigDecimal currCycDebit) {
        this.acctId = acctId;
        this.groupId = groupId == null ? "" : groupId;
        this.currBal = currBal;
        this.currCycCredit = currCycCredit;
        this.currCycDebit = currCycDebit;
    }

    public long acctId() { return acctId; }
    /** ACCT-GROUP-ID, trailing spaces removed. */
    public String groupId() { return groupId; }
    public BigDecimal currBal() { return currBal; }
    public BigDecimal currCycCredit() { return currCycCredit; }
    public BigDecimal currCycDebit() { return currCycDebit; }

    void setCurrBal(BigDecimal v) { this.currBal = v; }
    void setCurrCycCredit(BigDecimal v) { this.currCycCredit = v; }
    void setCurrCycDebit(BigDecimal v) { this.currCycDebit = v; }
}
