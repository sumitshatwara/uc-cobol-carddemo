package com.carddemo.online.files;

public record Customer(String custId, String firstName, String middleName, String lastName,
                       String addrLine1, String addrLine2, String addrLine3, String stateCd,
                       String countryCd, String zip, String phone1, String phone2, String ssn,
                       String govtId, String dob, String eftAccountId, String priCardHolder,
                       int ficoScore) {
}
