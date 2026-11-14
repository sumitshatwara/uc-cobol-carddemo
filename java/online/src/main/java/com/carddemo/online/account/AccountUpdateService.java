package com.carddemo.online.account;

import com.carddemo.common.clock.FrozenClock;
import com.carddemo.online.Aid;
import com.carddemo.online.Commarea;
import com.carddemo.online.Pic;
import com.carddemo.online.store.AccountRecord;
import com.carddemo.online.store.CustomerRecord;
import com.carddemo.online.store.FileResult;
import com.carddemo.online.store.OnlineStore;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * COACTUPC (transaction CAUP, map CACTUPA of mapset COACTUP): account update.  The program is a
 * state machine on ACUP-CHANGE-ACTION; this class follows 0000-MAIN, 1000-PROCESS-INPUTS,
 * 1200-EDIT-MAP-INPUTS, 2000-DECIDE-ACTION, 9000-READ-ACCT, 9600-WRITE-PROCESSING and
 * 9700-CHECK-CHANGE-IN-REC paragraph by paragraph.
 */
@Service
public class AccountUpdateService {

    static final String THISPGM = "COACTUPC";
    static final String THISTRANID = "CAUP";
    static final String MENUPGM = "COMEN01C";
    static final String MENUTRANID = "CM00";

    static final String PROMPT_FOR_SEARCH_KEYS = "Enter or update id of account to update";
    static final String PROMPT_FOR_CHANGES = "Update account details presented above.";
    static final String PROMPT_FOR_CONFIRMATION = "Changes validated.Press F5 to save";
    static final String CONFIRM_UPDATE_SUCCESS = "Changes committed to database";
    static final String INFORM_FAILURE = "Changes unsuccessful. Please try again";
    static final String NO_CHANGES_DETECTED = "No change detected with respect to values fetched.";
    static final String COULD_NOT_LOCK_ACCT = "Could not lock account record for update";
    static final String COULD_NOT_LOCK_CUST = "Could not lock customer record for update";
    static final String DATA_CHANGED = "Record changed by some one else. Please review";
    static final String UPDATE_FAILED = "Update of record failed";

    /** Screen input keys and their BMS field lengths (COACTUP). */
    public static final Map<String, Integer> FIELD_KEYS = fieldKeys();

