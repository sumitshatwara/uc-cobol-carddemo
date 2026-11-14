package com.carddemo.online.store;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * In-memory {@link OnlineStore} used by the unit tests (no database).  {@link #fail} forces the
 * next calls of one operation to return a given RESP/RESP2, which is how the tests reproduce
 * CICS conditions such as NOTOPEN, DISABLED or a record held by another task.
 */
public class InMemoryOnlineStore implements OnlineStore {

    /** Operation names accepted by {@link #fail}. */
    public enum Op { READ_USER, READ_XREF, READ_ACCT, READ_CUST, LOCK_ACCT, LOCK_CUST, REWRITE_ACCT, REWRITE_CUST }

    public final Map<String, UserSecurity> users = new LinkedHashMap<>();
    public final Map<Long, CardXref> xrefByAcct = new LinkedHashMap<>();
    public final Map<Long, AccountRecord> accounts = new LinkedHashMap<>();
    public final Map<Long, CustomerRecord> customers = new LinkedHashMap<>();
    private final Map<Op, int[]> failures = new HashMap<>();
    private Map<Long, AccountRecord> uowAccounts;
    private Map<Long, CustomerRecord> uowCustomers;
    public int rollbacks;
    public int syncpoints;

    public InMemoryOnlineStore fail(Op op, int resp, int resp2) {
        failures.put(op, new int[] {resp, resp2});
        return this;
    }

    private <T> FileResult<T> forced(Op op) {
        int[] f = failures.get(op);
        return f == null ? null : FileResult.error(f[0], f[1]);
    }

    private static Long key(String x) {
        try {
            return Long.parseLong(x.trim());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private <T> FileResult<T> get(Op op, Map<Long, T> map, String keyX) {
        FileResult<T> f = forced(op);
        if (f != null) {
            return f;
        }
        Long k = key(keyX);
        T rec = k == null ? null : map.get(k);
        return rec == null ? FileResult.notFound() : FileResult.ok(rec);
    }

    @Override
    public FileResult<UserSecurity> readUser(String userId) {
        FileResult<UserSecurity> f = forced(Op.READ_USER);
        if (f != null) {
            return f;
        }
        UserSecurity u = users.get(userId);
        return u == null ? FileResult.notFound() : FileResult.ok(u);
    }

    @Override
    public FileResult<CardXref> readXrefByAcct(String acctIdX) {
        return get(Op.READ_XREF, xrefByAcct, acctIdX);
    }

    @Override
    public FileResult<AccountRecord> readAccount(String acctIdX) {
        return get(Op.READ_ACCT, accounts, acctIdX);
    }

    @Override
    public FileResult<CustomerRecord> readCustomer(String custIdX) {
        return get(Op.READ_CUST, customers, custIdX);
    }

    @Override
    public FileResult<AccountRecord> readAccountForUpdate(String acctIdX) {
        beginUow();
        return get(Op.LOCK_ACCT, accounts, acctIdX);
    }

    @Override
    public FileResult<CustomerRecord> readCustomerForUpdate(String custIdX) {
        return get(Op.LOCK_CUST, customers, custIdX);
    }

    @Override
    public FileResult<Void> rewriteAccount(AccountRecord rec) {
        FileResult<Void> f = forced(Op.REWRITE_ACCT);
        if (f != null) {
            return f;
        }
        accounts.put(rec.acctId(), rec);
        return FileResult.ok(null);
    }

    @Override
    public FileResult<Void> rewriteCustomer(CustomerRecord rec) {
        FileResult<Void> f = forced(Op.REWRITE_CUST);
        if (f != null) {
            return f;
        }
        customers.put(rec.custId(), rec);
        return FileResult.ok(null);
    }

    private void beginUow() {
        uowAccounts = new LinkedHashMap<>(accounts);
        uowCustomers = new LinkedHashMap<>(customers);
    }

    @Override
    public void rollback() {
        rollbacks++;
        if (uowAccounts != null) {
            accounts.clear();
            accounts.putAll(uowAccounts);
            customers.clear();
            customers.putAll(uowCustomers);
        }
    }

    @Override
    public void syncpoint() {
        syncpoints++;
        uowAccounts = null;
        uowCustomers = null;
    }
}
