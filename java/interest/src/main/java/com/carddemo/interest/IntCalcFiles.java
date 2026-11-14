package com.carddemo.interest;

import java.math.BigDecimal;

/**
 * The five DD names CBACT04C opens, as a port.  Every operation returns the two-character
 * VSAM / QSAM file status the COBOL program tests ({@code 00} ok, {@code 10} end of file,
 * {@code 23} record not found, anything else an error), so the program logic in
 * {@link Cbact04c} can reproduce each status branch literally.  The batch job uses
 * {@link JdbcIntCalcFiles} (PostgreSQL tables); unit tests use in-memory fakes.
 */
public interface IntCalcFiles {

    /** DD names of INTCALC STEP15, in the order 0000..0400 open them. */
    enum Dd { TCATBALF, XREFFILE, DISCGRP, ACCTFILE, TRANSACT }

    /** Result of a READ: the file status and, when it is {@code 00}, the record read. */
    record Io<T>(String status, T value) {
        public static <T> Io<T> ok(T value) { return new Io<>("00", value); }
        public static <T> Io<T> status(String status) { return new Io<>(status, null); }
        public boolean isOk() { return "00".equals(status); }
    }

    /** One TCATBALF record (CVTRA01Y). */
    record TcatBal(long acctId, String typeCd, long catCd, BigDecimal bal) {}

    /** One card cross-reference record (CVACT03Y). */
    record Xref(String cardNum, long custId, long acctId) {}

    /** OPEN (input for TCATBALF/XREFFILE/DISCGRP, I-O for ACCTFILE, OUTPUT for TRANSACT). */
    String open(Dd dd);

    /** CLOSE. */
    String close(Dd dd);

    /** {@code READ TCATBAL-FILE} (sequential, key order). */
    Io<TcatBal> readNextBalance();

    /** {@code READ ACCOUNT-FILE} by primary key. */
    Io<AccountRecord> readAccount(long acctId);

    /** {@code REWRITE FD-ACCTFILE-REC FROM ACCOUNT-RECORD}. */
    String rewriteAccount(AccountRecord account);

    /** {@code READ XREF-FILE KEY IS FD-XREF-ACCT-ID} (alternate key). */
    Io<Xref> readXrefByAcct(long acctId);

    /** {@code READ DISCGRP-FILE} by (group, type, category); the value is DIS-INT-RATE. */
    Io<BigDecimal> readRate(String groupId, String typeCd, long catCd);

    /** {@code WRITE FD-TRANFILE-REC FROM TRAN-RECORD}. */
    String writeTran(InterestTran tran);
}