    private static Map<String, Integer> fieldKeys() {
        Map<String, Integer> m = new LinkedHashMap<>();
        Object[] kv = {"acctStatus", 1, "openYear", 4, "openMon", 2, "openDay", 2, "creditLimit", 15, "expYear", 4,
            "expMon", 2, "expDay", 2, "cashCreditLimit", 15, "reissueYear", 4, "reissueMon", 2, "reissueDay", 2,
            "currBal", 15, "currCycCredit", 15, "currCycDebit", 15, "groupId", 10, "custId", 9, "ssn1", 3, "ssn2", 2,
            "ssn3", 4, "dobYear", 4, "dobMon", 2, "dobDay", 2, "fico", 3, "firstName", 25, "middleName", 25,
            "lastName", 25, "addrLine1", 50, "addrLine2", 50, "city", 50, "state", 2, "zip", 5, "country", 3,
            "phone1a", 3, "phone1b", 3, "phone1c", 4, "phone2a", 3, "phone2b", 3, "phone2c", 4, "govtId", 20,
            "eftAccountId", 10, "priCardHolder", 1};
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], (Integer) kv[i + 1]);
        }
        return m;
    }

    private final OnlineStore store;
    private final Supplier<LocalDate> today;

    @Autowired
    public AccountUpdateService(OnlineStore store, FrozenClock clock) {
        this(store, () -> clock.now().toLocalDate());
    }

    public AccountUpdateService(OnlineStore store, Supplier<LocalDate> today) {
        this.store = store;
        this.today = today;
    }

    /** Working storage of one task. */
    private static final class Task {
        Commarea ca;
        String state;
        AcupDetails old;
        AcupDetails nu;
        Map<String, String> x15 = new LinkedHashMap<>();
        String ccAcctId;
        String message = "";
        boolean inputError;
        boolean acctFilterValid;
        boolean acctFilterBlank;
        boolean noChangesDetected;
        boolean foundCust;
        String aid;
        Map<String, String> fields;
    }

    /**
     * One COACTUPC interaction.
     *
     * @see "BR-ONL-028 .. BR-ONL-059"
     */
    public UpdateResponse process(UpdateRequest req) {
        Task t = new Task();
        t.state = normState(req.state());
        t.old = req.snapshot();
        t.fields = req.fields() == null ? Map.of() : req.fields();
        Commarea in = req.commarea();
        // 0000-MAIN: fresh entry (BR-ONL-031)
        if (in == null || (MENUPGM.equals(in.fromProgram) && in.pgmContext != 1)) {
            t.ca = new Commarea();
            if (in != null) {
                t.ca.userId = in.userId;
                t.ca.userType = in.userType;
                t.ca.fromProgram = in.fromProgram;
                t.ca.fromTranid = in.fromTranid;
            }
            t.ca.pgmContext = 0;
            t.state = " ";
            t.old = null;
        } else {
            t.ca = in.copy();
        }
        // BR-ONL-028: PF-key validity
        String aid = Aid.fold(req.action());
        boolean valid = "ENTER".equals(aid) || "PF3".equals(aid) || ("PF5".equals(aid) && "N".equals(t.state))
                || ("PF12".equals(aid) && !" ".equals(t.state));
        t.aid = valid ? aid : "ENTER";

        if ("PF3".equals(t.aid)) {
            // BR-ONL-030 (F-02)
            Commarea ca = t.ca;
            ca.toTranid = Pic.isLowOrSpaces(ca.fromTranid) ? MENUTRANID : ca.fromTranid;
            ca.toProgram = Pic.isLowOrSpaces(ca.fromProgram) ? MENUPGM : ca.fromProgram;
            ca.fromTranid = THISTRANID;
            ca.fromProgram = THISPGM;
            ca.userType = "U";
            ca.pgmContext = 0;
            ca.lastMapset = "COACTUP ";
            ca.lastMap = "CACTUPA";
            store.syncpoint();
            return new UpdateResponse(t.state, "", "", "", null, t.old, false, ca.toProgram, null, ca);
        }
        if (" ".equals(t.state) && t.ca.pgmContext == 0) {
            t.ca.pgmContext = 1;
            return send(t, null, true);
        }
        if ("C".equals(t.state) || "L".equals(t.state) || "F".equals(t.state)) {
            // BR-ONL-031: INITIALIZE WS-THIS-PROGCOMMAREA, CDEMO-ACCT-ID; search screen again
            t.old = null;
            t.ca.acctId = 0;
            t.state = " ";
            t.ca.pgmContext = 1;
            return send(t, null, true);
        }
        processInputs(t, req.acctId());
        UpdateResponse abend = decideAction(t);
        if (abend != null) {
            return abend;
        }
        return send(t, t.fields, false);
    }

    static String normState(String s) {
        if (s == null || s.isEmpty() || Pic.isLowOrSpaces(s)) {
            return " ";
        }
        return s.substring(0, 1);
    }

    // ---------------------------------------------------------------- 1000-PROCESS-INPUTS

    private String in(Task t, String key) {
        String v = t.fields.get(key);
        int len = FIELD_KEYS.get(key);
        String x = Pic.x(v, len);
        // BR-ONL-034: '*' or spaces = not supplied (LOW-VALUES)
        if (x.equals(Pic.x("*", len)) || Pic.isSpaces(x)) {
            return Pic.low(len);
        }
        return x;
    }

    private BigDecimal money(Task t, String key) {
        String x = in(t, key);
        t.x15.put(key, x);
        if (Pic.isLow(x)) {
            return null;
        }
        // BR-ONL-035: COMPUTE ...-N = NUMVAL-C(...) without ROUNDED (truncates)
        return Pic.testNumvalC(x) ? Pic.s9v2(Pic.numvalC(x)) : null;
    }

    private void processInputs(Task t, String acctIdInput) {
        // 1100-RECEIVE-MAP
        String a = Pic.x(acctIdInput, 11);
        t.ccAcctId = a.equals(Pic.x("*", 11)) || Pic.isSpaces(a) ? Pic.low(11) : a;
        AcupDetails n = new AcupDetails();
        n.acctIdX = t.ccAcctId;
        t.nu = n;
        if (" ".equals(t.state)) {
            editAccount(t);
            t.old = null; // MOVE LOW-VALUES TO ACUP-OLD-ACCT-DATA
            if (t.acctFilterBlank) {
                t.message = "No input received"; // SET NO-SEARCH-CRITERIA-RECEIVED
            }
            return;
        }
        n.activeStatus = in(t, "acctStatus");
        n.creditLimit = money(t, "creditLimit");
        n.cashCreditLimit = money(t, "cashCreditLimit");
        n.currBal = money(t, "currBal");
        n.currCycCredit = money(t, "currCycCredit");
        n.currCycDebit = money(t, "currCycDebit");
        n.openYear = in(t, "openYear");
        n.openMon = in(t, "openMon");
        n.openDay = in(t, "openDay");
        n.expYear = in(t, "expYear");
        n.expMon = in(t, "expMon");
        n.expDay = in(t, "expDay");
        n.reissueYear = in(t, "reissueYear");
        n.reissueMon = in(t, "reissueMon");
        n.reissueDay = in(t, "reissueDay");
        n.groupId = in(t, "groupId");
        n.custIdX = in(t, "custId");
        n.ssn1 = in(t, "ssn1");
        n.ssn2 = in(t, "ssn2");
        n.ssn3 = in(t, "ssn3");
        n.dobYear = in(t, "dobYear");
        n.dobMon = in(t, "dobMon");
        n.dobDay = in(t, "dobDay");
        n.ficoX = in(t, "fico");
        n.firstName = Pic.x(in(t, "firstName"), 25);
        n.middleName = Pic.x(in(t, "middleName"), 25);
        n.lastName = Pic.x(in(t, "lastName"), 25);
        n.addrLine1 = Pic.x(in(t, "addrLine1"), 50);
        n.addrLine2 = Pic.x(in(t, "addrLine2"), 50);
        n.addrLine3 = Pic.x(in(t, "city"), 50);
        n.stateCd = in(t, "state");
        n.zip = Pic.x(in(t, "zip"), 10);
        n.countryCd = in(t, "country");
        n.phone1a = in(t, "phone1a");
        n.phone1b = in(t, "phone1b");
        n.phone1c = in(t, "phone1c");
        n.phone2a = in(t, "phone2a");
        n.phone2b = in(t, "phone2b");
        n.phone2c = in(t, "phone2c");
        n.govtId = in(t, "govtId");
        n.eftAccountId = in(t, "eftAccountId");
        n.priHolder = in(t, "priCardHolder");
        editMapInputs(t);
    }

    /** 1210-EDIT-ACCOUNT. */
    private void editAccount(Task t) {
        String cc = t.ccAcctId;
        if (Pic.isLowOrSpaces(cc)) {
            t.inputError = true;
            t.acctFilterBlank = true;
            if (Pic.isSpaces(t.message)) {
                t.message = "Account number not provided";
            }
            t.ca.acctId = 0;
            return;
        }
        if (!Pic.numeric(cc) || Pic.zeros(cc)) {
            t.inputError = true;
            if (Pic.isSpaces(t.message)) {
                t.message = "Account Number if supplied must be a 11 digit Non-Zero Number";
            }
            t.ca.acctId = 0;
            return;
        }
        t.ca.acctId = Long.parseLong(cc);
        t.acctFilterValid = true;
    }

    /** 1200-EDIT-MAP-INPUTS when details were fetched (BR-ONL-036 .. BR-ONL-052). */
    private void editMapInputs(Task t) {
        t.acctFilterValid = true;
        t.foundCust = true;
        AcupDetails o = t.old == null ? new AcupDetails() : t.old;
        AcupDetails n = t.nu;
        boolean changed = changeHasOccurred(o, n);
        if (!changed) {
            t.noChangesDetected = true;
            t.message = NO_CHANGES_DETECTED;
        }
        if (!changed || "N".equals(t.state) || "C".equals(t.state)) {
            return;
        }
        t.state = "E"; // SET ACUP-CHANGES-NOT-OK
        FieldEdits e = new FieldEdits(t.message);
        e.yesNo("Account Status", n.activeStatus);
        e.date("Open Date", n.openYear, n.openMon, n.openDay);
        e.signed9v2("Credit Limit", t.x15.get("creditLimit"));
        e.date("Expiry Date", n.expYear, n.expMon, n.expDay);
        e.signed9v2("Cash Credit Limit", t.x15.get("cashCreditLimit"));
        e.date("Reissue Date", n.reissueYear, n.reissueMon, n.reissueDay);
        e.signed9v2("Current Balance", t.x15.get("currBal"));
        e.signed9v2("Current Cycle Credit Limit", t.x15.get("currCycCredit"));
        e.signed9v2("Current Cycle Debit Limit", t.x15.get("currCycDebit"));
        e.ssn(n.ssn1, n.ssn2, n.ssn3);
        if (e.date("Date of Birth", n.dobYear, n.dobMon, n.dobDay)) {
            e.dateOfBirth("Date of Birth", n.dobYear, n.dobMon, n.dobDay, today.get());
        }
        if (e.numReqd("FICO Score", n.ficoX, 3)) {
            e.ficoRange("FICO Score", n.ficoX);
        }
        e.alphaReqd("First Name", n.firstName, 25);
        e.alphaOpt("Middle Name", n.middleName, 25);
        e.alphaReqd("Last Name", n.lastName, 25);
        e.mandatory("Address Line 1", n.addrLine1, 50);
        boolean stateOk = e.alphaReqd("State", n.stateCd, 2) && e.usState("State", n.stateCd);
        boolean zipOk = e.numReqd("Zip", n.zip, 5);
        e.alphaReqd("City", n.addrLine3, 50);
        e.alphaReqd("Country", n.countryCd, 3);
        e.phone("Phone Number 1", n.phone1a, n.phone1b, n.phone1c);
        e.phone("Phone Number 2", n.phone2a, n.phone2b, n.phone2c);
        e.numReqd("EFT Account Id", n.eftAccountId, 10);
        e.yesNo("Primary Card Holder", n.priHolder);
        if (stateOk && zipOk) {
            e.stateZip(n.stateCd, n.zip);
        }
        t.message = e.message;
        t.inputError = e.inputError;
        if (!e.inputError) {
            t.state = "N"; // ACUP-CHANGES-OK-NOT-CONFIRMED
        }
    }

    private static String up(String s) {
        return s == null ? "" : s.toUpperCase(Locale.ROOT);
    }

    private static boolean eqTrimUpper(String a, String b) {
        return up(Pic.trim(a)).equals(up(Pic.trim(b)));
    }

    private static boolean moneyEq(BigDecimal a, BigDecimal b) {
        return a != null && b != null && a.compareTo(b) == 0;
    }

    /**
     * 1205-COMPARE-OLD-NEW (BR-ONL-036): status case-insensitive, money and dates exact, group id
     * and customer text trimmed and case-insensitive, phones / SSN / DOB / EFT / FICO exact.
     */
    static boolean changeHasOccurred(AcupDetails o, AcupDetails n) {
        boolean acctSame = n.acctIdX.equals(o.acctIdX)
                && up(n.activeStatus).equals(up(o.activeStatus))
                && moneyEq(n.currBal, o.currBal)
                && moneyEq(n.creditLimit, o.creditLimit)
                && moneyEq(n.cashCreditLimit, o.cashCreditLimit)
                && (n.openYear + n.openMon + n.openDay).equals(o.openYear + o.openMon + o.openDay)
                && (n.expYear + n.expMon + n.expDay).equals(o.expYear + o.expMon + o.expDay)
                && (n.reissueYear + n.reissueMon + n.reissueDay).equals(o.reissueYear + o.reissueMon + o.reissueDay)
                && moneyEq(n.currCycCredit, o.currCycCredit)
                && moneyEq(n.currCycDebit, o.currCycDebit)
                && eqTrimUpper(n.groupId, o.groupId);
        if (!acctSame) {
            return true;
        }
        boolean custSame = eqTrimUpper(n.custIdX, o.custIdX)
                && eqTrimUpper(n.firstName, o.firstName)
                && eqTrimUpper(n.middleName, o.middleName)
                && eqTrimUpper(n.lastName, o.lastName)
                && eqTrimUpper(n.addrLine1, o.addrLine1)
                && eqTrimUpper(n.addrLine2, o.addrLine2)
                && eqTrimUpper(n.addrLine3, o.addrLine3)
                && eqTrimUpper(n.stateCd, o.stateCd)
                && eqTrimUpper(n.countryCd, o.countryCd)
                && eqTrimUpper(n.zip, o.zip)
                && n.phone1a.equals(o.phone1a) && n.phone1b.equals(o.phone1b) && n.phone1c.equals(o.phone1c)
                && n.phone2a.equals(o.phone2a) && n.phone2b.equals(o.phone2b) && n.phone2c.equals(o.phone2c)
                && n.ssnX().equals(o.ssnX())
                && eqTrimUpper(n.govtId, o.govtId)
                && n.dob8().equals(o.dob8())
                && n.eftAccountId.equals(o.eftAccountId)
                && eqTrimUpper(n.priHolder, o.priHolder)
                && n.ficoX.equals(o.ficoX);
        return !custSame;
    }

    // ---------------------------------------------------------------- 2000-DECIDE-ACTION

    private UpdateResponse decideAction(Task t) {
        String s = t.state;
        if (" ".equals(s) || "PF12".equals(t.aid)) {
            if (t.acctFilterValid) {
                t.message = ""; // SET WS-RETURN-MSG-OFF
                readAcct(t);
                if (t.foundCust) {
                    t.state = "S";
                }
            }
            return null;
        }
        switch (s) {
            case "S":
                if (!(t.inputError || t.noChangesDetected)) {
                    t.state = "N";
                }
                return null;
            case "E":
                return null;
            case "N":
                if ("PF5".equals(t.aid)) {
                    String outcome = writeProcessing(t);
                    switch (outcome) {
                        case "LOCK_ACCT" -> t.state = "L";
                        case "UPDATE_FAILED" -> t.state = "F";
                        case "CHANGED" -> t.state = "S";
                        default -> t.state = "C"; // includes a customer lock failure (F-16)
                    }
                }
                return null;
            case "C":
                t.state = "S";
                if (Pic.isLowOrSpaces(t.ca.fromTranid)) {
                    t.ca.acctId = 0;
                    t.ca.cardNum = "";
                    t.ca.acctStatus = "";
                }
                return null;
            default:
                // BR-ONL-059: ABEND-ROUTINE
                Map<String, String> abend = new LinkedHashMap<>();
                abend.put("code", "0001");
                abend.put("culprit", THISPGM);
                abend.put("reason", "");
                abend.put("message", "UNEXPECTED DATA SCENARIO");
                abend.put("abendCode", "9999");
                return new UpdateResponse(t.state, "", "UNEXPECTED DATA SCENARIO", t.ccAcctId, null, t.old, false,
                        null, abend, t.ca);
        }
    }

    /** 9000-READ-ACCT + 9500-STORE-FETCHED-DATA (BR-ONL-033, F-01). */
    private void readAcct(Task t) {
        AccountLookup.Result r = AccountLookup.read(store, t.ccAcctId, t.message);
        t.message = r.message;
        t.inputError |= r.inputError;
        if (r.xref != null) {
            t.ca.custId = r.xref.custId();
            t.ca.cardNum = r.xref.cardNum();
        }
        t.foundCust = r.foundCust;
        if (!r.readCust) {
            t.old = null;
            return; // xref failed: GO TO 9000-READ-ACCT-EXIT before 9500
        }
        t.old = storeFetchedData(t, r.account, r.customer);
    }

    private AcupDetails storeFetchedData(Task t, AccountRecord a, CustomerRecord c) {
        AcupDetails o = new AcupDetails();
        o.acctIdX = t.ccAcctId;
        t.ca.acctId = Long.parseLong(t.ccAcctId);
        if (a != null) {
            o.activeStatus = Pic.x(a.activeStatus(), 1);
            o.currBal = a.currBal();
            o.creditLimit = a.creditLimit();
            o.cashCreditLimit = a.cashCreditLimit();
            String od = Pic.x(a.openDate(), 10);
            o.openYear = od.substring(0, 4);
            o.openMon = od.substring(5, 7);
            o.openDay = od.substring(8, 10);
            String ed = Pic.x(a.expirationDate(), 10);
            o.expYear = ed.substring(0, 4);
            o.expMon = ed.substring(5, 7);
            o.expDay = ed.substring(8, 10);
            String rd = Pic.x(a.reissueDate(), 10);
            o.reissueYear = rd.substring(0, 4);
            o.reissueMon = rd.substring(5, 7);
            o.reissueDay = rd.substring(8, 10);
            o.currCycCredit = a.currCycCredit();
            o.currCycDebit = a.currCycDebit();
            o.groupId = Pic.x(a.groupId(), 10);
            t.ca.acctStatus = o.activeStatus;
        }
        if (c != null) {
            o.custIdX = String.format("%09d", c.custId());
            o.firstName = Pic.x(c.firstName(), 25);
            o.middleName = Pic.x(c.middleName(), 25);
            o.lastName = Pic.x(c.lastName(), 25);
            o.addrLine1 = Pic.x(c.addrLine1(), 50);
            o.addrLine2 = Pic.x(c.addrLine2(), 50);
            o.addrLine3 = Pic.x(c.addrLine3(), 50);
            o.stateCd = Pic.x(c.addrStateCd(), 2);
            o.countryCd = Pic.x(c.addrCountryCd(), 3);
            o.zip = Pic.x(c.addrZip(), 10);
            o.phone1 = Pic.x(c.phoneNum1(), 15);
            o.phone1a = o.phone1.substring(1, 4);
            o.phone1b = o.phone1.substring(5, 8);
            o.phone1c = o.phone1.substring(9, 13);
            o.phone2 = Pic.x(c.phoneNum2(), 15);
            o.phone2a = o.phone2.substring(1, 4);
            o.phone2b = o.phone2.substring(5, 8);
            o.phone2c = o.phone2.substring(9, 13);
            String ssn = String.format("%09d", c.ssn());
            o.ssn1 = ssn.substring(0, 3);
            o.ssn2 = ssn.substring(3, 5);
            o.ssn3 = ssn.substring(5, 9);
            o.govtId = Pic.x(c.govtIssuedId(), 20);
            String dob = Pic.x(c.dob(), 10);
            o.dobYear = dob.substring(0, 4);
            o.dobMon = dob.substring(5, 7);
            o.dobDay = dob.substring(8, 10);
            o.eftAccountId = Pic.x(c.eftAccountId(), 10);
            o.priHolder = Pic.x(c.priCardHolderInd(), 1);
            o.ficoX = String.format("%03d", c.ficoCreditScore());
            t.ca.custId = c.custId();
            t.ca.custFname = o.firstName;
            t.ca.custMname = o.middleName;
            t.ca.custLname = o.lastName;
        }
        return o;
    }

    // ---------------------------------------------------------------- 9600-WRITE-PROCESSING

    private void setMsg(Task t, String m) {
        if (Pic.isSpaces(t.message)) {
            t.message = m;
        }
    }

    /** @return LOCK_ACCT, LOCK_CUST, CHANGED, UPDATE_FAILED or OK (BR-ONL-054 .. BR-ONL-057). */
    private String writeProcessing(Task t) {
        FileResult<AccountRecord> la = store.readAccountForUpdate(t.ccAcctId);
        if (!la.normal()) {
            t.inputError = true;
            setMsg(t, COULD_NOT_LOCK_ACCT);
            return "LOCK_ACCT";
        }
        FileResult<CustomerRecord> lc = store.readCustomerForUpdate(String.format("%09d", t.ca.custId));
        if (!lc.normal()) {
            t.inputError = true;
            setMsg(t, COULD_NOT_LOCK_CUST);
            return "LOCK_CUST";
        }
        // 9700-CHECK-CHANGE-IN-REC (BR-ONL-055, F-09: GO TO 9600-WRITE-PROCESSING-EXIT = early return)
        if (changedBeforeUpdate(la.record(), lc.record(), t.old == null ? new AcupDetails() : t.old)) {
            t.message = DATA_CHANGED;
            return "CHANGED";
        }
        AcupDetails n = t.nu;
        AccountRecord cur = la.record();
        // BR-ONL-056: ACCT-UPDATE-RECORD (INITIALIZEd: ACCT-UPDATE-ADDR-ZIP is never moved, F-18)
        AccountRecord acct = new AccountRecord(
                Pic.numeric(n.acctIdX) ? Long.parseLong(n.acctIdX) : cur.acctId(),
                n.activeStatus,
                zeroIfNull(n.currBal), zeroIfNull(n.creditLimit), zeroIfNull(n.cashCreditLimit),
                n.openYear + "-" + n.openMon + "-" + n.openDay,
                n.expYear + "-" + n.expMon + "-" + n.expDay,
                n.reissueYear + "-" + n.reissueMon + "-" + n.reissueDay,
                zeroIfNull(n.currCycCredit), zeroIfNull(n.currCycDebit),
                Pic.x("", 10),
                Pic.x(n.groupId, 10));
        CustomerRecord cust = new CustomerRecord(
                Pic.numeric(n.custIdX) ? Long.parseLong(n.custIdX) : t.ca.custId,
                n.firstName, n.middleName, n.lastName, n.addrLine1, n.addrLine2, n.addrLine3,
                n.stateCd, n.countryCd, n.zip,
                Pic.x("(" + n.phone1a + ")" + n.phone1b + "-" + n.phone1c, 15),
                Pic.x("(" + n.phone2a + ")" + n.phone2b + "-" + n.phone2c, 15),
                Pic.numeric(n.ssnX()) ? Long.parseLong(n.ssnX()) : 0L,
                Pic.x(n.govtId, 20),
                n.dobYear + "-" + n.dobMon + "-" + n.dobDay,
                n.eftAccountId, n.priHolder,
                Pic.numeric(n.ficoX) ? Integer.parseInt(n.ficoX) : 0);
        if (!store.rewriteAccount(acct).normal()) {
            t.message = UPDATE_FAILED;
            return "UPDATE_FAILED";
        }
        if (!store.rewriteCustomer(cust).normal()) {
            t.message = UPDATE_FAILED;
            store.rollback(); // EXEC CICS SYNCPOINT ROLLBACK
            return "UPDATE_FAILED";
        }
        return "OK";
    }

    private static BigDecimal zeroIfNull(BigDecimal v) {
        return v == null ? new BigDecimal("0.00") : v;
    }

    /** 9700-CHECK-CHANGE-IN-REC: current records against the snapshot taken at display time. */
    static boolean changedBeforeUpdate(AccountRecord a, CustomerRecord c, AcupDetails o) {
        String od = Pic.x(a.openDate(), 10);
        String ed = Pic.x(a.expirationDate(), 10);
        String rd = Pic.x(a.reissueDate(), 10);
        boolean acctSame = Pic.x(a.activeStatus(), 1).equals(o.activeStatus)
                && moneyEq(a.currBal(), o.currBal)
                && moneyEq(a.creditLimit(), o.creditLimit)
                && moneyEq(a.cashCreditLimit(), o.cashCreditLimit)
                && moneyEq(a.currCycCredit(), o.currCycCredit)
                && moneyEq(a.currCycDebit(), o.currCycDebit)
                && od.substring(0, 4).equals(o.openYear) && od.substring(5, 7).equals(o.openMon)
                && od.substring(8, 10).equals(o.openDay)
                && ed.substring(0, 4).equals(o.expYear) && ed.substring(5, 7).equals(o.expMon)
                && ed.substring(8, 10).equals(o.expDay)
                && rd.substring(0, 4).equals(o.reissueYear) && rd.substring(5, 7).equals(o.reissueMon)
                && rd.substring(8, 10).equals(o.reissueDay)
                && Pic.x(a.groupId(), 10).toLowerCase(Locale.ROOT).equals(o.groupId.toLowerCase(Locale.ROOT));
        if (!acctSame) {
            return true;
        }
        String dob = Pic.x(c.dob(), 10);
        boolean custSame = up(Pic.x(c.firstName(), 25)).equals(up(o.firstName))
                && up(Pic.x(c.middleName(), 25)).equals(up(o.middleName))
                && up(Pic.x(c.lastName(), 25)).equals(up(o.lastName))
                && up(Pic.x(c.addrLine1(), 50)).equals(up(o.addrLine1))
                && up(Pic.x(c.addrLine2(), 50)).equals(up(o.addrLine2))
                && up(Pic.x(c.addrLine3(), 50)).equals(up(o.addrLine3))
                && up(Pic.x(c.addrStateCd(), 2)).equals(up(o.stateCd))
                && up(Pic.x(c.addrCountryCd(), 3)).equals(up(o.countryCd))
                && Pic.x(c.addrZip(), 10).equals(o.zip)
                && Pic.x(c.phoneNum1(), 15).equals(o.phone1)
                && Pic.x(c.phoneNum2(), 15).equals(o.phone2)
                && String.format("%09d", c.ssn()).equals(o.ssnX())
                && up(Pic.x(c.govtIssuedId(), 20)).equals(up(o.govtId))
                && dob.substring(0, 4).equals(o.dobYear) && dob.substring(5, 7).equals(o.dobMon)
                && dob.substring(8, 10).equals(o.dobDay)
                && Pic.x(c.eftAccountId(), 10).equals(o.eftAccountId)
                && Pic.x(c.priCardHolderInd(), 1).equals(o.priHolder)
                && String.format("%03d", c.ficoCreditScore()).equals(o.ficoX);
        return !custSame;
    }

    // ---------------------------------------------------------------- 3000-SEND-MAP

    /** 3250-SETUP-INFOMSG. */
    static String infoMessage(int pgmContext, String state) {
        if (pgmContext == 0) {
            return PROMPT_FOR_SEARCH_KEYS;
        }
        return switch (state) {
            case " " -> PROMPT_FOR_SEARCH_KEYS;
            case "S", "E" -> PROMPT_FOR_CHANGES;
            case "N" -> PROMPT_FOR_CONFIRMATION;
            case "C" -> CONFIRM_UPDATE_SUCCESS;
            case "L", "F" -> INFORM_FAILURE;
            default -> PROMPT_FOR_SEARCH_KEYS;
        };
    }

    private UpdateResponse send(Task t, Map<String, String> echo, boolean search) {
        Map<String, String> shown = null;
        if (!search) {
            if ("S".equals(t.state) && t.old != null) {
                shown = originalValues(t.old); // 3201-SHOW-ORIGINAL-VALUES
            } else if (!" ".equals(t.state)) {
                shown = new LinkedHashMap<>();
                for (String k : FIELD_KEYS.keySet()) {
                    shown.put(k, echo == null ? "" : echo.getOrDefault(k, ""));
                }
            }
        }
        int ctx = t.ca.pgmContext;
        String info = infoMessage(search ? 0 : ctx, t.state);
        t.ca.pgmContext = 1; // SET CDEMO-PGM-REENTER
        String acctField = search ? "" : Pic.rtrim(t.ccAcctId == null ? "" : t.ccAcctId.replace(Pic.LOW, ' '));
        if (!search && t.acctFilterBlank) {
            acctField = "*";
        }
        return new UpdateResponse(t.state, info, Pic.rtrim(t.message), acctField, shown, t.old,
                "N".equals(t.state), null, null, t.ca);
    }

    /** Display values of the snapshot (money with +ZZZ,ZZZ,ZZZ.99, BR-ONL-058). */
    public static Map<String, String> originalValues(AcupDetails o) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("acctStatus", o.activeStatus);
        m.put("openYear", o.openYear);
        m.put("openMon", o.openMon);
        m.put("openDay", o.openDay);
        m.put("creditLimit", o.creditLimit == null ? "" : Pic.currency(o.creditLimit));
        m.put("expYear", o.expYear);
        m.put("expMon", o.expMon);
        m.put("expDay", o.expDay);
        m.put("cashCreditLimit", o.cashCreditLimit == null ? "" : Pic.currency(o.cashCreditLimit));
        m.put("reissueYear", o.reissueYear);
        m.put("reissueMon", o.reissueMon);
        m.put("reissueDay", o.reissueDay);
        m.put("currBal", o.currBal == null ? "" : Pic.currency(o.currBal));
        m.put("currCycCredit", o.currCycCredit == null ? "" : Pic.currency(o.currCycCredit));
        m.put("currCycDebit", o.currCycDebit == null ? "" : Pic.currency(o.currCycDebit));
        m.put("groupId", Pic.rtrim(o.groupId));
        m.put("custId", o.custIdX);
        m.put("ssn1", o.ssn1);
        m.put("ssn2", o.ssn2);
        m.put("ssn3", o.ssn3);
        m.put("dobYear", o.dobYear);
        m.put("dobMon", o.dobMon);
        m.put("dobDay", o.dobDay);
        m.put("fico", o.ficoX);
        m.put("firstName", Pic.rtrim(o.firstName));
        m.put("middleName", Pic.rtrim(o.middleName));
        m.put("lastName", Pic.rtrim(o.lastName));
        m.put("addrLine1", Pic.rtrim(o.addrLine1));
        m.put("addrLine2", Pic.rtrim(o.addrLine2));
        m.put("city", Pic.rtrim(o.addrLine3));
        m.put("state", Pic.rtrim(o.stateCd));
        m.put("zip", Pic.rtrim(o.zip));
        m.put("country", Pic.rtrim(o.countryCd));
        m.put("phone1a", o.phone1a);
        m.put("phone1b", o.phone1b);
        m.put("phone1c", o.phone1c);
        m.put("phone2a", o.phone2a);
        m.put("phone2b", o.phone2b);
        m.put("phone2c", o.phone2c);
        m.put("govtId", Pic.rtrim(o.govtId));
        m.put("eftAccountId", Pic.rtrim(o.eftAccountId));
        m.put("priCardHolder", o.priHolder);
        return m;
    }

    /** Keys of {@link #FIELD_KEYS} as a list (for clients). */
    public static List<String> fieldKeys(boolean unused) {
        return List.copyOf(FIELD_KEYS.keySet());
    }
}
