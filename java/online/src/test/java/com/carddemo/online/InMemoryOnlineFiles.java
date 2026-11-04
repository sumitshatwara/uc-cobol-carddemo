package com.carddemo.online;

import com.carddemo.online.files.Account;
import com.carddemo.online.files.CardXref;
import com.carddemo.online.files.Customer;
import com.carddemo.online.files.FileResult;
import com.carddemo.online.files.OnlineFiles;
import com.carddemo.online.files.UserSecurity;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

final class InMemoryOnlineFiles implements OnlineFiles {
    final List<String> calls = new ArrayList<>();
    UserSecurity user = new UserSecurity("USER0001", "JOHN", "SAMPLE", "PASSWORD", "U");
    Account account = new Account("00000000001", "Y", new BigDecimal("194.00"),
            new BigDecimal("5000.00"), new BigDecimal("1500.00"), "2014-11-20",
            "2025-05-20", "2025-05-20", new BigDecimal("1500.00"),
            new BigDecimal("265.44"), "10001", "STANDARD");
    Customer customer = new Customer("000000001", "JOHN", "Q", "SAMPLE", "100 MAIN STREET",
            "APT 1", "NEW YORK", "NY", "USA", "10001", "(212)555-0100", "(212)555-0101",
            "123456789", "DL0000001", "1980-01-15", "0000000001", "Y", 720);
    int userResp;
    int xrefResp;
    int accountResp;
    int customerResp;
    int accountLockResp;
    int customerLockResp;
    int accountRewriteResp;
    int customerRewriteResp;
    boolean rollback;
    String xrefCustId = "000000001";
    private Account accountBeforeRewrite;
    private Customer customerBeforeRewrite;

    private <T> FileResult<T> result(int code, T value) {
        return new FileResult<>(code, code == 0 ? 0 : 17, code == 0 ? value : null);
    }

    @Override
    public FileResult<UserSecurity> readUserSec(String userId) {
        calls.add("USRSEC:" + userId);
        return result(userResp, user);
    }

    @Override
    public FileResult<CardXref> readXrefByAcct(String acctId) {
        calls.add("CXACAIX:" + acctId);
        return result(xrefResp, new CardXref("4111111111111111", xrefCustId, acctId));
    }

    @Override
    public FileResult<Account> readAccount(String acctId) {
        calls.add("ACCTDAT:" + acctId);
        return result(accountResp, account);
    }

    @Override
    public FileResult<Customer> readCustomer(String custId) {
        calls.add("CUSTDAT:" + custId);
        return result(customerResp, customer);
    }

    @Override
    public FileResult<Account> readAccountForUpdate(String acctId) {
        calls.add("READ UPDATE ACCTDAT");
        return result(accountLockResp, account);
    }

    @Override
    public FileResult<Customer> readCustomerForUpdate(String custId) {
        calls.add("READ UPDATE CUSTDAT");
        return result(customerLockResp, customer);
    }

    @Override
    public FileResult<Void> rewriteAccount(Account value) {
        calls.add("REWRITE ACCTDAT");
        if (accountRewriteResp == 0) {
            accountBeforeRewrite = account;
            customerBeforeRewrite = customer;
            account = value;
        }
        return new FileResult<>(accountRewriteResp, 0, null);
    }

    @Override
    public FileResult<Void> rewriteCustomer(Customer value) {
        calls.add("REWRITE CUSTDAT");
        if (customerRewriteResp == 0) {
            customerBeforeRewrite = customer;
            customer = value;
        }
        return new FileResult<>(customerRewriteResp, 0, null);
    }

    @Override
    public void syncpointRollback() {
        calls.add("ROLLBACK");
        rollback = true;
        if (accountBeforeRewrite != null) {
            account = accountBeforeRewrite;
        }
        if (customerBeforeRewrite != null) {
            customer = customerBeforeRewrite;
        }
    }
}
