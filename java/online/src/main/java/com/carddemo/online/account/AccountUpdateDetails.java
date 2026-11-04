package com.carddemo.online.account;

import com.carddemo.online.files.Account;
import com.carddemo.online.files.Customer;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public class AccountUpdateDetails {
    public String acctId = "";
    public String activeStatus = "";
    public String currBal = "";
    public String creditLimit = "";
    public String cashCreditLimit = "";
    public String openDate = "";
    public String expirationDate = "";
    public String reissueDate = "";
    public String currCycCredit = "";
    public String currCycDebit = "";
    public String groupId = "";
    public String custId = "";
    public String firstName = "";
    public String middleName = "";
    public String lastName = "";
    public String addrLine1 = "";
    public String addrLine2 = "";
    public String city = "";
    public String stateCd = "";
    public String countryCd = "";
    public String zip = "";
    public String phone1Area = "";
    public String phone1Prefix = "";
    public String phone1Line = "";
    public String phone2Area = "";
    public String phone2Prefix = "";
    public String phone2Line = "";
    public String ssn1 = "";
    public String ssn2 = "";
    public String ssn3 = "";
    public String govtId = "";
    public String dob = "";
    public String eftAccountId = "";
    public String priCardHolder = "";
    public String ficoScore = "";

    public AccountUpdateDetails copy() {
        AccountUpdateDetails result = new AccountUpdateDetails();
        result.acctId=acctId; result.activeStatus=activeStatus; result.currBal=currBal;
        result.creditLimit=creditLimit; result.cashCreditLimit=cashCreditLimit;
        result.openDate=openDate; result.expirationDate=expirationDate; result.reissueDate=reissueDate;
        result.currCycCredit=currCycCredit; result.currCycDebit=currCycDebit; result.groupId=groupId;
        result.custId=custId; result.firstName=firstName; result.middleName=middleName;
        result.lastName=lastName; result.addrLine1=addrLine1; result.addrLine2=addrLine2;
        result.city=city; result.stateCd=stateCd; result.countryCd=countryCd; result.zip=zip;
        result.phone1Area=phone1Area; result.phone1Prefix=phone1Prefix; result.phone1Line=phone1Line;
        result.phone2Area=phone2Area; result.phone2Prefix=phone2Prefix; result.phone2Line=phone2Line;
        result.ssn1=ssn1; result.ssn2=ssn2; result.ssn3=ssn3; result.govtId=govtId;
        result.dob=dob; result.eftAccountId=eftAccountId; result.priCardHolder=priCardHolder;
        result.ficoScore=ficoScore;
        return result;
    }

    public static AccountUpdateDetails from(Account account, Customer customer) {
        AccountUpdateDetails result = new AccountUpdateDetails();
        result.acctId=account.acctId(); result.activeStatus=account.activeStatus();
        result.currBal=account.currBal().toPlainString(); result.creditLimit=account.creditLimit().toPlainString();
        result.cashCreditLimit=account.cashCreditLimit().toPlainString();
        result.openDate=compact(account.openDate()); result.expirationDate=compact(account.expirationDate());
        result.reissueDate=compact(account.reissueDate());
        result.currCycCredit=account.currCycCredit().toPlainString();
        result.currCycDebit=account.currCycDebit().toPlainString(); result.groupId=account.groupId();
        result.custId=customer.custId(); result.firstName=customer.firstName();
        result.middleName=customer.middleName(); result.lastName=customer.lastName();
        result.addrLine1=customer.addrLine1(); result.addrLine2=customer.addrLine2();
        result.city=customer.addrLine3(); result.stateCd=customer.stateCd();
        result.countryCd=customer.countryCd(); result.zip=customer.zip();
        String[] phone1=phoneParts(customer.phone1()), phone2=phoneParts(customer.phone2());
        result.phone1Area=phone1[0]; result.phone1Prefix=phone1[1]; result.phone1Line=phone1[2];
        result.phone2Area=phone2[0]; result.phone2Prefix=phone2[1]; result.phone2Line=phone2[2];
        String ssn=customer.ssn() == null ? "" : customer.ssn();
        result.ssn1=part(ssn, 0, 3); result.ssn2=part(ssn, 3, 5); result.ssn3=part(ssn, 5, 9);
        result.govtId=customer.govtId(); result.dob=compact(customer.dob());
        result.eftAccountId=customer.eftAccountId(); result.priCardHolder=customer.priCardHolder();
        result.ficoScore=Integer.toString(customer.ficoScore());
        return result;
    }

    public Account account() {
        return new Account(acctId, activeStatus, amount(currBal), amount(creditLimit), amount(cashCreditLimit),
                dashed(openDate), dashed(expirationDate), dashed(reissueDate),
                amount(currCycCredit), amount(currCycDebit), zip, groupId);
    }

    public Customer customer() {
        return new Customer(custId, firstName, middleName, lastName, addrLine1, addrLine2, city, stateCd,
                countryCd, zip, phone(phone1Area, phone1Prefix, phone1Line),
                phone(phone2Area, phone2Prefix, phone2Line), digits(ssn1 + ssn2 + ssn3, 9), govtId,
                dashed(dob), eftAccountId, priCardHolder, integer(ficoScore));
    }

    private static String phone(String area, String prefix, String line) {
        return "(" + fixed(area, 3) + ")" + fixed(prefix, 3) + "-" + fixed(line, 4) + "  ";
    }

    private static String[] phoneParts(String value) {
        String phone = value == null ? "" : value;
        String digits = phone.replaceAll("\\D", "");
        if (digits.length() >= 10) {
            return new String[]{digits.substring(0, 3), digits.substring(3, 6), digits.substring(6, 10)};
        }
        return new String[]{"", "", ""};
    }

    private static String fixed(String value, int width) {
        String text = value == null ? "" : value;
        return text.length() > width ? text.substring(0, width) : text + " ".repeat(width - text.length());
    }

    private static String part(String value, int begin, int end) {
        String source = value.length() >= end ? value : "0".repeat(end - value.length()) + value;
        return source.substring(begin, end);
    }

    private static String compact(String value) {
        return value == null ? "" : value.replace("-", "");
    }

    private static String dashed(String value) {
        if (value == null || value.length() != 8 || !value.chars().allMatch(Character::isDigit)) {
            return value == null ? "" : value;
        }
        return value.substring(0, 4) + "-" + value.substring(4, 6) + "-" + value.substring(6, 8);
    }

    private static BigDecimal amount(String value) {
        try {
            return new BigDecimal(value.trim()).setScale(2);
        } catch (RuntimeException e) {
            return BigDecimal.ZERO.setScale(2);
        }
    }

    private static String digits(String value, int width) {
        String digits = value == null ? "" : value.replaceAll("\\D", "");
        if (digits.isEmpty()) return "0";
        return digits.length() > width ? digits.substring(digits.length() - width) : digits;
    }

    private static int integer(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (RuntimeException e) {
            return 0;
        }
    }
}
