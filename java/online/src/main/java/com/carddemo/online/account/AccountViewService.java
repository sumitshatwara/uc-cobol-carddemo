package com.carddemo.online.account;

import com.carddemo.common.clock.FrozenClock;
import com.carddemo.online.commarea.CardDemoCommarea;
import com.carddemo.online.commarea.ScreenHeader;
import com.carddemo.online.files.Account;
import com.carddemo.online.files.CardXref;
import com.carddemo.online.files.Customer;
import com.carddemo.online.files.FileResult;
import com.carddemo.online.files.OnlineFiles;
import org.springframework.stereotype.Service;

@Service
public class AccountViewService {
    public static final String INFO = "Enter or update id of account to display";
    private final OnlineFiles files;
    private final FrozenClock clock;

    public AccountViewService(OnlineFiles files, FrozenClock clock) {
        this.files = files;
        this.clock = clock;
    }

    public ViewResponse view(String rawAcctId, String aid, CardDemoCommarea commarea) {
        CardDemoCommarea state = commarea == null ? new CardDemoCommarea() : commarea;
        String key = rawAcctId == null ? "" : rawAcctId;
        String acctIdDisplay = key.isBlank() || "*".equals(key.trim()) ? "*" : key;
        String action = normalizeAid(aid);
        if ("PF3".equals(action)) {
            return new ViewResponse(header(), "", INFO, false, acctIdDisplay, null, null,
                    returnToCaller(state));
        }
        if (key.isBlank() || "*".equals(key.trim())) {
            state.acctId = "00000000000";
            return new ViewResponse(header(), "No input received", INFO, true, acctIdDisplay,
                    null, null, state);
        }
        if (key.length() != 11 || !key.chars().allMatch(Character::isDigit)
                || "00000000000".equals(key)) {
            return new ViewResponse(header(), "Account Filter must  be a non-zero 11 digit number",
                    INFO, true, acctIdDisplay, null, null, state);
        }
        state.acctId = key;
        String message = "";
        Account account = null;
        Customer customer = null;
        FileResult<CardXref> xref = files.readXrefByAcct(key);
        if (xref.resp() == FileResult.NOTFND) {
            message = notFoundXref(key, xref.resp(), xref.resp2());
            return new ViewResponse(header(), message, INFO, false, acctIdDisplay, null, null, state);
        }
        if (!xref.normal()) {
            message = fileError("CXACAIX", xref.resp(), xref.resp2());
            return new ViewResponse(header(), message, INFO, false, acctIdDisplay, null, null, state);
        }
        CardXref ref = xref.record();
        state.custId = ref.custId();
        state.cardNum = ref.cardNum();

        FileResult<Account> accountRead = files.readAccount(key);
        if (accountRead.normal()) {
            account = accountRead.record();
        } else if (accountRead.resp() == FileResult.NOTFND) {
            message = notFoundAccount(key, accountRead.resp(), accountRead.resp2());
        } else {
            message = fileError("ACCTDAT", accountRead.resp(), accountRead.resp2());
        }

        FileResult<Customer> customerRead = files.readCustomer(ref.custId());
        if (customerRead.normal()) {
            customer = customerRead.record();
            if (account == null && accountRead.resp() == FileResult.NOTFND) {
                account = Account.empty(key);
            }
        } else if (customerRead.resp() == FileResult.NOTFND) {
            if (message.isEmpty()) {
                message = notFoundCustomer(ref.custId(), customerRead.resp(), customerRead.resp2());
            }
        } else {
            message = fileError("CUSTDAT", customerRead.resp(), customerRead.resp2());
        }
        return new ViewResponse(header(), limit(message), INFO, false, acctIdDisplay, display(account),
                display(customer), state);
    }

