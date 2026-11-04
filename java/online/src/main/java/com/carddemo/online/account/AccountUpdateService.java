package com.carddemo.online.account;

import com.carddemo.common.clock.FrozenClock;
import com.carddemo.online.commarea.CardDemoCommarea;
import com.carddemo.online.files.Account;
import com.carddemo.online.files.CardXref;
import com.carddemo.online.files.Customer;
import com.carddemo.online.files.FileResult;
import com.carddemo.online.files.OnlineFiles;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

@Service
public class AccountUpdateService {
    private final OnlineFiles files;
    private final FrozenClock clock;
    private final TransactionTemplate transactions;

    public AccountUpdateService(OnlineFiles files, FrozenClock clock) {
        this(files, clock, null);
    }

    @Autowired
    public AccountUpdateService(OnlineFiles files, FrozenClock clock, TransactionTemplate transactions) {
        this.files = files;
        this.clock = clock;
        this.transactions = transactions;
    }

    public UpdateResponse search(String acctId, CardDemoCommarea commarea) {
        CardDemoCommarea state = commarea == null ? new CardDemoCommarea() : commarea;
        if (acctId == null || acctId.isBlank() || "*".equals(acctId.trim())) {
            return result(new AccountUpdateSession(state, " ", null, null),
                    "No input received", "Enter or update id of account to update", List.of("acctId"));
        }
        if (!validAccountId(acctId)) {
            return result(new AccountUpdateSession(state, " ", null, null),
                    "Account Number if supplied must be a 11 digit Non-Zero Number",
                    "Enter or update id of account to update",
                    List.of("acctId"));
        }
        FileResult<CardXref> xref = files.readXrefByAcct(acctId);
        if (!xref.normal()) {
            return result(new AccountUpdateSession(state, " ", null, null),
                    xref.resp() == 13 ? "Account:" + acctId + " not found in Cross ref file.  Resp:"
                            + resp10(xref.resp()) + " Reas:" + resp10(xref.resp2())
                            : fileError("CXACAIX", xref.resp(), xref.resp2()),
                    "", List.of("acctId"));
        }
        state.acctId = acctId;
        state.custId = xref.record().custId();
        state.cardNum = xref.record().cardNum();
        FileResult<Account> account = files.readAccount(acctId);
        FileResult<Customer> customer = files.readCustomer(xref.record().custId());
        String message = "";
        if (!account.normal()) {
            message = account.resp() == 13 ? "Account:" + acctId
                    + " not found in Acct Master file.Resp:" + resp10(account.resp()) + " Reas:"
                    + resp10(account.resp2()) : fileError("ACCTDAT", account.resp(), account.resp2());
        }
        if (!customer.normal()) {
            message = customer.resp() == 13 && message.isEmpty()
                    ? "CustId:" + xref.record().custId() + " not found in customer master.Resp: "
                    + resp10(customer.resp()) + " REAS:" + resp10(customer.resp2())
                    : customer.resp() == 13 ? message : fileError("CUSTDAT", customer.resp(), customer.resp2());
        }
        if (!customer.normal()) {
            return result(new AccountUpdateSession(state, " ", null, null), limit(message), "", List.of());
        }
        Account shownAccount = account.normal() ? account.record() : Account.empty(acctId);
        AccountUpdateDetails details = AccountUpdateDetails.from(shownAccount, customer.record());
        return result(new AccountUpdateSession(state, "S", details.copy(), details.copy()),
                limit(message), "Update account details presented above.", List.of());
    }

