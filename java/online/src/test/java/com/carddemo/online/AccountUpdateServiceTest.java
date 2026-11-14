package com.carddemo.online;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carddemo.online.account.AccountUpdateService;
import com.carddemo.online.account.UpdateRequest;
import com.carddemo.online.account.UpdateResponse;
import com.carddemo.online.store.AccountRecord;
import com.carddemo.online.store.InMemoryOnlineStore;
import com.carddemo.online.store.InMemoryOnlineStore.Op;
import com.carddemo.online.store.Resp;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AccountUpdateServiceTest {

    private final InMemoryOnlineStore store = Fixtures.store();
    private final AccountUpdateService svc = new AccountUpdateService(store, () -> LocalDate.of(2022, 7, 5));
    private static final String ACCT = "00000000099";

    private static Commarea reenter() {
        Commarea ca = new Commarea();
        ca.fromProgram = "COMEN01C";
        ca.fromTranid = "CM00";
        ca.userType = "U";
        ca.pgmContext = 1;
        return ca;
    }

    private UpdateResponse fetch() {
        UpdateResponse r = svc.process(new UpdateRequest("ENTER", " ", ACCT, null, null, reenter()));
        assertEquals("S", r.state());
        return r;
    }

    private UpdateResponse send(UpdateResponse prev, String action, Map<String, String> fields) {
        return svc.process(new UpdateRequest(action, prev.state(), ACCT, prev.snapshot(), fields, prev.commarea()));
    }

    private UpdateResponse change(String... kv) {
        UpdateResponse s = fetch();
        Map<String, String> f = new LinkedHashMap<>(s.fields());
        for (int i = 0; i < kv.length; i += 2) {
            f.put(kv[i], kv[i + 1]);
        }
        return send(s, "ENTER", f);
    }

    private void expectError(String msg, String... kv) {
        UpdateResponse r = change(kv);
        assertEquals(msg, r.errorMessage());
        assertEquals("E", r.state());
    }

    private void expectValid(String... kv) {
        UpdateResponse r = change(kv);
        assertEquals("", r.errorMessage());
        assertEquals("N", r.state());
    }

    @Test
    void BR_ONL_028_pf5BeforeValidationActsAsEnter() {
        UpdateResponse s = fetch();
        Map<String, String> f = new LinkedHashMap<>(s.fields());
        f.put("creditLimit", "2500.00");
        UpdateResponse r = send(s, "PF5", f);
        assertEquals("N", r.state()); // edited, not saved
        assertEquals(new BigDecimal("2000.00"), store.accounts.get(99L).creditLimit());
        // PF12 before a fetch is ENTER too
        UpdateResponse p12 = svc.process(new UpdateRequest("PF12", " ", "", null, null, reenter()));
        assertEquals("No input received", p12.errorMessage());
    }

    @Test
    void BR_ONL_029_stateMachineAndInfoMessages() {
        UpdateResponse s = fetch();
        assertEquals("Update account details presented above.", s.infoMessage());
        UpdateResponse e = change("acctStatus", "X");
        assertEquals("E", e.state());
        assertEquals("Update account details presented above.", e.infoMessage());
        Map<String, String> f = new LinkedHashMap<>(e.fields());
        f.put("acctStatus", "N");
        UpdateResponse n = send(e, "ENTER", f);
        assertEquals("N", n.state());
        assertEquals("Changes validated.Press F5 to save", n.infoMessage());
        UpdateResponse c = send(n, "PF5", f);
        assertEquals("C", c.state());
        assertEquals("Changes committed to database", c.infoMessage());
        UpdateResponse search = svc.process(new UpdateRequest("ENTER", " ", "", null, null, reenter()));
        assertEquals("Enter or update id of account to update", search.infoMessage());
    }

    @Test
    void BR_ONL_030_pf3SyncpointsAndReturnsAsUser() {
        Commarea ca = reenter();
        ca.userType = "A";
        UpdateResponse r = svc.process(new UpdateRequest("PF3", " ", "", null, null, ca));
        assertEquals("COMEN01C", r.next());
        assertEquals("COACTUPC", r.commarea().fromProgram);
        assertEquals("U", r.commarea().userType);
        assertEquals(1, store.syncpoints);
    }

    @Test
    void BR_ONL_031_freshEntryAndResetAfterSave() {
        Commarea menu = reenter();
        menu.pgmContext = 0;
        UpdateResponse fresh = svc.process(new UpdateRequest("ENTER", "S", ACCT, null, null, menu));
        assertEquals(" ", fresh.state());
        assertNull(fresh.fields());
        UpdateResponse n = change("creditLimit", "2500.00");
        UpdateResponse c = send(n, "PF5", n.fields());
        assertEquals("C", c.state());
        UpdateResponse after = send(c, "ENTER", c.fields());
        assertEquals(" ", after.state());
        assertNull(after.fields());
        assertEquals(0, after.commarea().acctId);
        assertEquals("Enter or update id of account to update", after.infoMessage());
    }

    @Test
    void BR_ONL_032_searchKeyEdit() {
        UpdateResponse blank = svc.process(new UpdateRequest("ENTER", " ", "*", null, null, reenter()));
        assertEquals("No input received", blank.errorMessage());
        UpdateResponse bad = svc.process(new UpdateRequest("ENTER", " ", "ABC00000001", null, null, reenter()));
        assertEquals("Account Number if supplied must be a 11 digit Non-Zero Number", bad.errorMessage());
        assertEquals(" ", bad.state());
    }

    @Test
    void BR_ONL_033_fetchSnapshotsOldValues() {
        UpdateResponse s = fetch();
        assertEquals("2019", s.snapshot().openYear);
        assertEquals("03", s.snapshot().openMon);
        assertEquals("15", s.snapshot().openDay);
        assertEquals("Update account details presented above.", s.infoMessage());
        assertEquals(99, s.commarea().acctId);
        assertEquals(99, s.commarea().custId);
        assertEquals("4000000000000099", s.commarea().cardNum);
        assertEquals("Y", s.commarea().acctStatus);
    }

    @Test
    void BR_ONL_034_starMeansNotSupplied() {
        expectError("Credit Limit must be supplied.", "creditLimit", "*");
    }

    @Test
    void BR_ONL_035_moneyInputTruncates() {
        UpdateResponse n = change("creditLimit", "$2,500.759");
        assertEquals("N", n.state());
        send(n, "PF5", n.fields());
        assertEquals(new BigDecimal("2500.75"), store.accounts.get(99L).creditLimit());
    }

    @Test
    void BR_ONL_036_changeDetectionIsTrimmedAndCaseInsensitive() {
        UpdateResponse r = change("firstName", "avery ");
        assertEquals("No change detected with respect to values fetched.", r.errorMessage());
        assertEquals("S", r.state());
    }

    @Test
    void BR_ONL_037_firstErrorWins() {
        expectError("Account Status must be supplied.", "acctStatus", "", "firstName", "");
    }

    @Test
    void BR_ONL_038_yesNoFields() {
        expectError("Account Status must be Y or N.", "acctStatus", "X");
        expectError("Primary Card Holder must be Y or N.", "priCardHolder", "X");
        assertEquals("S", change("priCardHolder", "y").state()); // case-insensitive compare: no change
        // F-05: 'Y' -> 'y' is no change (case-insensitive compare)
        assertEquals("S", change("acctStatus", "y").state());
    }

    @Test
    void BR_ONL_039_signedMoneyFields() {
        expectError("Cash Credit Limit is not valid", "cashCreditLimit", "12O0.00");
        expectError("Current Cycle Debit Limit must be supplied.", "currCycDebit", "");
        expectValid("currBal", "-99999.99"); // no range or sign limit
    }

    @Test
    void BR_ONL_040_yearAndCentury() {
        expectError("Open Date : Century is not valid.", "openYear", "2124");
        expectError("Open Date : Year must be supplied.", "openYear", "");
        expectError("Expiry Date must be 4 digit number.", "expYear", "20X7");
    }

    @Test
    void BR_ONL_041_monthAndDayRanges() {
        expectError("Expiry Date: Month must be a number between 1 and 12.", "expMon", "13");
        expectError("Open Date:day must be a number between 1 and 31.", "openDay", "32");
        expectError("Open Date : Month must be supplied.", "openMon", "*");
    }

    @Test
    void BR_ONL_042_dayMonthCombinationsAndLeapYears() {
        expectError("Reissue Date:Not a leap year.Cannot have 29 days in this month.",
                "reissueYear", "1900", "reissueMon", "02", "reissueDay", "29");
        expectValid("reissueYear", "2000", "reissueMon", "02", "reissueDay", "29");
        expectError("Open Date:Cannot have 31 days in this month.", "openMon", "04", "openDay", "31");
        expectError("Open Date:Cannot have 30 days in this month.", "openMon", "02", "openDay", "30");
    }

    @Test
    void BR_ONL_043_dateOfBirthMustBeBeforeToday() {
        expectError("Date of Birth:cannot be in the future", "dobYear", "2022", "dobMon", "07", "dobDay", "05");
        expectValid("dobYear", "2022", "dobMon", "07", "dobDay", "04");
    }

    @Test
    void BR_ONL_044_ssnEdit() {
        expectError("SSN: First 3 chars: should not be 000, 666, or between 900 and 999", "ssn1", "666");
        expectError("SSN: First 3 chars: should not be 000, 666, or between 900 and 999", "ssn1", "901");
        expectError("SSN: First 3 chars must not be zero.", "ssn1", "000"); // F-06
        expectError("SSN 4th & 5th chars must be all numeric.", "ssn2", "4X");
        expectError("SSN Last 4 chars must be supplied.", "ssn3", "");
    }

    @Test
    void BR_ONL_045_ficoRange() {
        expectError("FICO Score: should be between 300 and 850", "fico", "299");
        expectValid("fico", "850");
    }

    @Test
    void BR_ONL_046_namesAlphabeticOnly() {
        expectError("Last Name can have alphabets only.", "lastName", "TEST-CASE");
        expectError("First Name must be supplied.", "firstName", "");
        expectError("Middle Name can have alphabets only.", "middleName", "J4");
        expectValid("middleName", "");
    }

    @Test
    void BR_ONL_047_addressCityCountry() {
        expectError("City can have alphabets only.", "city", "Springfield 2");
        expectError("Address Line 1 must be supplied.", "addrLine1", "");
        expectError("Country can have alphabets only.", "country", "U5A");
        expectValid("addrLine2", "ANY #2 text", "govtId", "ANY-TEXT 123");
    }

    @Test
    void BR_ONL_048_stateCode() {
        expectError("State: is not a valid state code", "state", "XX");
    }

    @Test
    void BR_ONL_049_zipAndStateZipCombination() {
        expectError("Invalid zip code for state", "state", "NY", "zip", "75001");
        expectValid("state", "NY", "zip", "10001");
        expectError("Zip must be all numeric.", "zip", "6270A");
        expectError("Zip must not be zero.", "zip", "00000");
    }

    @Test
    void BR_ONL_050_phoneNumberEdit() {
        expectError("Phone Number 1: Not valid North America general purpose area code",
                "phone1a", "555", "phone1b", "010", "phone1c", "0123");
        expectError("Phone Number 2: Prefix code must be supplied.", "phone2b", "");
        expectError("Phone Number 1: Line number code cannot be zero", "phone1c", "0000");
        expectValid("phone2a", "", "phone2b", "", "phone2c", ""); // entirely blank is accepted
    }

    @Test
    void BR_ONL_051_eftAccountId() {
        expectError("EFT Account Id must not be zero.", "eftAccountId", "0000000000");
        expectError("EFT Account Id must be all numeric.", "eftAccountId", "00000A9001");
    }

    @Test
    void BR_ONL_052_validChangesAskForConfirmation() {
        UpdateResponse r = change("creditLimit", "2500.00");
        assertEquals("Changes validated.Press F5 to save", r.infoMessage());
        assertTrue(r.showSaveKeys());
        assertFalse(fetch().showSaveKeys());
    }

    @Test
    void BR_ONL_053_pf12CancelsChanges() {
        UpdateResponse n = change("creditLimit", "2500.00");
        UpdateResponse r = send(n, "PF12", n.fields());
        assertEquals("S", r.state());
        assertEquals("+      2,000.00", r.fields().get("creditLimit"));
    }

    @Test
    void BR_ONL_054_saveLocksBothRecords() {
        UpdateResponse n = change("creditLimit", "2500.00");
        store.fail(Op.LOCK_ACCT, Resp.IOERR, 0);
        UpdateResponse r = send(n, "PF5", n.fields());
        assertEquals("Could not lock account record for update", r.errorMessage());
        assertEquals("L", r.state());
        assertEquals("Changes unsuccessful. Please try again", r.infoMessage());
        // customer lock: message set, but 2000-DECIDE-ACTION falls into WHEN OTHER -> 'C' (F-16)
        InMemoryOnlineStore s2 = Fixtures.store();
        AccountUpdateService svc2 = new AccountUpdateService(s2, () -> LocalDate.of(2022, 7, 5));
        UpdateResponse f = svc2.process(new UpdateRequest("ENTER", " ", ACCT, null, null, reenter()));
        Map<String, String> fl = new LinkedHashMap<>(f.fields());
        fl.put("creditLimit", "2500.00");
        UpdateResponse n2 = svc2.process(new UpdateRequest("ENTER", f.state(), ACCT, f.snapshot(), fl, f.commarea()));
        s2.fail(Op.LOCK_CUST, Resp.IOERR, 0);
        UpdateResponse c = svc2.process(new UpdateRequest("PF5", n2.state(), ACCT, n2.snapshot(), fl, n2.commarea()));
        assertEquals("Could not lock customer record for update", c.errorMessage());
        assertEquals("C", c.state());
        assertEquals(new BigDecimal("2000.00"), s2.accounts.get(99L).creditLimit()); // nothing written
    }

    @Test
    void BR_ONL_055_optimisticConcurrencyCheck() {
        UpdateResponse n = change("creditLimit", "2500.00");
        AccountRecord a = store.accounts.get(99L);
        store.accounts.put(99L, new AccountRecord(99, a.activeStatus(), new BigDecimal("175.00"), a.creditLimit(),
                a.cashCreditLimit(), a.openDate(), a.expirationDate(), a.reissueDate(), a.currCycCredit(),
                a.currCycDebit(), a.addrZip(), a.groupId()));
        UpdateResponse r = send(n, "PF5", n.fields());
        assertEquals("Record changed by some one else. Please review", r.errorMessage());
        assertEquals("S", r.state());
        assertEquals(new BigDecimal("2000.00"), store.accounts.get(99L).creditLimit());
    }

    @Test
    void BR_ONL_056_savedRecordFormats() {
        UpdateResponse n = change("phone1a", "201", "phone1b", "555", "phone1c", "0142", "ssn1", "123",
                "ssn2", "45", "ssn3", "6780");
        assertEquals("N", n.state());
        UpdateResponse c = send(n, "PF5", n.fields());
        assertEquals("C", c.state());
        assertEquals("2019-03-15", store.accounts.get(99L).openDate());
        assertEquals("(201)555-0142  ", store.customers.get(99L).phoneNum1());
        assertEquals(123456780L, store.customers.get(99L).ssn());
        assertEquals("          ", store.accounts.get(99L).addrZip()); // F-18: never moved
    }

    @Test
    void BR_ONL_057_rewriteFailureRollsBack() {
        UpdateResponse n = change("creditLimit", "2500.00");
        store.fail(Op.REWRITE_CUST, Resp.IOERR, 0);
        UpdateResponse r = send(n, "PF5", n.fields());
        assertEquals("F", r.state());
        assertEquals("Update of record failed", r.errorMessage());
        assertEquals("Changes unsuccessful. Please try again", r.infoMessage());
        assertEquals(1, store.rollbacks);
        assertEquals(new BigDecimal("2000.00"), store.accounts.get(99L).creditLimit()); // undone
        // account rewrite failure: F without rollback
        InMemoryOnlineStore s2 = Fixtures.store();
        s2.fail(Op.REWRITE_ACCT, Resp.IOERR, 0);
        AccountUpdateService svc2 = new AccountUpdateService(s2, () -> LocalDate.of(2022, 7, 5));
        UpdateResponse f = svc2.process(new UpdateRequest("ENTER", " ", ACCT, null, null, reenter()));
        Map<String, String> fl = new LinkedHashMap<>(f.fields());
        fl.put("creditLimit", "2500.00");
        UpdateResponse n2 = svc2.process(new UpdateRequest("ENTER", f.state(), ACCT, f.snapshot(), fl, f.commarea()));
        UpdateResponse r2 = svc2.process(new UpdateRequest("PF5", n2.state(), ACCT, n2.snapshot(), fl, n2.commarea()));
        assertEquals("F", r2.state());
        assertEquals(0, s2.rollbacks);
    }

    @Test
    void BR_ONL_058_currencyDisplayPicture() {
        assertEquals("-      1,234.50", Pic.currency(new BigDecimal("-1234.50")));
        assertEquals("+      2,000.00", fetch().fields().get("creditLimit"));
        assertEquals("+234,567,890.12", Pic.currency(new BigDecimal("1234567890.12"))); // F-08
    }

    @Test
    void BR_ONL_059_unexpectedStateAbends() {
        UpdateResponse s = fetch();
        UpdateResponse r = svc.process(new UpdateRequest("ENTER", "X", ACCT, s.snapshot(), s.fields(), s.commarea()));
        assertNotNull(r.abend());
        assertEquals("0001", r.abend().get("code"));
        assertEquals("COACTUPC", r.abend().get("culprit"));
        assertEquals("UNEXPECTED DATA SCENARIO", r.abend().get("message"));
        assertEquals("9999", r.abend().get("abendCode"));
    }
}