    private CardDemoCommarea returnToCaller(CardDemoCommarea state) {
        state.toTranId = state.fromTranId == null || state.fromTranId.isBlank() ? "CM00" : state.fromTranId;
        state.toProgram = state.fromProgram == null || state.fromProgram.isBlank()
                ? "COMEN01C" : state.fromProgram;
        state.fromTranId = "CAVW";
        state.fromProgram = "COACTVWC";
        state.userType = "U";
        state.pgmContext = 0;
        state.lastMapset = "COACTVW";
        state.lastMap = "CACTVWA";
        return state;
    }

    private ScreenHeader header() {
        return ScreenHeader.from(clock, ScreenHeader.TITLE_01, ScreenHeader.TITLE_02,
                "CAVW", "COACTVWC");
    }

    private static CustomerView display(Customer customer) {
        if (customer == null) {
            return null;
        }
        String ssn = customer.ssn();
        if (ssn != null && ssn.length() == 9) {
            ssn = ssn.substring(0, 3) + "-" + ssn.substring(3, 5) + "-" + ssn.substring(5);
        }
        return new CustomerView(customer.custId(), ssn, customer.firstName(), customer.middleName(),
                customer.lastName(), customer.addrLine1(), customer.addrLine2(), customer.addrLine3(),
                customer.stateCd(), customer.zip(), customer.countryCd(), customer.phone1(),
                customer.phone2(), customer.govtId(), customer.dob(), customer.eftAccountId(),
                customer.priCardHolder(), customer.ficoScore());
    }

    private static AccountView display(Account account) {
        if (account == null) return null;
        return new AccountView(account.acctId(), account.activeStatus(), account.currBal().toPlainString(),
                account.creditLimit().toPlainString(), account.cashCreditLimit().toPlainString(),
                account.currCycCredit().toPlainString(), account.currCycDebit().toPlainString(),
                account.openDate(), account.expirationDate(), account.reissueDate(), account.groupId());
    }

    private static String normalizeAid(String aid) {
        if (aid == null || aid.isBlank() || "ENTER".equalsIgnoreCase(aid)) {
            return "ENTER";
        }
        String value = aid.trim().toUpperCase();
        if (value.matches("PF(1[3-9]|2[0-4])")) {
            int key = Integer.parseInt(value.substring(2)) - 12;
            return "PF" + key;
        }
        return value;
    }

    private static String notFoundXref(String id, int resp, int resp2) {
        return limit("Account:" + id + " not found in Cross ref file.  Resp:"
                + resp10(resp) + " Reas:" + resp10(resp2));
    }

    private static String notFoundAccount(String id, int resp, int resp2) {
        return limit("Account:" + id + " not found in Acct Master file.Resp:"
                + resp10(resp) + " Reas:" + resp10(resp2));
    }

    private static String notFoundCustomer(String id, int resp, int resp2) {
        return limit("CustId:" + id + " not found in customer master.Resp: "
                + resp10(resp) + " REAS:" + resp10(resp2));
    }

    private static String fileError(String file, int resp, int resp2) {
        return limit("File Error: " + "READ    " + " on " + (file + " ".repeat(9)).substring(0, 9)
                + " returned RESP " + resp10(resp) + ",RESP2 " + resp10(resp2));
    }

    private static String resp10(int code) {
        return String.format("%09d ", code);
    }

    private static String limit(String value) {
        return value.length() > 75 ? value.substring(0, 75) : value;
    }

    public record ViewResponse(ScreenHeader header, String errorMessage, String infoMessage,
                               boolean acctIdError, String acctIdDisplay, AccountView account, CustomerView customer,
                               CardDemoCommarea commarea) {
    }

    public record AccountView(String acctId, String activeStatus, String currBal, String creditLimit,
                              String cashCreditLimit, String currCycCredit, String currCycDebit,
                              String openDate, String expirationDate, String reissueDate, String groupId) {
    }

    public record CustomerView(String custId, String ssn, String firstName, String middleName,
                               String lastName, String addrLine1, String addrLine2, String addrLine3,
                               String stateCd, String zip, String countryCd, String phone1, String phone2,
                               String govtId, String dob, String eftAccountId, String priCardHolder,
                               int ficoScore) {
    }
}