    public UpdateResponse update(String acctId, String aid, AccountUpdateSession supplied,
                                 Map<String, ?> input) {
        AccountUpdateSession session = supplied == null
                ? new AccountUpdateSession(new CardDemoCommarea(), " ", null, null) : supplied;
        CardDemoCommarea state = session.commarea() == null ? new CardDemoCommarea() : session.commarea();
        String action = normalizeAid(aid);
        if ("PF3".equals(action)) {
            state.toTranId = blank(state.fromTranId) ? "CM00" : state.fromTranId;
            state.toProgram = blank(state.fromProgram) ? "COMEN01C" : state.fromProgram;
            state.fromTranId = "CAUP"; state.fromProgram = "COACTUPC";
            state.userType = "U"; state.pgmContext = 0;
            state.lastMapset = "COACTUP"; state.lastMap = "CAUPA";
            return result(new AccountUpdateSession(state, session.changeAction(),
                    session.oldDetails(), session.newDetails()), "", "", List.of());
        }
        String currentAction = session.changeAction() == null ? " " : session.changeAction();
        if ("PF12".equals(action)) {
            return search(acctId == null ? state.acctId : acctId, state);
        }
        if (("C".equals(currentAction) || "L".equals(currentAction) || "F".equals(currentAction))
                && !"PF5".equals(action)) {
            state.acctId = "00000000000";
            state.cardNum = "0000000000000000";
            return result(new AccountUpdateSession(state, " ", null, null), "",
                    "Enter account id to change", List.of());
        }
        if (session.oldDetails() == null || session.newDetails() == null || blank(session.oldDetails().acctId)) {
            String key = acctId == null ? text(input, "acctId", state.acctId) : acctId;
            return search(key, state);
        }
        if ("PF5".equals(action) && "N".equals(currentAction)) {
            return save(session);
        }
        if ("N".equals(currentAction)) {
            return result(session, "", "Changes validated.Press F5 to save", List.of());
        }
        if (!"ENTER".equals(action) && !"PF5".equals(action)) {
            action = "ENTER";
        }
        AccountUpdateDetails changed = session.newDetails().copy();
        overlay(changed, input);
        normalizeAmounts(changed);
        List<String> differences = changedFields(session.oldDetails(), changed);
        if (differences.isEmpty()) {
            return result(new AccountUpdateSession(state, "S", session.oldDetails(), changed),
                    "No change detected with respect to values fetched.",
                    "Update account details presented above.", List.of());
        }
        Validation validation = validate(changed);
        return result(new AccountUpdateSession(state, validation.message.isEmpty() ? "N" : "E",
                        session.oldDetails(), validation.details),
                fixed75(validation.message), validation.message.isEmpty()
                        ? "Changes validated.Press F5 to save" : "Update account details presented above.",
                validation.flags);
    }

    private UpdateResponse save(AccountUpdateSession session) {
        SaveState save = new SaveState();
        if (transactions == null) {
            saveInTransaction(session, save);
        } else {
            transactions.execute(status -> {
                saveInTransaction(session, save);
                if (save.rollback) status.setRollbackOnly();
                return null;
            });
        }
        return result(new AccountUpdateSession(session.commarea(), save.action,
                session.oldDetails(), session.newDetails()), fixed75(save.error), save.info, List.of());
    }

    private void saveInTransaction(AccountUpdateSession session, SaveState state) {
        AccountUpdateDetails old = session.oldDetails();
        FileResult<Account> account = files.readAccountForUpdate(old.acctId);
        if (!account.normal()) {
            state.action = "L";
            state.error = "Could not lock account record for update";
            state.info = "Changes unsuccessful. Please try again";
            return;
        }
        FileResult<Customer> customer = files.readCustomerForUpdate(old.custId);
        if (!customer.normal()) {
            state.action = "C";
            state.error = "Could not lock customer record for update";
            state.info = "Changes committed to database";
            return;
        }
        AccountUpdateDetails current = AccountUpdateDetails.from(account.record(), customer.record());
        if (!changedFields(old, current).isEmpty()) {
            state.action = "S";
            state.error = "Record changed by some one else. Please review";
            return;
        }
        AccountUpdateDetails next = session.newDetails();
        if (!files.rewriteAccount(next.account()).normal()) {
            state.action = "F";
            state.error = "Update of record failed";
            state.info = "Changes unsuccessful. Please try again";
            return;
        }
        if (!files.rewriteCustomer(next.customer()).normal()) {
            files.syncpointRollback();
            state.rollback = true;
            state.action = "F";
            state.error = "Update of record failed";
            state.info = "Changes unsuccessful. Please try again";
            return;
        }
        state.action = "C";
        state.info = "Changes committed to database";
    }

