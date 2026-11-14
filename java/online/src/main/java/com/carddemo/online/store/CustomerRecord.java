package com.carddemo.online.store;

/** CUSTDAT record, copybook CVCUS01Y. Table carddemo_online.customer. */
public record CustomerRecord(
        long custId,
        String firstName,
        String middleName,
        String lastName,
        String addrLine1,
        String addrLine2,
        String addrLine3,
        String addrStateCd,
        String addrCountryCd,
        String addrZip,
        String phoneNum1,
        String phoneNum2,
        long ssn,
        String govtIssuedId,
        String dob,
        String eftAccountId,
        String priCardHolderInd,
        int ficoCreditScore) {}
