package com.carddemo.online;

import com.carddemo.online.commarea.CardDemoCommarea;
import com.carddemo.online.menu.MenuOption;
import com.carddemo.online.menu.MenuService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MenuServiceTest extends OnlineTestSupport {
    @Test
    @DisplayName("BR-ONL-008 Menu entry, PF3 and invalid keys")
    void brOnl008_entryPf3AndEibcalenZero() {
        // Given
        var service = new MenuService(clock);
        var initial = new CardDemoCommarea();
        initial.fromProgram = "COSGN00C";
        initial.userType = "U";

        // When
        var firstDisplay = service.menu("U");
        var pf3State = new CardDemoCommarea();
        pf3State.toProgram = "OTHERPGM";
        var pf3 = service.select("PF3", "", pf3State);
        var noCommarea = service.select("ENTER", "01", null);
        var invalidKey = service.select("PF7", "", initial);

        // Then
        assertEquals(1, firstDisplay.commarea().pgmContext);
        assertEquals("COMEN01C", firstDisplay.commarea().fromProgram);
        assertEquals("COSGN00C", pf3.nextProgram());
        assertEquals("COSGN00C", pf3.commarea().toProgram);
        assertEquals("COSGN00C", noCommarea.nextProgram());
        assertEquals("Invalid key pressed. Please see below...", invalidKey.errorMessage());
        assertNull(invalidKey.nextProgram());
    }

    @Test
    @DisplayName("BR-ONL-009 Menu options table and display")
    void brOnl009_menuLines() {
        // Given
        var result = new MenuService(clock).menu("U");

        // When
        List<String> lines = result.menuLines();

        // Then
        assertEquals(12, lines.size());
        assertEquals("01. Account View" + " ".repeat(24), lines.get(0));
        assertEquals("11. Pending Authorization View" + " ".repeat(10), lines.get(10));
        assertNull(lines.get(11));
        assertEquals(40, lines.get(0).length());
        assertEquals("Transaction Add", result.options().get(7).name());
        assertEquals("U", result.options().get(7).userType());
    }

    @Test
    @DisplayName("BR-ONL-010 Menu option parsing")
    void brOnl010_optionParsing() {
        // Given
        var service = new MenuService(clock);
        var state = regularUser();

        // When
        var trailingSpace = service.select("ENTER", "1 ", state);
        var leadingSpace = service.select("ENTER", " 1", regularUser());
        var ten = service.select("ENTER", "10", regularUser());

        // Then
        assertEquals("01", trailingSpace.option());
        assertEquals("COACTVWC", trailingSpace.nextProgram());
        assertEquals("01", leadingSpace.option());
        assertEquals("COACTVWC", leadingSpace.nextProgram());
        assertEquals("10", ten.option());
        assertEquals("COBIL00C", ten.nextProgram());
    }

    @Test
    @DisplayName("BR-ONL-011 Menu invalid option number")
    void brOnl011_invalidNumbers() {
        // Given
        var service = new MenuService(clock);

        // When
        var zero = service.select("ENTER", "00", regularUser());
        var twelve = service.select("ENTER", "12", regularUser());
        var blank = service.select("ENTER", "  ", regularUser());

        // Then
        for (var result : List.of(zero, twelve, blank)) {
            assertEquals("Please enter a valid option number...", result.errorMessage());
            assertNull(result.nextProgram());
        }
        assertEquals("00", zero.option());
        assertEquals("12", twelve.option());
        assertEquals("00", blank.option());
    }

    @Test
    @DisplayName("BR-ONL-012 Menu admin-only option authorization")
    void brOnl012_adminVariantAndShippedTable() {
        // Given
        List<MenuOption> adminVariant = List.of(
                new MenuOption(1, "Account View", "COACTVWC", "U"),
                new MenuOption(2, "Account Update", "COACTUPC", "U"),
                new MenuOption(3, "Credit Card List", "COCRDLIC", "A"));
        var restricted = new MenuService(clock, adminVariant, program -> true);
        var shipped = new MenuService(clock);

        // When
        var denied = restricted.select("ENTER", "03", regularUser());
        var allowed = shipped.select("ENTER", "03", regularUser());

        // Then
        assertEquals("No access - Admin Only option... ", denied.errorMessage());
        assertEquals("RED", denied.errorColor());
        assertNull(denied.nextProgram());
        assertEquals("COCRDLIC", allowed.nextProgram());
    }

    @Test
    @DisplayName("BR-ONL-013 Menu not-installed and coming-soon options")
    void brOnl013_notInstalledAndComingSoon() {
        // Given
        var notInstalled = new MenuService(clock, new MenuService(clock).menu("U").options(),
                program -> false);
        var dummy = new MenuService(clock,
                List.of(new MenuOption(1, "Card Rewards", "DUMMY001", "U")), program -> true);

        // When
        var unavailable = notInstalled.select("ENTER", "11", regularUser());
        var comingSoon = dummy.select("ENTER", "1", regularUser());

        // Then
        assertEquals("This option Pending Authorization View is not installed...",
                unavailable.errorMessage());
        assertEquals("RED", unavailable.errorColor());
        assertNull(unavailable.nextProgram());
        assertEquals("This option Cardis coming soon ...", comingSoon.errorMessage());
        assertEquals("GREEN", comingSoon.errorColor());
        assertNull(comingSoon.nextProgram());
    }

    @Test
    @DisplayName("BR-ONL-014 Menu transfer to the selected program")
    void brOnl014_xctlCommarea() {
        // Given
        CardDemoCommarea state = regularUser();
        state.fromProgram = "COMEN01C";
        state.fromTranId = "CM00";
        state.userId = "USER0001";

        // When
        var result = new MenuService(clock).select("ENTER", "1", state);

        // Then
        assertEquals("COACTVWC", result.nextProgram());
        assertEquals("CM00", result.commarea().fromTranId);
        assertEquals("COMEN01C", result.commarea().fromProgram);
        assertEquals(0, result.commarea().pgmContext);
        assertEquals("USER0001", result.commarea().userId);
    }

    private static CardDemoCommarea regularUser() {
        CardDemoCommarea commarea = new CardDemoCommarea();
        commarea.userType = "U";
        return commarea;
    }
}