    private Validation validate(AccountUpdateDetails value) {
        List<String> flags = new ArrayList<>();
        String[] firstError = {""};
        Consumer<String> yn = name -> {
            String val = get(value, name);
            String label = label(name);
            if (blank(val) || "0".equals(val) || "*".equals(val)) {
                fail(firstError, flags, name, label + " must be supplied.");
            } else if (!"Y".equals(val) && !"N".equals(val)) {
                fail(firstError, flags, name, label + " must be Y or N.");
            }
        };
        yn.accept("activeStatus");
        date(value.openDate, "Open Date", "openDate", firstError, flags);
        signed(value.creditLimit, "Credit Limit", "creditLimit", firstError, flags);
        date(value.expirationDate, "Expiry Date", "expirationDate", firstError, flags);
        signed(value.cashCreditLimit, "Cash Credit Limit", "cashCreditLimit", firstError, flags);
        date(value.reissueDate, "Reissue Date", "reissueDate", firstError, flags);
        signed(value.currBal, "Current Balance", "currBal", firstError, flags);
        signed(value.currCycCredit, "Current Cycle Credit Limit", "currCycCredit", firstError, flags);
        signed(value.currCycDebit, "Current Cycle Debit Limit", "currCycDebit", firstError, flags);
        ssn(value, firstError, flags);
        date(value.dob, "Date of Birth", "dob", firstError, flags);
        if (validDate(value.dob)) {
            try {
                LocalDate dob = toDate(value.dob);
                if (!LocalDate.now(clock).isAfter(dob)) {
                    fail(firstError, flags, "dob", "Date of Birth:cannot be in the future ");
                }
            } catch (RuntimeException ignored) {
            }
        }
        numericRequired(value.ficoScore, "FICO Score", "ficoScore", 3, firstError, flags);
        if (digits(value.ficoScore) && !blank(value.ficoScore)) {
            int fico = Integer.parseInt(value.ficoScore.trim());
            if (fico < 300 || fico > 850) {
                fail(firstError, flags, "ficoScore", "FICO Score: should be between 300 and 850");
            }
        }
        alpha(value.firstName, "First Name", "firstName", true, firstError, flags);
        alpha(value.middleName, "Middle Name", "middleName", false, firstError, flags);
        alpha(value.lastName, "Last Name", "lastName", true, firstError, flags);
        if (blank(value.addrLine1)) fail(firstError, flags, "addrLine1", "Address Line 1 must be supplied.");
        if (!ValidationCodes.STATES.contains(value.stateCd)) {
            fail(firstError, flags, "stateCd", "State: is not a valid state code");
        }
        numericRequired(value.zip, "Zip", "zip", 5, firstError, flags);
        alpha(value.city, "City", "city", true, firstError, flags);
        alpha(value.countryCd, "Country", "countryCd", true, firstError, flags);
        phone(value.phone1Area, value.phone1Prefix, value.phone1Line, "Phone Number 1",
                "phone1", firstError, flags);
        phone(value.phone2Area, value.phone2Prefix, value.phone2Line, "Phone Number 2",
                "phone2", firstError, flags);
        numericRequired(value.eftAccountId, "EFT Account Id", "eftAccountId", 10, firstError, flags);
        yn.accept("priCardHolder");
        if (!flags.contains("stateCd") && !flags.contains("zip")
                && !ValidationCodes.STATE_ZIP2.contains(value.stateCd + value.zip.substring(0, 2))) {
            fail(firstError, flags, "stateCd", "Invalid zip code for state");
            if (!flags.contains("zip")) flags.add("zip");
        }
        normalizeAmounts(value);
        return new Validation(value, firstError[0], flags);
    }

