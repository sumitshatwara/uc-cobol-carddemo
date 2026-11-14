package com.carddemo.online.account;

import com.carddemo.online.Aid;
import com.carddemo.online.Commarea;
import com.carddemo.online.Pic;
import com.carddemo.online.store.AccountRecord;
import com.carddemo.online.store.CustomerRecord;
import com.carddemo.online.store.OnlineStore;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

/** COACTVWC (transaction CAVW, map CACTVWA of mapset COACTVW): account view. */
@Service
public class AccountViewService {

    static final String THISPGM = "COACTVWC";
    static final String THISTRANID = "CAVW";
    static final String MENUPGM = "COMEN01C";
    static final String MENUTRANID = "CM00";
    static final String PROMPT = "Enter or update id of account to display";

    private final OnlineStore store;

    public AccountViewService(OnlineStore store) {
        this.store = store;
    }

    /**
     * One COACTVWC interaction.  {@code in == null} is EIBCALEN = 0.
     *
     * @see "BR-ONL-020 .. BR-ONL-027"
     */
    public AccountViewResponse process(Commarea in, String action, String acctIdInput) {
        boolean eibcalen0 = in == null;
        Commarea ca;
        if (eibcalen0 || (MENUPGM.equals(in.fromProgram) && in.pgmContext != 1)) {
            ca = new Commarea(); // INITIALIZE CARDDEMO-COMMAREA
            if (!eibcalen0) {
                // keep who we are (the menu's commarea is re-initialised: fresh entry)
                ca.userId = in.userId;
                ca.userType = in.userType;
                ca.fromProgram = in.fromProgram;
                ca.fromTranid = in.fromTranid;
            }
        } else {
            ca = in.copy();
        }
        // BR-ONL-020: CSSTRPFY folding, only ENTER and PF3 valid, others act as ENTER
        String aid = Aid.fold(action);
        if (!"ENTER".equals(aid) && !"PF3".equals(aid)) {
            aid = "ENTER";
        }
        if ("PF3".equals(aid)) {
            // BR-ONL-021 (F-02: user type forced to 'U')
            ca.toTranid = Pic.isLowOrSpaces(ca.fromTranid) ? MENUTRANID : ca.fromTranid;
            ca.toProgram = Pic.isLowOrSpaces(ca.fromProgram) ? MENUPGM : ca.fromProgram;
            ca.fromTranid = THISTRANID;
            ca.fromProgram = THISPGM;
            ca.userType = "U";
            ca.pgmContext = 0;
            ca.lastMapset = "COACTVW ";
            ca.lastMap = "CACTVWA";
            return new AccountViewResponse("", "DEFAULT", "", "", null, null, ca.custId, ca.cardNum, ca.toProgram,
                    null, ca);
        }
        // WHEN CDEMO-PGM-ENTER: send the empty map (fresh entry)
        if (ca.pgmContext == 0) {
            ca.pgmContext = 1;
            return new AccountViewResponse("", "DEFAULT", "", PROMPT, null, null, 0, "", null, null, ca);
        }
        ca.pgmContext = 1;
        // 2200-EDIT-MAP-INPUTS
        String in11 = Pic.x(acctIdInput, 11);
        String ccAcctId = in11.equals(Pic.x("*", 11)) || Pic.isSpaces(in11) ? Pic.low(11) : in11;
        String message = "";
        boolean blank = false;
        boolean notOk = false;
        // 2210-EDIT-ACCOUNT
        if (Pic.isLowOrSpaces(ccAcctId)) {
            blank = true;
            message = "Account number not provided";
            ca.acctId = 0;
        } else if (!Pic.numeric(ccAcctId) || Pic.zeros(ccAcctId)) {
            notOk = true;
            message = "Account Filter must  be a non-zero 11 digit number";
            ca.acctId = 0;
        } else {
            ca.acctId = Long.parseLong(ccAcctId);
        }
        if (blank) {
            // BR-ONL-022 / F-03: 'No input received' overwrites 'Account number not provided'
            message = "No input received";
            return new AccountViewResponse("*", "RED", message, PROMPT, null, null, ca.custId, ca.cardNum, null,
                    null, ca);
        }
        if (notOk) {
            // BR-ONL-023
            return new AccountViewResponse(Pic.rtrim(in11), "RED", message, PROMPT, null, null, ca.custId,
                    ca.cardNum, null, null, ca);
        }
        // 9000-READ-ACCT
        AccountLookup.Result r = AccountLookup.read(store, ccAcctId, "");
        if (r.xref != null) {
            ca.custId = r.xref.custId();
            ca.cardNum = r.xref.cardNum();
        }
        Map<String, String> acct = null;
        Map<String, String> cust = null;
        if (r.foundAcct || r.foundCust) {
            acct = accountFields(r.account);
        }
        if (r.foundCust) {
            cust = customerFields(r.customer);
        }
        // 1300-SETUP-SCREEN-ATTRS: red when the filter is not ok
        String color = r.acctFilterNotOk ? "RED" : "DEFAULT";
        // BR-ONL-027 / F-03: the information line is always the prompt
        return new AccountViewResponse(ccAcctId, color, Pic.rtrim(r.message), PROMPT, acct, cust, ca.custId,
                ca.cardNum, null, null, ca);
    }

