package com.carddemo.online;

import com.carddemo.online.files.UserSecurity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class SignOnServiceTest extends OnlineTestSupport {
    @Test
    @DisplayName("BR-ONL-001 Sign-on first display and screen header")
    void brOnl001_initialScreen() {
        // Given
        // The shared frozen clock is 2022-07-05 10:00:00 and no commarea is supplied.

        // When
        var result = signon.initialScreen();

        // Then
        assertEquals("COSGN0A", result.screen());
        assertEquals("", result.errorMessage());
        assertEquals("USERID", result.cursorField());
        assertEquals("      AWS Mainframe Modernization       ", result.header().title01());
        assertEquals("              CardDemo                  ", result.header().title02());
        assertEquals("CC00", result.header().tranId());
        assertEquals("COSGN00C", result.header().pgmName());
        assertEquals("07/05/22", result.header().curDate());
        assertEquals("10:00:00", result.header().curTime());
        assertEquals("CC00", result.returnTransId());
        assertNotNull(result.commarea());
        assertEquals(160, result.commarea().encode().length());
    }

    @Test
    @DisplayName("BR-ONL-002 Sign-on PF3 and invalid keys")
    void brOnl002_signonAids() {
        // Given
        // The sign-on screen is displayed.

        // When
        var pf3 = signon.signOn("PF3", "", "");
        var pf7 = signon.signOn("PF7", "", "");

        // Then
        assertEquals("Thank you for using CardDemo application...", pf3.plainText());
        assertNull(pf3.nextProgram());
        assertNull(pf3.returnTransId());
        assertEquals("Invalid key pressed. Please see below...", pf7.errorMessage());
        assertEquals("CC00", pf7.returnTransId());
        assertNull(pf7.nextProgram());
    }

    @Test
    @DisplayName("BR-ONL-003 Sign-on blank User ID / Password validation")
    void brOnl003_blankFields() {
        // Given
        // First case has a blank user ID; second case has a blank password.

        // When
        var blankUser = signon.signOn("ENTER", "", "PASS0001");
        var blankPassword = signon.signOn("ENTER", "USER0001", " ");

        // Then
        assertEquals("Please enter User ID ...", blankUser.errorMessage());
        assertEquals("USERID", blankUser.cursorField());
        assertEquals("CC00", blankUser.returnTransId());
        assertEquals("Please enter Password ...", blankPassword.errorMessage());
        assertEquals("PASSWD", blankPassword.cursorField());
        assertEquals("CC00", blankPassword.returnTransId());
        assertEquals(0, files.calls.size());
    }

    @Test
    @DisplayName("BR-ONL-004 Sign-on upper-cases User ID and Password")
    void brOnl004_uppercaseInputs() {
        // Given
        // USRSEC holds USER0001 with the upper-case password PASSWORD.

        // When
        var result = signon.signOn("ENTER", "user0001", "password");

        // Then
        assertEquals("USRSEC:USER0001", files.calls.get(0));
        assertEquals("COMEN01C", result.nextProgram());
        assertEquals("USER0001", result.commarea().userId);
        assertEquals("U", result.commarea().userType);
    }

    @Test
    @DisplayName("BR-ONL-004 Sign-on upper-cases User ID and Password — lower-case stored password finding")
    void brOnl004_lowercaseStoredPasswordFinding() {
        // Given
        files.user = new UserSecurity("USER0001", "JOHN", "SAMPLE", "password", "U");

        // When
        var result = signon.signOn("ENTER", "user0001", "password");

        // Then
        assertEquals("Wrong Password. Try again ...", result.errorMessage());
        assertEquals("PASSWD", result.cursorField());
        assertNull(result.nextProgram());
        assertEquals("CC00", result.returnTransId());
    }

    @Test
    @DisplayName("BR-ONL-005 Sign-on USRSEC lookup return codes")
    void brOnl005_fileResponses() {
        // Given
        files.userResp = 13;

        // When
        var missingUser = signon.signOn("ENTER", "NOUSER01", "PASSWORD");
        files.userResp = 12;
        var ioError = signon.signOn("ENTER", "USER0001", "PASSWORD");

        // Then
        assertEquals("User not found. Try again ...", missingUser.errorMessage());
        assertEquals("USERID", missingUser.cursorField());
        assertEquals("CC00", missingUser.returnTransId());
        assertEquals("Unable to verify the User ...", ioError.errorMessage());
        assertEquals("USERID", ioError.cursorField());
        assertEquals("CC00", ioError.returnTransId());
        assertEquals("USRSEC:NOUSER01", files.calls.get(0));
        assertEquals("USRSEC:USER0001", files.calls.get(1));
    }

    @Test
    @DisplayName("BR-ONL-006 Sign-on wrong password")
    void brOnl006_wrongPassword() {
        // Given
        // USRSEC contains USER0001 with password PASSWORD.

        // When
        var result = signon.signOn("ENTER", "USER0001", "WRONGPW1");

        // Then
        assertEquals("Wrong Password. Try again ...", result.errorMessage());
        assertEquals("PASSWD", result.cursorField());
        assertNull(result.nextProgram());
        assertEquals("CC00", result.returnTransId());
        assertEquals("", result.commarea().userType);
    }

    @Test
    @DisplayName("BR-ONL-007 Sign-on success: COMMAREA and admin vs regular routing")
    void brOnl007_routing() {
        // Given
        files.user = new UserSecurity("USER0001", "JOHN", "SAMPLE", "PASSWORD", "U");

        // When
        var regular = signon.signOn("ENTER", "USER0001", "PASSWORD");
        files.user = new UserSecurity("ADMIN001", "ADMIN", "USER", "PASSWORD", "A");
        var admin = signon.signOn("ENTER", "ADMIN001", "PASSWORD");

        // Then
        assertEquals("COMEN01C", regular.nextProgram());
        assertEquals("USER0001", regular.commarea().userId);
        assertEquals("U", regular.commarea().userType);
        assertEquals("COSGN00C", regular.commarea().fromProgram);
        assertEquals("CC00", regular.commarea().fromTranId);
        assertEquals(0, regular.commarea().pgmContext);
        assertNull(regular.returnTransId());
        assertEquals("COADM01C", admin.nextProgram());
        assertEquals("A", admin.commarea().userType);
        assertEquals(0, admin.commarea().pgmContext);
        assertNull(admin.returnTransId());
    }

    @Test
    @DisplayName("BR-ONL-007 Sign-on success: COMMAREA and admin vs regular routing — unexpected user type finding")
    void brOnl007_unexpectedUserTypeFinding() {
        // Given
        files.user = new UserSecurity("ODD00001", "ODD", "USER", "PASSWORD", "X");

        // When
        var result = signon.signOn("ENTER", "ODD00001", "PASSWORD");

        // Then
        assertEquals("COMEN01C", result.nextProgram());
        assertEquals("X", result.commarea().userType);
        assertEquals("ODD00001", result.commarea().userId);
    }
}
