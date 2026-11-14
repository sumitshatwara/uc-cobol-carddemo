package com.carddemo.online.store;

/**
 * The VSAM files the online programs read through CICS (USRSEC, CXACAIX, ACCTDAT, CUSTDAT).
 * Every call returns the CICS RESP/RESP2 pair so the services can reproduce the legacy
 * response handling.  {@code READ ... UPDATE} and {@code REWRITE} map to
 * {@link #readAccountForUpdate}/{@link #rewriteAccount} etc.; the implementation owns the
 * unit of work ({@link #rollback()} = {@code SYNCPOINT ROLLBACK}).
 */
public interface OnlineStore {

    FileResult<UserSecurity> readUser(String userId);

    FileResult<CardXref> readXrefByAcct(String acctIdX);

    FileResult<AccountRecord> readAccount(String acctIdX);

    FileResult<CustomerRecord> readCustomer(String custIdX);

    FileResult<AccountRecord> readAccountForUpdate(String acctIdX);

    FileResult<CustomerRecord> readCustomerForUpdate(String custIdX);

    FileResult<Void> rewriteAccount(AccountRecord rec);

    FileResult<Void> rewriteCustomer(CustomerRecord rec);

    /** EXEC CICS SYNCPOINT ROLLBACK: undo rewrites of the current unit of work. */
    void rollback();

    /** EXEC CICS SYNCPOINT: commit the current unit of work. */
    default void syncpoint() {}
}
