package com.carddemo.online;

import com.carddemo.online.commarea.CardDemoCommarea;
import com.carddemo.online.files.Customer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountViewServiceTest extends OnlineTestSupport {
    @Test
    @DisplayName("BR-ONL-015 PF-key normalisation and valid keys on Account View")
    void brOnl015_pfKeys() {
        // Given
        CardDemoCommarea caller = new CardDemoCommarea();
        caller.fromProgram = "COMEN01C";
        caller.fromTranId = "CM00";

        // When
        var pf15 = view.view("", "PF15", caller);
        var pf8 = view.view("00000000001", "PF8", null);

        // Then
        assertEquals("COMEN01C", pf15.commarea().toProgram);
        assertEquals("00000000001", pf8.account().acctId());
        assertEquals(List.of("CXACAIX:00000000001", "ACCTDAT:00000000001", "CUSTDAT:000000001"),
                files.calls);
    }

    @Test
    @DisplayName("BR-ONL-016 PF3 exit from Account View / Account Update")
    void brOnl016_accountViewExit() {
        // Given
        CardDemoCommarea state = new CardDemoCommarea();
        state.fromProgram = "COMEN01C";
        state.fromTranId = "CM00";
        state.userType = "A";

        // When
        var result = view.view("", "PF3", state);

        // Then
        assertEquals("CM00", result.commarea().toTranId);
        assertEquals("COMEN01C", result.commarea().toProgram);
        assertEquals("COACTVWC", result.commarea().fromProgram);
        assertEquals("CAVW", result.commarea().fromTranId);
        assertEquals("U", result.commarea().userType);
        assertEquals(0, result.commarea().pgmContext);
        assertEquals("COACTVW", result.commarea().lastMapset);
        assertEquals("CACTVWA", result.commarea().lastMap);
        assertNull(result.account());
        assertEquals(0, files.calls.size());
    }

    @Test
    @DisplayName("BR-ONL-017 Account View blank account id")
    void brOnl017_blankAccountId() {
        // Given
        // The account field is blank.

        // When
        var result = view.view("", "ENTER", null);

        // Then
        assertEquals("No input received", result.errorMessage());
        assertEquals("*", result.acctIdDisplay());
        assertTrue(result.acctIdError());
        assertEquals(0, files.calls.size());
    }

    @Test
    @DisplayName("BR-ONL-018 Account View non-numeric or zero account id")
    void brOnl018_invalidAccountId() {
        // Given
        // One request is all zeroes; the other is non-numeric.

        // When
        var zero = view.view("00000000000", "ENTER", null);
        var nonNumeric = view.view("ABC", "ENTER", null);

        // Then
        assertEquals("Account Filter must  be a non-zero 11 digit number", zero.errorMessage());
        assertEquals("Account Filter must  be a non-zero 11 digit number", nonNumeric.errorMessage());
        assertTrue(zero.acctIdError());
        assertTrue(nonNumeric.acctIdError());
        assertEquals(0, files.calls.size());
    }

    @Test
    @DisplayName("BR-ONL-019 Account lookup chain: card xref -> account -> customer")
    void brOnl019_readChain() {
        // Given
        // The in-memory files contain the xref, account, and customer records.

        // When
        var result = view.view("00000000001", "ENTER", null);

        // Then
        assertEquals(List.of("CXACAIX:00000000001", "ACCTDAT:00000000001", "CUSTDAT:000000001"),
                files.calls);
        assertEquals("000000001", result.commarea().custId);
        assertEquals("4111111111111111", result.commarea().cardNum);
        assertNotNull(result.account());
        assertNotNull(result.customer());
    }

    @Test
    @DisplayName("BR-ONL-019 Account lookup chain: card xref -> account -> customer — missing account finding")
    void brOnl019_missingAccountFinding() {
        // Given
        files.accountResp = 13;

        // When
        var result = view.view("00000000003", "ENTER", null);

        // Then
        assertEquals(List.of("CXACAIX:00000000003", "ACCTDAT:00000000003", "CUSTDAT:000000001"),
                files.calls);
        assertNotNull(result.customer());
        assertNotNull(result.account());
        assertEquals("00000000003", result.account().acctId());
    }

    @Test
    @DisplayName("BR-ONL-020 Account lookup NOTFND messages (truncated to 75 chars)")
    void brOnl020_notFoundMessages() {
        // Given
        files.xrefResp = 13;

        // When
        String missingXref = view.view("00000000099", "ENTER", null).errorMessage();
        files.xrefResp = 0;
        files.xrefCustId = "000000009";
        files.customerResp = 13;
        String missingCustomer = view.view("00000000001", "ENTER", null).errorMessage();

        // Then
        assertEquals("Account:00000000099 not found in Cross ref file.  Resp:000000013  Reas:0000",
                missingXref);
        assertEquals(75, missingXref.length());
        assertEquals("CustId:000000009 not found in customer master.Resp: 000000013  REAS:0000000",
                missingCustomer);
        assertEquals(75, missingCustomer.length());
    }

    @Test
    @DisplayName("BR-ONL-021 Account lookup unexpected file errors")
    void brOnl021_fileError() {
        // Given
        files.accountResp = 19;

        // When
        String message = view.view("00000000001", "ENTER", null).errorMessage();

        // Then
        assertEquals("File Error: READ     on ACCTDAT   returned RESP 000000019 ,RESP2 000000017 ",
                message);
        assertEquals(75, message.length());
    }

    @Test
    @DisplayName("BR-ONL-022 Account View display mapping")
    void brOnl022_mapping() {
        // Given
        files.customer = new Customer("000000001", "JOHN", "Q", "SAMPLE",
                "100 MAIN STREET", "APT 1", "NEW YORK", "NY", "USA", "10001",
                "(212)555-0100", "(212)555-0101", "123456789", "DL0000001",
                "1980-01-15", "0000000001", "Y", 720);

        // When
        var result = view.view("00000000001", "ENTER", null);

        // Then
        assertEquals("00000000001", result.acctIdDisplay());
        assertEquals("194.00", result.account().currBal());
        assertEquals("5000.00", result.account().creditLimit());
        assertEquals("123-45-6789", result.customer().ssn());
        assertEquals("JOHN", result.customer().firstName());
        assertEquals("SAMPLE", result.customer().lastName());
        assertEquals("Enter or update id of account to display", result.infoMessage());
    }

    @Test
    @DisplayName("BR-ONL-022 Account View display mapping — missing account finding")
    void brOnl022_missingAccountFinding() {
        // Given
        files.accountResp = 13;

        // When
        var result = view.view("00000000003", "ENTER", null);

        // Then
        assertEquals("Account:00000000003 not found in Acct Master file.Resp:000000013  Reas:0000",
                result.errorMessage());
        assertNotNull(result.account());
        assertNotNull(result.customer());
    }
}
