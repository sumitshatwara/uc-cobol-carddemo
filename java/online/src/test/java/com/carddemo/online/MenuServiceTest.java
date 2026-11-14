package com.carddemo.online;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.carddemo.online.menu.MenuOption;
import com.carddemo.online.menu.MenuResponse;
import com.carddemo.online.menu.MenuService;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MenuServiceTest {

    private final MenuService svc = new MenuService();

    private static Commarea user(String type) {
        Commarea ca = new Commarea();
        ca.userType = type;
        ca.fromProgram = "COSGN00C";
        ca.pgmContext = 1;
        return ca;
    }

    @Test
    void BR_ONL_011_menuWithoutCommareaGoesToSignOn() {
        assertEquals("COSGN00C", svc.display(null).next());
    }

    @Test
    void BR_ONL_012_firstDisplayListsElevenOptionsAndSetsReenter() {
        Commarea ca = user("U");
        ca.pgmContext = 0;
        MenuResponse r = svc.display(ca);
        assertEquals(11, r.options().size());
        assertEquals("01. Account View", r.options().get(0).text());
        assertEquals("11. Pending Authorization View", r.options().get(10).text());
        assertEquals(1, r.commarea().pgmContext);
    }

    @Test
    void BR_ONL_013_optionIsRightJustifiedAndZeroFilled() {
        assertEquals("02", svc.select(user("U"), "ENTER", "2 ").option());
        assertEquals("02", svc.select(user("U"), "ENTER", " 2").option());
        assertEquals("COACTUPC", svc.select(user("U"), "ENTER", "2 ").next());
    }

    @Test
    void BR_ONL_014_optionRangeCheck() {
        for (String o : new String[] {"12", "0", "AB"}) {
            MenuResponse r = svc.select(user("U"), "ENTER", o);
            assertEquals("Please enter a valid option number...", r.message(), o);
            assertNull(r.next());
        }
    }

    @Test
    void BR_ONL_015_adminOnlyOptionRejectedForUser() {
        List<MenuOption> opts = new ArrayList<>(MenuService.COMEN02Y);
        opts.set(7, new MenuOption(8, Pic.x("Transaction Add", 35), "COTRN02C", "A"));
        MenuService s = new MenuService(opts, p -> true);
        MenuResponse r = s.select(user("U"), "ENTER", "08");
        assertEquals("No access - Admin Only option... ", r.message());
        assertNull(r.next());
        assertEquals("COTRN02C", s.select(user("A"), "ENTER", "08").next());
        // as shipped every option is 'U': the edit never fires
        assertEquals("COTRN02C", svc.select(user("U"), "ENTER", "08").next());
    }

    @Test
    void BR_ONL_016_pendingAuthorizationNotInstalled() {
        MenuResponse r = svc.select(user("U"), "ENTER", "11");
        assertEquals("This option Pending Authorization View is not installed...", r.message());
        assertEquals("RED", r.messageColor());
        assertNull(r.next());
        assertEquals("COPAUS0C", new MenuService(MenuService.COMEN02Y, p -> true).select(user("U"), "ENTER", "11").next());
    }

    @Test
    void BR_ONL_017_dummyProgramIsComingSoon() {
        List<MenuOption> opts = new ArrayList<>(MenuService.COMEN02Y);
        opts.set(8, new MenuOption(9, Pic.x("Statement Print", 35), "DUMMY001", "U"));
        MenuResponse r = new MenuService(opts, p -> true).select(user("U"), "ENTER", "9");
        assertEquals("This option Statementis coming soon ...", r.message());
        assertEquals("GREEN", r.messageColor());
    }

    @Test
    void BR_ONL_018_validOptionTransfersControl() {
        MenuResponse r = svc.select(user("U"), "ENTER", "1");
        assertEquals("COACTVWC", r.next());
        assertEquals("CM00", r.commarea().fromTranid);
        assertEquals("COMEN01C", r.commarea().fromProgram);
        assertEquals(0, r.commarea().pgmContext);
    }

    @Test
    void BR_ONL_019_pf3ReturnsToSignOnOtherKeysInvalid() {
        assertEquals("COSGN00C", svc.select(user("U"), "PF3", "").next());
        MenuResponse r = svc.select(user("U"), "PF5", "1");
        assertEquals("Invalid key pressed. Please see below...", r.message());
        assertNull(r.next());
    }
}
