package com.carddemo.online.account;

import java.math.BigDecimal;

/**
 * ACUP-OLD-DETAILS / ACUP-NEW-DETAILS of COACTUPC.  Text fields hold the PIC X storage (blank
 * padded; LOW-VALUES = "not supplied" are U+0000).  Money fields hold the S9(10)V99 value, or
 * null when the storage is not a number (INITIALIZEd spaces: the input was blank or not valid).
 */
public class AcupDetails {
    public String acctIdX = "";
    public String activeStatus = "";
    public BigDecimal currBal;
    public BigDecimal creditLimit;
    public BigDecimal cashCreditLimit;
    public String openYear = "";
    public String openMon = "";
    public String openDay = "";
    public String expYear = "";
    public String expMon = "";
    public String expDay = "";
    public String reissueYear = "";
    public String reissueMon = "";
    public String reissueDay = "";
    public BigDecimal currCycCredit;
    public BigDecimal currCycDebit;
    public String groupId = "";
    public String custIdX = "";
    public String firstName = "";
    public String middleName = "";
    public String lastName = "";
    public String addrLine1 = "";
    public String addrLine2 = "";
    public String addrLine3 = "";
    public String stateCd = "";
    public String countryCd = "";
    public String zip = "";
    public String phone1a = "";
    public String phone1b = "";
    public String phone1c = "";
    public String phone2a = "";
    public String phone2b = "";
    public String phone2c = "";
    /** Old: the stored 15-byte phone numbers (9700 compares them whole). */
    public String phone1 = "";
    public String phone2 = "";
    public String ssn1 = "";
    public String ssn2 = "";
    public String ssn3 = "";
    public String govtId = "";
    public String dobYear = "";
    public String dobMon = "";
    public String dobDay = "";
    public String eftAccountId = "";
    public String priHolder = "";
    public String ficoX = "";

    public String ssnX() {
        return ssn1 + ssn2 + ssn3;
    }

    public String dob8() {
        return dobYear + dobMon + dobDay;
    }
}
