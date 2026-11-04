package com.carddemo.online.files;

import java.math.BigDecimal;

public record Account(String acctId, String activeStatus, BigDecimal currBal,
                      BigDecimal creditLimit, BigDecimal cashCreditLimit,
                      String openDate, String expirationDate, String reissueDate,
                      BigDecimal currCycCredit, BigDecimal currCycDebit,
                      String addrZip, String groupId) {
    public Account {
        currBal = money(currBal);
        creditLimit = money(creditLimit);
        cashCreditLimit = money(cashCreditLimit);
        currCycCredit = money(currCycCredit);
        currCycDebit = money(currCycDebit);
    }

    public static Account empty(String acctId) {
        return new Account(acctId, "", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                "", "", "", BigDecimal.ZERO, BigDecimal.ZERO, "", "");
    }

    private static BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2);
    }
}
