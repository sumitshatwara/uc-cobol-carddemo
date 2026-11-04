package com.carddemo.posting;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

public interface PostingStore {
    Optional<BigDecimal> accountIdForCard(String cardNumber);

    Optional<Account> account(BigDecimal accountId);

    Optional<BigDecimal> categoryBalance(BigDecimal accountId, String typeCode, BigDecimal categoryCode);

    void createCategoryBalance(BigDecimal accountId, String typeCode, BigDecimal categoryCode, BigDecimal balance);

    int updateCategoryBalance(BigDecimal accountId, String typeCode, BigDecimal categoryCode, BigDecimal balance);

    int updateAccount(Account account);

    void insertTransaction(Map<String, Object> transaction);

    void truncateTransactions();

    record Account(BigDecimal id, String activeStatus, BigDecimal currentBalance, BigDecimal creditLimit,
                   BigDecimal cashCreditLimit, String expirationDate, BigDecimal cycleCredit,
                   BigDecimal cycleDebit) {
        public Account withBalances(BigDecimal balance, BigDecimal credit, BigDecimal debit) {
            return new Account(id, activeStatus, balance, creditLimit, cashCreditLimit, expirationDate, credit, debit);
        }
    }

    final class DuplicateTransactionException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public DuplicateTransactionException(Throwable cause) {
            super(cause);
        }

        public DuplicateTransactionException() {
            super();
        }
    }
}