    /** ACSTTUSO..AADDGRPO. */
    static Map<String, String> accountFields(AccountRecord a) {
        Map<String, String> m = new LinkedHashMap<>();
        if (a == null) {
            // F-01: customer found but account not: the record area holds no data (blank screen fields)
            for (String k : new String[] {"status", "currBal", "creditLimit", "cashCreditLimit", "currCycCredit",
                    "currCycDebit", "openDate", "expiryDate", "reissueDate", "groupId"}) {
                m.put(k, "");
            }
            return m;
        }
        m.put("status", a.activeStatus());
        m.put("currBal", Pic.currency(a.currBal()));
        m.put("creditLimit", Pic.currency(a.creditLimit()));
        m.put("cashCreditLimit", Pic.currency(a.cashCreditLimit()));
        m.put("currCycCredit", Pic.currency(a.currCycCredit()));
        m.put("currCycDebit", Pic.currency(a.currCycDebit()));
        m.put("openDate", Pic.rtrim(a.openDate()));
        m.put("expiryDate", Pic.rtrim(a.expirationDate()));
        m.put("reissueDate", Pic.rtrim(a.reissueDate()));
        m.put("groupId", Pic.rtrim(a.groupId()));
        return m;
    }

    /** ACSTNUMO..ACSPFLGO; SSN as NNN-NN-NNNN. */
    static Map<String, String> customerFields(CustomerRecord c) {
        Map<String, String> m = new LinkedHashMap<>();
        String ssn = String.format("%09d", c.ssn());
        m.put("custId", String.format("%09d", c.custId()));
        m.put("ssn", ssn.substring(0, 3) + "-" + ssn.substring(3, 5) + "-" + ssn.substring(5, 9));
        m.put("fico", String.format("%03d", c.ficoCreditScore()));
        m.put("dob", Pic.rtrim(c.dob()));
        m.put("firstName", Pic.rtrim(c.firstName()));
        m.put("middleName", Pic.rtrim(c.middleName()));
        m.put("lastName", Pic.rtrim(c.lastName()));
        m.put("addrLine1", Pic.rtrim(c.addrLine1()));
        m.put("addrLine2", Pic.rtrim(c.addrLine2()));
        m.put("city", Pic.rtrim(c.addrLine3()));
        m.put("state", Pic.rtrim(c.addrStateCd()));
        m.put("zip", Pic.rtrim(c.addrZip()));
        m.put("country", Pic.rtrim(c.addrCountryCd()));
        m.put("phone1", Pic.rtrim(c.phoneNum1()));
        m.put("phone2", Pic.rtrim(c.phoneNum2()));
        m.put("govtId", Pic.rtrim(c.govtIssuedId()));
        m.put("eftAccountId", Pic.rtrim(c.eftAccountId()));
        m.put("priCardHolder", Pic.rtrim(c.priCardHolderInd()));
        return m;
    }
}