    private void date(String input, String name, String field, String[] first, List<String> flags) {
        String value = compact(input);
        if (value.length() < 4 || value.substring(0, 4).isBlank()) {
            fail(first, flags, field, name + " : Year must be supplied.");
        } else if (!value.substring(0, 4).chars().allMatch(Character::isDigit)) {
            fail(first, flags, field, name + " must be 4 digit number.");
        } else {
            String year = value.substring(0, 4);
            if (!(year.startsWith("19") || year.startsWith("20"))) {
                fail(first, flags, field, name + " : Century is not valid.");
            } else if (value.length() < 6 || !digits(value.substring(4, Math.min(6, value.length())))) {
                fail(first, flags, field, name + ": Month must be a number between 1 and 12.");
            } else {
                int month = Integer.parseInt(value.substring(4, 6));
                if (month < 1 || month > 12) {
                    fail(first, flags, field, name + ": Month must be a number between 1 and 12.");
                } else if (value.length() < 8 || !digits(value.substring(6, 8))) {
                    fail(first, flags, field, name + ":day must be a number between 1 and 31.");
                } else {
                    int day = Integer.parseInt(value.substring(6, 8));
                    String error = dateError(Integer.parseInt(year), month, day);
                    if (error != null) fail(first, flags, field, name + error);
                }
            }
        }
    }

    private static String dateError(int year, int month, int day) {
        if (day < 1 || day > 31) return ":day must be a number between 1 and 31.";
        if (day == 31 && List.of(4, 6, 9, 11).contains(month)) return ":Cannot have 31 days in this month.";
        if (month == 2 && day == 30) return ":Cannot have 30 days in this month.";
        if (month == 2 && day == 29
                && (year % 100 == 0 ? year % 400 != 0 : year % 4 != 0)) {
            return ":Not a leap year.Cannot have 29 days in this month.";
        }
        try {
            LocalDate.of(year, month, day);
        } catch (RuntimeException e) {
            return ":day must be a number between 1 and 31.";
        }
        return null;
    }

    private static void signed(String value, String name, String field, String[] first, List<String> flags) {
        if (blank(value) || "*".equals(value)) {
            fail(first, flags, field, name + " must be supplied.");
        } else if (parseCurrency(value) == null) {
            fail(first, flags, field, name + " is not valid");
        }
    }

    private static void numericRequired(String value, String name, String field, int width,
                                        String[] first, List<String> flags) {
        if (blank(value) || "*".equals(value)) {
            fail(first, flags, field, name + " must be supplied.");
        } else if (value.trim().length() != width || !digits(value)) {
            fail(first, flags, field, name + " must be all numeric.");
        } else if (value.chars().allMatch(c -> c == '0')) {
            fail(first, flags, field, name + " must not be zero.");
        }
    }

    private static void alpha(String value, String name, String field, boolean required,
                              String[] first, List<String> flags) {
        if (blank(value) && required) {
            fail(first, flags, field, name + " must be supplied.");
        } else if (!blank(value) && !value.chars().allMatch(c -> Character.isLetter(c) || c == ' ')) {
            fail(first, flags, field, name + " can have alphabets only.");
        }
    }

    private static void phone(String area, String prefix, String line, String name, String field,
                              String[] first, List<String> flags) {
        if (blank(area) && blank(prefix)) return;
        if (blank(area)) {
            fail(first, flags, field + "Area", name + ": Area code must be supplied.");
        } else if (area.trim().length() != 3 || !digits(area)) {
            fail(first, flags, field + "Area", name + ": Area code must be A 3 digit number.");
        } else if ("000".equals(area.trim())) {
            fail(first, flags, field + "Area", name + ": Area code cannot be zero");
        } else if (!ValidationCodes.GENERAL_AREAS.contains(area.trim())) {
            fail(first, flags, field + "Area", name + ": Not valid North America general purpose area code");
        }
        if (!blank(prefix)) {
            if (prefix.trim().length() != 3 || !digits(prefix)) {
                fail(first, flags, field + "Prefix", name + ": Prefix code must be A 3 digit number.");
            } else if ("000".equals(prefix.trim())) {
                fail(first, flags, field + "Prefix", name + ": Prefix code cannot be zero");
            }
        } else {
            fail(first, flags, field + "Prefix", name + ": Prefix code must be supplied.");
        }
        if (!blank(line)) {
            if (line.trim().length() != 4 || !digits(line)) {
                fail(first, flags, field + "Line", name + ": Line number code must be A 4 digit number.");
            } else if ("0000".equals(line.trim())) {
                fail(first, flags, field + "Line", name + ": Line number code cannot be zero");
            }
        } else {
            fail(first, flags, field + "Line", name + ": Line number code must be supplied.");
        }
    }

