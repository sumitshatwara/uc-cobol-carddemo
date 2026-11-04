package com.carddemo.online;

import com.carddemo.online.account.AccountUpdateSession;
import com.carddemo.online.commarea.CardDemoCommarea;
import com.carddemo.online.files.Account;
import com.carddemo.online.files.Customer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountUpdateServiceTest extends OnlineTestSupport {
    @Test
    @DisplayName("BR-ONL-016 PF3 exit from Account View / Account Update")
    void brOnl016_accountUpdateExit() {
        // Given
        CardDemoCommarea state = new CardDemoCommarea();
        state.fromProgram = "COMEN01C";
        state.fromTranId = "CM00";
        state.userType = "A";

        // When
        var session = new AccountUpdateSession(state, "S", null, null);
        var result = update.update("00000000001", "PF3", session, Map.of());

        // Then
        assertEquals("CM00", result.session().commarea().toTranId);
        assertEquals("COMEN01C", result.session().commarea().toProgram);
        assertEquals("COACTUPC", result.session().commarea().fromProgram);
        assertEquals("CAUP", result.session().commarea().fromTranId);
        assertEquals("U", result.session().commarea().userType);
        assertEquals(0, result.session().commarea().pgmContext);
        assertEquals("COACTUP", result.session().commarea().lastMapset);
        assertEquals("CAUPA", result.session().commarea().lastMap);
    }

    @Test
    @DisplayName("BR-ONL-023 Account Update state machine and info messages")
    void brOnl023_statesAndMessages() {
        // Given
        var fetched = update.search("00000000001", null).session();

        // When
        var validated = update.update("00000000001", "ENTER", fetched,
                Map.of("creditLimit", "6000.00"));
        var saved = update.update("00000000001", "PF5", validated.session(), Map.of());

        // Then
        assertEquals("S", fetched.changeAction());
        assertEquals("N", validated.session().changeAction());
        assertEquals("Changes validated.Press F5 to save", validated.infoMessage());
        assertEquals("C", saved.session().changeAction());
        assertEquals("Changes committed to database", saved.infoMessage());
    }

    @Test
    @DisplayName("BR-ONL-024 Account Update valid keys")
    void brOnl024_pf5InStateSProcessesEnter() {
        // Given
        var fetched = update.search("00000000001", null).session();

        // When
        files.calls.clear();
        var result = update.update("00000000001", "PF5", fetched,
                Map.of("creditLimit", "6000.00"));

        // Then
        assertEquals("N", result.session().changeAction());
        assertEquals("6000.00", result.session().newDetails().creditLimit);
        assertFalse(files.calls.contains("REWRITE ACCTDAT"));
    }

    @Test
    @DisplayName("BR-ONL-025 Account Update search and fetch")
    void brOnl025_searchAndFetch() {
        // Given
        files.calls.clear();

        // When
        var invalid = update.search("ABCDEFGHIJK", null);
        boolean invalidReadNothing = files.calls.isEmpty();
        files.calls.clear();
        var fetched = update.search("00000000001", null);

        // Then
        assertEquals("Account Number if supplied must be a 11 digit Non-Zero Number",
                invalid.errorMessage());
        assertTrue(invalidReadNothing);
        assertEquals(List.of("CXACAIX:00000000001", "ACCTDAT:00000000001", "CUSTDAT:000000001"),
                files.calls);
        assertEquals("S", fetched.session().changeAction());
        assertEquals("Update account details presented above.", fetched.infoMessage());
    }

    @Test
    @DisplayName("BR-ONL-026 Account Update input mapping and amount conversion")
    void brOnl026_amountConversionAndStarBlank() {
        // Given
        var fetched = update.search("00000000001", null).session();

        // When
        var converted = update.update("00000000001", "ENTER", fetched,
                Map.of("creditLimit", "$1,234.567", "currBal", "250.00CR"));
        var blank = update.update("00000000001", "ENTER", fetched,
                Map.of("creditLimit", "*"));

        // Then
        assertEquals("1234.56", converted.session().newDetails().creditLimit);
        assertEquals("-250.00", converted.session().newDetails().currBal);
        assertEquals("", blank.session().newDetails().creditLimit);
        assertEquals("E", blank.session().changeAction());
        assertEquals("Credit Limit must be supplied.", blank.errorMessage());
    }

    @Test
    @DisplayName("BR-ONL-027 Account Update change detection")
    void brOnl027_noChange() {
        // Given
        var fetched = update.search("00000000001", null).session();

        // When
        var result = update.update("00000000001", "ENTER", fetched, Map.of("firstName", "john "));

        // Then
        assertEquals("No change detected with respect to values fetched.", result.errorMessage());
        assertEquals("S", result.session().changeAction());
        assertTrue(result.flaggedFields().isEmpty());
    }

    @Test
    @DisplayName("BR-ONL-028 Account Update edit ladder (order and first-error-wins)")
    void brOnl028_editOrder() {
        // Given
        var fetched = update.search("00000000001", null).session();

        // When
        var result = update.update("00000000001", "ENTER", fetched,
                Map.of("creditLimit", "", "activeStatus", "X"));

        // Then
        assertEquals("Account Status must be Y or N.", result.errorMessage());
        assertEquals("E", result.session().changeAction());
        assertTrue(result.flaggedFields().contains("activeStatus"));
        assertTrue(result.flaggedFields().contains("creditLimit"));
    }

    @Test
    @DisplayName("BR-ONL-029 Account Update Y/N fields (Account Status, Primary Card Holder)")
    void brOnl029_yesNoFields() {
        // Given
        var fetched = update.search("00000000001", null).session();

        // When
        var withCreditChange = update.update("00000000001", "ENTER", fetched,
                Map.of("activeStatus", "y", "creditLimit", "6000.00"));
        var statusOnly = update.update("00000000001", "ENTER", fetched,
                Map.of("activeStatus", "y"));

        // Then
        assertEquals("Account Status must be Y or N.", withCreditChange.errorMessage());
        assertEquals("E", withCreditChange.session().changeAction());
        assertEquals("No change detected with respect to values fetched.", statusOnly.errorMessage());
        assertEquals("S", statusOnly.session().changeAction());
    }

    @Test
    @DisplayName("BR-ONL-030 Account Update signed amount fields")
    void brOnl030_signedAmount() {
        // Given
        var fetched = update.search("00000000001", null).session();

        // When
        var result = update.update("00000000001", "ENTER", fetched,
                Map.of("currCycDebit", "12A.00"));

        // Then
        assertEquals("Current Cycle Debit Limit is not valid", result.errorMessage());
        assertEquals("E", result.session().changeAction());
    }

    @Test
    @DisplayName("BR-ONL-031 Account Update date fields (Open, Expiry, Reissue, DOB)")
    void brOnl031_dateValidationAndLeapYears() {
        // Given
        var fetched = update.search("00000000001", null).session();

        // When
        var invalidExpiry = update.update("00000000001", "ENTER", fetched,
                Map.of("expirationDate", "2023-02-29"));
        var invalidCentury = update.update("00000000001", "ENTER", fetched,
                Map.of("openDate", "2100-01-01"));
        var invalid1900 = update.update("00000000001", "ENTER", fetched,
                Map.of("expirationDate", "1900-02-29"));
        var valid2000 = update.update("00000000001", "ENTER", fetched,
                Map.of("expirationDate", "2000-02-29"));

        // Then
        assertEquals("Expiry Date:Not a leap year.Cannot have 29 days in this month.",
                invalidExpiry.errorMessage());
        assertEquals("Open Date : Century is not valid.", invalidCentury.errorMessage());
        assertEquals("Expiry Date:Not a leap year.Cannot have 29 days in this month.",
                invalid1900.errorMessage());
        assertEquals("", valid2000.errorMessage());
        assertEquals("N", valid2000.session().changeAction());
    }

    @Test
    @DisplayName("BR-ONL-032 Account Update date of birth must be in the past")
    void brOnl032_dobMustBePast() {
        // Given
        var fetched = update.search("00000000001", null).session();

        // When
        var today = update.update("00000000001", "ENTER", fetched, Map.of("dob", "2022-07-05"));
        var yesterday = update.update("00000000001", "ENTER", fetched, Map.of("dob", "2022-07-04"));

        // Then
        assertEquals("Date of Birth:cannot be in the future ", today.errorMessage());
        assertEquals("E", today.session().changeAction());
        assertEquals("", yesterday.errorMessage());
        assertEquals("N", yesterday.session().changeAction());
    }

    @Test
    @DisplayName("BR-ONL-033 Account Update numeric-required fields (FICO, Zip, EFT)")
    void brOnl033_numericRequired() {
        // Given
        var fetched = update.search("00000000001", null).session();

        // When
        var fico = update.update("00000000001", "ENTER", fetched, Map.of("ficoScore", "299"));
        var zip = update.update("00000000001", "ENTER", fetched, Map.of("zip", "1234A"));

        // Then
        assertEquals("FICO Score: should be between 300 and 850", fico.errorMessage());
        assertEquals("Zip must be all numeric.", zip.errorMessage());
        assertEquals("E", fico.session().changeAction());
        assertEquals("E", zip.session().changeAction());
    }

    @Test
    @DisplayName("BR-ONL-034 Account Update name, address, city and country text edits")
    void brOnl034_textEdits() {
        // Given
        var fetched = update.search("00000000001", null).session();

        // When
        var lastName = update.update("00000000001", "ENTER", fetched, Map.of("lastName", "O'Brien"));
        var city = update.update("00000000001", "ENTER", fetched, Map.of("city", "St. Louis"));
        var blankMiddle = update.update("00000000001", "ENTER", fetched, Map.of("middleName", ""));
        var unvalidatedFields = update.update("00000000001", "ENTER", fetched,
                Map.of("addrLine2", "Apt. #2", "govtId", "ID-123!"));

        // Then
        assertEquals("Last Name can have alphabets only.", lastName.errorMessage());
        assertEquals("City can have alphabets only.", city.errorMessage());
        assertEquals("", blankMiddle.errorMessage());
        assertEquals("N", blankMiddle.session().changeAction());
        assertEquals("", unvalidatedFields.errorMessage());
        assertEquals("N", unvalidatedFields.session().changeAction());
    }

    @Test
    @DisplayName("BR-ONL-035 Account Update state code and state/zip combination")
    void brOnl035_stateAndZip() {
        // Given
        var fetched = update.search("00000000001", null).session();

        // When
        var invalidState = update.update("00000000001", "ENTER", fetched, Map.of("stateCd", "ZZ"));
        var invalidCombination = update.update("00000000001", "ENTER", fetched,
                Map.of("stateCd", "NY", "zip", "90210"));

        // Then
        assertEquals("State: is not a valid state code", invalidState.errorMessage());
        assertEquals("Invalid zip code for state", invalidCombination.errorMessage());
    }

    @Test
    @DisplayName("BR-ONL-036 Account Update US phone number edit")
    void brOnl036_phoneNumber() {
        // Given
        var fetched = update.search("00000000001", null).session();

        // When
        var zeroArea = update.update("00000000001", "ENTER", fetched, Map.of("phone1Area", "000"));
        var reservedArea = update.update("00000000001", "ENTER", fetched, Map.of("phone1Area", "555"));
        var validLine = update.update("00000000001", "ENTER", fetched,
                Map.of("phone1Area", "212", "phone1Prefix", "555", "phone1Line", "0100",
                        "creditLimit", "6000.00"));

        // Then
        assertEquals("Phone Number 1: Area code cannot be zero", zeroArea.errorMessage());
        assertEquals("Phone Number 1: Not valid North America general purpose area code",
                reservedArea.errorMessage());
        assertEquals("", validLine.errorMessage());
        assertEquals("N", validLine.session().changeAction());
    }

    @Test
    @DisplayName("BR-ONL-036 Account Update US phone number edit — blank-test finding")
    void brOnl036_blankAreaPrefixFinding() {
        // Given
        var fetched = update.search("00000000001", null).session();

        // When
        var validated = update.update("00000000001", "ENTER", fetched,
                Map.of("phone1Area", "", "phone1Prefix", "", "phone1Line", "1234",
                        "creditLimit", "6000.00"));
        var saved = update.update("00000000001", "PF5", validated.session(), Map.of());

        // Then
        assertEquals("", validated.errorMessage());
        assertEquals("N", validated.session().changeAction());
        assertEquals("C", saved.session().changeAction());
        assertEquals("(   )   -1234", files.customer.phone1().stripTrailing());
    }

    @Test
    @DisplayName("BR-ONL-037 Account Update SSN edit")
    void brOnl037_ssnEdit() {
        // Given
        var fetched = update.search("00000000001", null).session();

        // When
        var invalidRange = update.update("00000000001", "ENTER", fetched, Map.of("ssn1", "666"));
        var zeroArea = update.update("00000000001", "ENTER", fetched, Map.of("ssn1", "000"));

        // Then
        assertEquals("SSN: First 3 chars: should not be 000, 666, or between 900 and 999",
                invalidRange.errorMessage());
        assertEquals("SSN: First 3 chars must not be zero.", zeroArea.errorMessage());
    }

    @Test
    @DisplayName("BR-ONL-038 Account Update validated changes await PF5 confirmation")
    void brOnl038_waitsForConfirmation() {
        // Given
        var fetched = update.search("00000000001", null).session();

        // When
        var firstEnter = update.update("00000000001", "ENTER", fetched,
                Map.of("creditLimit", "6000.00"));
        var callsBeforeSecondEnter = List.copyOf(files.calls);
        var secondEnter = update.update("00000000001", "ENTER", firstEnter.session(), Map.of());

        // Then
        assertEquals("N", firstEnter.session().changeAction());
        assertEquals("Changes validated.Press F5 to save", firstEnter.infoMessage());
        assertEquals("N", secondEnter.session().changeAction());
        assertEquals("6000.00", secondEnter.session().newDetails().creditLimit);
        assertEquals(callsBeforeSecondEnter, files.calls);
    }

    @Test
    @DisplayName("BR-ONL-039 PF5 save: READ UPDATE locks on account and customer")
    void brOnl039_accountLockFailure() {
        // Given
        var validated = changedCreditLimit();
        files.accountLockResp = 12;
        files.calls.clear();

        // When
        var result = update.update("00000000001", "PF5", validated, Map.of());

        // Then
        assertEquals("Could not lock account record for update", result.errorMessage());
        assertEquals("L", result.session().changeAction());
        assertEquals("Changes unsuccessful. Please try again", result.infoMessage());
        assertEquals(List.of("READ UPDATE ACCTDAT"), files.calls);
    }

    @Test
    @DisplayName("BR-ONL-039 PF5 save: READ UPDATE locks on account and customer — customer-lock finding")
    void brOnl039_customerLockFinding() {
        // Given
        var validated = changedCreditLimit();
        files.customerLockResp = 12;
        files.calls.clear();

        // When
        var result = update.update("00000000001", "PF5", validated, Map.of());

        // Then
        assertEquals("Could not lock customer record for update", result.errorMessage());
        assertEquals("C", result.session().changeAction());
        assertEquals("Changes committed to database", result.infoMessage());
        assertFalse(files.calls.contains("REWRITE ACCTDAT"));
    }

    @Test
    @DisplayName("BR-ONL-040 PF5 optimistic concurrency check")
    void brOnl040_concurrencyCheck() {
        // Given
        files.account = withBalance(files.account, "100.00");
        var validated = changedCreditLimit();
        files.account = withBalance(files.account, "150.00");
        files.calls.clear();

        // When
        var result = update.update("00000000001", "PF5", validated, Map.of());

        // Then
        assertEquals("Record changed by some one else. Please review", result.errorMessage());
        assertEquals("S", result.session().changeAction());
        assertFalse(files.calls.contains("REWRITE ACCTDAT"));
        assertEquals("150.00", files.account.currBal().toPlainString());
    }

    @Test
    @DisplayName("BR-ONL-041 PF5 record build, REWRITE and rollback")
    void brOnl041_rewritesAndRollback() {
        // Given
        var fetched = update.search("00000000001", null).session();
        var validated = update.update("00000000001", "ENTER", fetched,
                Map.of("creditLimit", "6000.00", "phone1Area", "212",
                        "phone1Prefix", "555", "phone1Line", "0100"));

        // When
        var saved = update.update("00000000001", "PF5", validated.session(), Map.of());

        // Then
        assertEquals("6000.00", files.account.creditLimit().toPlainString());
        assertEquals("(212)555-0100", files.customer.phone1().stripTrailing());
        assertEquals("Changes committed to database", saved.infoMessage());
        assertEquals("C", saved.session().changeAction());

        // Given
        files.accountRewriteResp = 0;
        files.customerRewriteResp = 12;
        var oldAccount = files.account;
        var oldCustomer = files.customer;
        var nextFetched = update.search("00000000001", null).session();
        var nextValidated = update.update("00000000001", "ENTER", nextFetched,
                Map.of("creditLimit", "7000.00"));

        // When
        var failed = update.update("00000000001", "PF5", nextValidated.session(), Map.of());

        // Then
        assertEquals("Update of record failed", failed.errorMessage());
        assertEquals("Changes unsuccessful. Please try again", failed.infoMessage());
        assertEquals("F", failed.session().changeAction());
        assertEquals(oldAccount, files.account);
        assertEquals(oldCustomer, files.customer);
        assertTrue(files.rollback);
    }

    @Test
    @DisplayName("BR-ONL-042 After save / PF12 cancel")
    void brOnl042_cancelFromEditError() {
        // Given
        var fetched = update.search("00000000001", null).session();
        var invalid = update.update("00000000001", "ENTER", fetched, Map.of("ficoScore", "200"));

        // When
        files.calls.clear();
        var cancelled = update.update("00000000001", "PF12", invalid.session(), Map.of());

        // Then
        assertEquals("FICO Score: should be between 300 and 850", invalid.errorMessage());
        assertEquals("S", cancelled.session().changeAction());
        assertEquals("", cancelled.errorMessage());
        assertEquals("720", cancelled.session().newDetails().ficoScore);
        assertEquals(List.of("CXACAIX:00000000001", "ACCTDAT:00000000001", "CUSTDAT:000000001"),
                files.calls);
    }

    private AccountUpdateSession changedCreditLimit() {
        var fetched = update.search("00000000001", null).session();
        return update.update("00000000001", "ENTER", fetched,
                Map.of("creditLimit", "6000.00")).session();
    }

    private static Account withBalance(Account account, String balance) {
        return new Account(account.acctId(), account.activeStatus(), new BigDecimal(balance),
                account.creditLimit(), account.cashCreditLimit(), account.openDate(),
                account.expirationDate(), account.reissueDate(), account.currCycCredit(),
                account.currCycDebit(), account.addrZip(), account.groupId());
    }
}
