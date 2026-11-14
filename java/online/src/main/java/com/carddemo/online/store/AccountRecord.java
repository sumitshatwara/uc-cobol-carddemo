package com.carddemo.online.store;

import java.math.BigDecimal;

/** ACCTDAT record, copybook CVACT01Y. Table carddemo_online.account. Money is S9(10)V99. */
public record AccountRecord(
        long acctId,
        String activeStatus,
        BigDecimal currBal,
        BigDecimal creditLimit,
        BigDecimal cashCreditLimit,
        String openDate,
        String expirationDate,
        String reissueDate,
        BigDecimal currCycCredit,
        BigDecimal currCycDebit,
        String addrZip,
        String groupId) {}
