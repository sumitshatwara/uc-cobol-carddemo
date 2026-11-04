package com.carddemo.online.files;

public interface OnlineFiles {
    FileResult<UserSecurity> readUserSec(String userId);
    FileResult<CardXref> readXrefByAcct(String acctId);
    FileResult<Account> readAccount(String acctId);
    FileResult<Customer> readCustomer(String custId);
    FileResult<Account> readAccountForUpdate(String acctId);
    FileResult<Customer> readCustomerForUpdate(String custId);
    FileResult<Void> rewriteAccount(Account account);
    FileResult<Void> rewriteCustomer(Customer customer);
    void syncpointRollback();
}