    private static void ssn(AccountUpdateDetails value, String[] first, List<String> flags) {
        String a = value.ssn1, b = value.ssn2, c = value.ssn3;
        if (blank(a)) fail(first, flags, "ssn1", "SSN: First 3 chars must be supplied.");
        else if (!digits(a)) fail(first, flags, "ssn1", "SSN: First 3 chars must be all numeric.");
        else if ("000".equals(a)) fail(first, flags, "ssn1", "SSN: First 3 chars must not be zero.");
        else if ("666".equals(a) || Integer.parseInt(a) >= 900) {
            fail(first, flags, "ssn1",
                    "SSN: First 3 chars: should not be 000, 666, or between 900 and 999");
        }
        if (blank(b)) fail(first, flags, "ssn2", "SSN 4th & 5th chars must be supplied.");
        else if (!digits(b)) fail(first, flags, "ssn2", "SSN 4th & 5th chars must be all numeric.");
        else if ("00".equals(b)) fail(first, flags, "ssn2", "SSN 4th & 5th chars must not be zero.");
        if (blank(c)) fail(first, flags, "ssn3", "SSN Last 4 chars must be supplied.");
        else if (!digits(c)) fail(first, flags, "ssn3", "SSN Last 4 chars must be all numeric.");
        else if ("0000".equals(c)) fail(first, flags, "ssn3", "SSN Last 4 chars must not be zero.");
    }

    private static void fail(String[] first, List<String> flags, String field, String message) {
        if (!flags.contains(field)) flags.add(field);
        if (first[0].isEmpty()) first[0] = message;
    }

    private static void overlay(AccountUpdateDetails d, Map<String, ?> input) {
        if (input == null) return;
        Map<String, java.util.function.Consumer<String>> fields = new LinkedHashMap<>();
        fields.put("activeStatus", v -> d.activeStatus=v); fields.put("currBal", v -> d.currBal=v);
        fields.put("creditLimit", v -> d.creditLimit=v); fields.put("cashCreditLimit", v -> d.cashCreditLimit=v);
        fields.put("openDate", v -> d.openDate=v); fields.put("expirationDate", v -> d.expirationDate=v);
        fields.put("reissueDate", v -> d.reissueDate=v); fields.put("currCycCredit", v -> d.currCycCredit=v);
        fields.put("currCycDebit", v -> d.currCycDebit=v); fields.put("groupId", v -> d.groupId=v);
        fields.put("firstName", v -> d.firstName=v); fields.put("middleName", v -> d.middleName=v);
        fields.put("lastName", v -> d.lastName=v); fields.put("addrLine1", v -> d.addrLine1=v);
        fields.put("addrLine2", v -> d.addrLine2=v); fields.put("city", v -> d.city=v);
        fields.put("stateCd", v -> d.stateCd=v); fields.put("countryCd", v -> d.countryCd=v);
        fields.put("zip", v -> d.zip=v); fields.put("phone1Area", v -> d.phone1Area=v);
        fields.put("phone1Prefix", v -> d.phone1Prefix=v); fields.put("phone1Line", v -> d.phone1Line=v);
        fields.put("phone2Area", v -> d.phone2Area=v); fields.put("phone2Prefix", v -> d.phone2Prefix=v);
        fields.put("phone2Line", v -> d.phone2Line=v); fields.put("ssn1", v -> d.ssn1=v);
        fields.put("ssn2", v -> d.ssn2=v); fields.put("ssn3", v -> d.ssn3=v);
        fields.put("govtId", v -> d.govtId=v); fields.put("dob", v -> d.dob=v);
        fields.put("eftAccountId", v -> d.eftAccountId=v); fields.put("priCardHolder", v -> d.priCardHolder=v);
        fields.put("ficoScore", v -> d.ficoScore=v);
        fields.forEach((key, setter) -> {
            if (input.containsKey(key)) {
                String value = input.get(key) == null ? "" : input.get(key).toString();
                setter.accept("*".equals(value) ? "" : value);
            }
        });
    }

