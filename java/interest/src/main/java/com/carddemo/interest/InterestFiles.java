package com.carddemo.interest;

import java.math.BigDecimal;
import java.util.Objects;

public interface InterestFiles {
    String openTcatBal();

    String openXref();

    String openDiscGrp();

    String openAccount();

    String openTransaction();

    ReadResult<TranCatBal> readNextTcatBal();

    ReadResult<Account> readAccount(long accountId);

    ReadResult<CardXref> readXrefByAccount(long accountId);

    ReadResult<DisclosureGroup> readDiscGrp(String groupId, String typeCd, int categoryCd);

    String rewriteAccount(Account account);

    String writeTransaction(Transaction transaction);

    String closeTcatBal();

    String closeXref();

    String closeDiscGrp();

    String closeAccount();

    String closeTransaction();

    record ReadResult<T>(String status, T record) {
        public ReadResult {
            Objects.requireNonNull(status);
        }
    }

    record TranCatBal(long accountId, String typeCd, int categoryCd, BigDecimal balance) {
        public TranCatBal {
            typeCd = Objects.requireNonNullElse(typeCd, "");
            balance = money(balance);
        }
    }

    record Account(
            long accountId,
            String activeStatus,
            BigDecimal currentBalance,
            BigDecimal creditLimit,
            BigDecimal cashCreditLimit,
            String openDate,
            String expirationDate,
            String reissueDate,
            BigDecimal currentCycleCredit,
            BigDecimal currentCycleDebit,
            String zip,
            String groupId) {
        public Account {
            activeStatus = Objects.requireNonNullElse(activeStatus, "");
            currentBalance = money(currentBalance);
            creditLimit = money(creditLimit);
            cashCreditLimit = money(cashCreditLimit);
            openDate = Objects.requireNonNullElse(openDate, "");
            expirationDate = Objects.requireNonNullElse(expirationDate, "");
            reissueDate = Objects.requireNonNullElse(reissueDate, "");
            currentCycleCredit = money(currentCycleCredit);
            currentCycleDebit = money(currentCycleDebit);
            zip = Objects.requireNonNullElse(zip, "");
            groupId = Objects.requireNonNullElse(groupId, "");
        }
    }

    record CardXref(String cardNumber, long customerId, long accountId) {
        public CardXref {
            cardNumber = Objects.requireNonNullElse(cardNumber, "");
        }
    }

    record DisclosureGroup(String groupId, String typeCd, int categoryCd, BigDecimal interestRate) {
        public DisclosureGroup {
            groupId = Objects.requireNonNullElse(groupId, "");
            typeCd = Objects.requireNonNullElse(typeCd, "");
            interestRate = money(interestRate);
        }
    }

    record Transaction(
            String id,
            String typeCd,
            int categoryCd,
            String source,
            String description,
            BigDecimal amount,
            long merchantId,
            String merchantName,
            String merchantCity,
            String merchantZip,
            String cardNumber,
            String originalTimestamp,
            String processTimestamp) {
        public Transaction {
            id = Objects.requireNonNullElse(id, "");
            typeCd = Objects.requireNonNullElse(typeCd, "");
            source = Objects.requireNonNullElse(source, "");
            description = Objects.requireNonNullElse(description, "");
            amount = money(amount);
            merchantName = Objects.requireNonNullElse(merchantName, "");
            merchantCity = Objects.requireNonNullElse(merchantCity, "");
            merchantZip = Objects.requireNonNullElse(merchantZip, "");
            cardNumber = Objects.requireNonNullElse(cardNumber, "");
            originalTimestamp = Objects.requireNonNullElse(originalTimestamp, "");
            processTimestamp = Objects.requireNonNullElse(processTimestamp, "");
        }
    }

    private static BigDecimal money(BigDecimal value) {
        return Objects.requireNonNullElse(value, BigDecimal.ZERO).setScale(2);
    }
}
