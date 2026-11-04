package com.carddemo.online.account;

import com.carddemo.online.commarea.CardDemoCommarea;

public record AccountUpdateSession(CardDemoCommarea commarea, String changeAction,
                                   AccountUpdateDetails oldDetails, AccountUpdateDetails newDetails) {
    public String encodeCommarea() {
        StringBuilder value = new StringBuilder(2000);
        value.append(commarea == null ? new CardDemoCommarea().encode() : commarea.encode());
        value.append(fixed(changeAction, 1));
        appendDetails(value, oldDetails);
        appendDetails(value, newDetails);
        return value.append(" ".repeat(Math.max(0, 2000 - value.length())))
                .substring(0, 2000);
    }

    private static void appendDetails(StringBuilder target, AccountUpdateDetails d) {
        AccountUpdateDetails value = d == null ? new AccountUpdateDetails() : d;
        append(target, value.acctId,11); append(target,value.activeStatus,1);
        append(target,value.currBal,12); append(target,value.creditLimit,12); append(target,value.cashCreditLimit,12);
        append(target,compact(value.openDate),8); append(target,compact(value.expirationDate),8);
        append(target,compact(value.reissueDate),8); append(target,value.currCycCredit,12);
        append(target,value.currCycDebit,12); append(target,value.groupId,10);
        append(target,value.custId,9); append(target,value.firstName,25); append(target,value.middleName,25);
        append(target,value.lastName,25); append(target,value.addrLine1,50); append(target,value.addrLine2,50);
        append(target,value.city,50); append(target,value.stateCd,2); append(target,value.countryCd,3);
        append(target,value.zip,10); append(target,phone(value.phone1Area,value.phone1Prefix,value.phone1Line),15);
        append(target,phone(value.phone2Area,value.phone2Prefix,value.phone2Line),15);
        append(target,value.ssn1,3); append(target,value.ssn2,2); append(target,value.ssn3,4);
        append(target,value.govtId,20); append(target,compact(value.dob),8); append(target,value.eftAccountId,10);
        append(target,value.priCardHolder,1); append(target,value.ficoScore,3);
    }

    private static String compact(String value) {
        return value == null ? "" : value.replace("-", "");
    }

    private static String phone(String area, String prefix, String line) {
        return "(" + fixed(area,3) + ")" + fixed(prefix,3) + "-" + fixed(line,4) + "  ";
    }

    private static void append(StringBuilder target, String field, int width) {
        target.append(fixed(field, width));
    }

    private static String fixed(String value, int width) {
        String source = value == null ? "" : value;
        return source.length() >= width ? source.substring(0, width)
                : source + " ".repeat(width - source.length());
    }
}
