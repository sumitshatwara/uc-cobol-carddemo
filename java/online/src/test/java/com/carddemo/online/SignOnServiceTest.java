package com.carddemo.online;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carddemo.online.signon.SignOnResponse;
import com.carddemo.online.signon.SignOnService;
import com.carddemo.online.store.InMemoryOnlineStore;
import com.carddemo.online.store.InMemoryOnlineStore.Op;
import com.carddemo.online.store.Resp;
import org.junit.jupiter.api.Test;

class SignOnServiceTest {

    private final InMemoryOnlineStore store = Fixtures.store();
    private final SignOnService svc = new SignOnService(store);

    @Test
    void BR_ONL_001_firstDisplayIsBlankWithCursorOnUserId() {
        SignOnResponse r = svc.firstDisplay();
        assertEquals("COSGN0A", r.screen());
        assertEquals("", r.message());
        assertEquals("USERID", r.cursor());
        assertNull(r.next());
    }

    @Test
    void BR_ONL_002_pf3SendsThankYouAndEndsSession() {
        SignOnResponse r = svc.process("PF3", "", "");
        assertEquals("PLAIN", r.screen());
        assertEquals("Thank you for using CardDemo application...", r.message());
        assertNull(r.next());
    }

    @Test
    void BR_ONL_003_otherKeysAreInvalid() {
        SignOnResponse r = svc.process("PF7", "USER0001", "PASSW0RD");
        assertEquals("COSGN0A", r.screen());
        assertEquals("Invalid key pressed. Please see below...", r.message());
        assertFalse(r.readUsrsec());
    }

    @Test
    void BR_ONL_004_userIdIsMandatoryAndUsrsecIsNotRead() {
        store.fail(Op.READ_USER, Resp.NOTOPEN, 0); // would show if USRSEC were read
        SignOnResponse r = svc.process("ENTER", "", "PASSW0RD");
        assertEquals("Please enter User ID ...", r.message());
        assertEquals("USERID", r.cursor());
        assertFalse(r.readUsrsec());
    }

    @Test
    void BR_ONL_005_passwordIsMandatory() {
        SignOnResponse r = svc.process("ENTER", "USER0001", "   ");
        assertEquals("Please enter Password ...", r.message());
        assertEquals("PASSWD", r.cursor());
    }

    @Test
    void BR_ONL_006_credentialsAreUpperCasedBeforeTheCheck() {
        SignOnResponse r = svc.process("ENTER", "user0001", "passw0rd");
        assertEquals("COMEN01C", r.next());
        assertEquals("USER0001", r.commarea().userId);
    }

    @Test
    void BR_ONL_007_unknownUser() {
        SignOnResponse r = svc.process("ENTER", "GHOST001", "ANYPASS1");
        assertEquals("User not found. Try again ...", r.message());
        assertEquals("USERID", r.cursor());
        assertTrue(r.readUsrsec());
    }

    @Test
    void BR_ONL_008_securityFileError() {
        store.fail(Op.READ_USER, Resp.NOTOPEN, 0);
        SignOnResponse r = svc.process("ENTER", "USER0001", "PASSW0RD");
        assertEquals("Unable to verify the User ...", r.message());
        assertEquals("USERID", r.cursor());
    }

    @Test
    void BR_ONL_009_wrongPassword() {
        SignOnResponse r = svc.process("ENTER", "USER0001", "WRONG123");
        assertEquals("Wrong Password. Try again ...", r.message());
        assertEquals("PASSWD", r.cursor());
        assertNull(r.next());
    }

    @Test
    void BR_ONL_010_successRoutesByUserType() {
        SignOnResponse admin = svc.process("ENTER", "ADMIN001", "PASSWORD");
        assertEquals("COADM01C", admin.next());
        assertEquals("A", admin.commarea().userType);
        assertEquals("CC00", admin.commarea().fromTranid);
        assertEquals("COSGN00C", admin.commarea().fromProgram);
        assertEquals(0, admin.commarea().pgmContext);
        SignOnResponse user = svc.process("ENTER", "USER0001", "PASSW0RD");
        assertEquals("COMEN01C", user.next());
        assertEquals("U", user.commarea().userType);
    }
}