    private static void normalizeAmounts(AccountUpdateDetails d) {
        d.currBal = normalized(d.currBal); d.creditLimit = normalized(d.creditLimit);
        d.cashCreditLimit = normalized(d.cashCreditLimit); d.currCycCredit = normalized(d.currCycCredit);
        d.currCycDebit = normalized(d.currCycDebit);
    }

    private static String normalized(String value) {
        BigDecimal amount = parseCurrency(value);
        return amount == null ? value : amount.setScale(2, java.math.RoundingMode.DOWN).toPlainString();
    }

    private static BigDecimal parseCurrency(String source) {
        if (source == null || source.isBlank()) return null;
        String value = source.trim().toUpperCase(Locale.ROOT).replace(",", "").replace("$", "").replace(" ", "");
        boolean negative = false;
        if (value.endsWith("CR") || value.endsWith("DB")) {
            negative = value.endsWith("CR");
            value = value.substring(0, value.length() - 2);
        } else if (value.endsWith("-")) {
            negative = true; value = value.substring(0, value.length() - 1);
        } else if (value.startsWith("-")) {
            negative = true; value = value.substring(1);
        } else if (value.startsWith("+")) {
            value = value.substring(1);
        }
        try {
            BigDecimal result = new BigDecimal(value);
            return negative ? result.negate() : result;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static List<String> changedFields(AccountUpdateDetails old, AccountUpdateDetails value) {
        List<String> changed = new ArrayList<>();
        compare(changed, "activeStatus", old.activeStatus, value.activeStatus, true);
        compareAmount(changed, "currBal", old.currBal, value.currBal);
        compareAmount(changed, "creditLimit", old.creditLimit, value.creditLimit);
        compareAmount(changed, "cashCreditLimit", old.cashCreditLimit, value.cashCreditLimit);
        compare(changed, "openDate", compact(old.openDate), compact(value.openDate), false);
        compare(changed, "expirationDate", compact(old.expirationDate), compact(value.expirationDate), false);
        compare(changed, "reissueDate", compact(old.reissueDate), compact(value.reissueDate), false);
        compareAmount(changed, "currCycCredit", old.currCycCredit, value.currCycCredit);
        compareAmount(changed, "currCycDebit", old.currCycDebit, value.currCycDebit);
        compare(changed, "groupId", old.groupId, value.groupId, true);
        compare(changed, "firstName", old.firstName, value.firstName, true);
        compare(changed, "middleName", old.middleName, value.middleName, true);
        compare(changed, "lastName", old.lastName, value.lastName, true);
        compare(changed, "addrLine1", old.addrLine1, value.addrLine1, true);
        compare(changed, "addrLine2", old.addrLine2, value.addrLine2, true);
        compare(changed, "city", old.city, value.city, true);
        compare(changed, "stateCd", old.stateCd, value.stateCd, true);
        compare(changed, "countryCd", old.countryCd, value.countryCd, true);
        compare(changed, "zip", old.zip, value.zip, false);
        for (String key : List.of("phone1Area", "phone1Prefix", "phone1Line", "phone2Area", "phone2Prefix",
                "phone2Line", "ssn1", "ssn2", "ssn3", "govtId", "dob", "eftAccountId",
                "priCardHolder", "ficoScore")) {
            compare(changed, key, get(old,key), get(value,key), false);
        }
        return changed;
    }

    private static void compare(List<String> fields, String key, String left, String right, boolean insensitive) {
        String a = left == null ? "" : left.trim(), b = right == null ? "" : right.trim();
        if (insensitive ? !a.equalsIgnoreCase(b) : !a.equals(b)) fields.add(key);
    }

    private static void compareAmount(List<String> fields, String key, String left, String right) {
        BigDecimal a = parseCurrency(left), b = parseCurrency(right);
        if (a != null && b != null) {
            if (a.setScale(2, java.math.RoundingMode.DOWN)
                    .compareTo(b.setScale(2, java.math.RoundingMode.DOWN)) != 0) fields.add(key);
        } else {
            compare(fields, key, left, right, false);
        }
    }

    private static boolean validAccountId(String id) {
        return id != null && id.length() == 11 && id.chars().allMatch(Character::isDigit)
                && !"00000000000".equals(id);
    }

    private static boolean validDate(String value) {
        String text = compact(value);
        return text.length() == 8 && digits(text) && dateError(Integer.parseInt(text.substring(0,4)),
                Integer.parseInt(text.substring(4,6)), Integer.parseInt(text.substring(6,8))) == null;
    }

    private static LocalDate toDate(String value) {
        String text = compact(value);
        return LocalDate.of(Integer.parseInt(text.substring(0,4)), Integer.parseInt(text.substring(4,6)),
                Integer.parseInt(text.substring(6,8)));
    }

    private static String compact(String value) {
        return value == null ? "" : value.replace("-", "");
    }

    private static boolean digits(String value) {
        return value != null && !value.isEmpty() && value.trim().chars().allMatch(Character::isDigit);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank() || "*".equals(value.trim());
    }

    private static String get(AccountUpdateDetails d, String name) {
        return switch (name) {
            case "activeStatus" -> d.activeStatus;
            case "priCardHolder" -> d.priCardHolder;
            case "phone1Area" -> d.phone1Area; case "phone1Prefix" -> d.phone1Prefix;
            case "phone1Line" -> d.phone1Line; case "phone2Area" -> d.phone2Area;
            case "phone2Prefix" -> d.phone2Prefix; case "phone2Line" -> d.phone2Line;
            case "ssn1" -> d.ssn1; case "ssn2" -> d.ssn2; case "ssn3" -> d.ssn3;
            case "dob" -> d.dob; case "eftAccountId" -> d.eftAccountId;
            case "ficoScore" -> d.ficoScore; default -> "";
        };
    }

    private static String text(Map<String, ?> input, String key, String fallback) {
        if (input == null || !input.containsKey(key)) return fallback;
        Object value = input.get(key);
        return value == null ? "" : value.toString();
    }

    private static String label(String field) {
        return "activeStatus".equals(field) ? "Account Status" : "Primary Card Holder";
    }

    private static String normalizeAid(String aid) {
        if (aid == null || aid.isBlank() || "ENTER".equalsIgnoreCase(aid)) return "ENTER";
        String value = aid.trim().toUpperCase(Locale.ROOT);
        if (value.matches("PF(1[3-9]|2[0-4])")) return "PF" + (Integer.parseInt(value.substring(2)) - 12);
        return value;
    }

    private static String resp10(int value) {
        return String.format("%09d ", value);
    }

    private static String fileError(String file, int resp, int resp2) {
        return "File Error: READ     on " + (file + " ".repeat(9)).substring(0, 9)
                + " returned RESP " + resp10(resp) + ",RESP2 " + resp10(resp2);
    }

    private static String limit(String value) {
        return value.length() > 75 ? value.substring(0, 75) : value;
    }

    private static String fixed75(String value) {
        if (value == null) return "";
        return value.length() > 75 ? value.substring(0, 75) : value;
    }

    private static UpdateResponse result(AccountUpdateSession session, String error, String info,
                                         List<String> flagged) {
        return new UpdateResponse(session, error, info, List.copyOf(flagged));
    }

    public record UpdateResponse(AccountUpdateSession session, String errorMessage,
                                 String infoMessage, List<String> flaggedFields) {
    }

    private record Validation(AccountUpdateDetails details, String message, List<String> flags) {
    }

    private static final class SaveState {
        private String action = "F";
        private String error = "";
        private String info = "";
        private boolean rollback;
    }
}
