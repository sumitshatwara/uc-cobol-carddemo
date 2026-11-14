package com.carddemo.posting.post;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * The VSAM files CBTRN02C opens, as a port: XREFFILE (input), ACCTFILE and TCATBALF (I-O) and
 * TRANFILE (output).  {@link JdbcPostingStore} maps them to the carddemo tables; unit tests use an
 * in-memory fake.
 */
public interface PostingStore {

    /** {@code OPEN OUTPUT TRANSACT-FILE}: the run's postings become the file's only content. */
    void openTransactOutput();

    Optional<Long> findXrefAcctId(String cardNum);

    Optional<Account> findAccount(long acctId);

    /** {@code REWRITE FD-ACCTFILE-REC}; false = INVALID KEY. */
    boolean rewriteAccount(Account account);

    Optional<BigDecimal> findTcatBal(TcatKey key);

    /** {@code WRITE FD-TRAN-CAT-BAL-RECORD}; false = write failed (duplicate). */
    boolean writeTcatBal(TcatKey key, BigDecimal balance);

    /** {@code REWRITE FD-TRAN-CAT-BAL-RECORD}; false = rewrite failed. */
    boolean rewriteTcatBal(TcatKey key, BigDecimal balance);

    /** {@code WRITE FD-TRANFILE-REC}; false = duplicate key (file status 22). */
    boolean writeTransaction(PostedTransaction tran);
}
