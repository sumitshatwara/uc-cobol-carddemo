package com.carddemo.online.menu;

import com.carddemo.online.Aid;
import com.carddemo.online.Commarea;
import com.carddemo.online.Messages;
import com.carddemo.online.Pic;
import java.util.List;
import java.util.function.Predicate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** COMEN01C (transaction CM00, map COMEN1A of mapset COMEN01): CardDemo main menu. */
@Service
public class MenuService {

    static final String TRANID = "CM00";
    static final String PGMNAME = "COMEN01C";

    /** COMEN02Y as shipped: 11 options, all user type 'U' (the '(Admin Only)' name of option 8 is commented out). */
    public static final List<MenuOption> COMEN02Y = List.of(
            new MenuOption(1, Pic.x("Account View", 35), "COACTVWC", "U"),
            new MenuOption(2, Pic.x("Account Update", 35), "COACTUPC", "U"),
            new MenuOption(3, Pic.x("Credit Card List", 35), "COCRDLIC", "U"),
            new MenuOption(4, Pic.x("Credit Card View", 35), "COCRDSLC", "U"),
            new MenuOption(5, Pic.x("Credit Card Update", 35), "COCRDUPC", "U"),
            new MenuOption(6, Pic.x("Transaction List", 35), "COTRN00C", "U"),
            new MenuOption(7, Pic.x("Transaction View", 35), "COTRN01C", "U"),
            new MenuOption(8, Pic.x("Transaction Add", 35), "COTRN02C", "U"),
            new MenuOption(9, Pic.x("Transaction Reports", 35), "CORPT00C", "U"),
            new MenuOption(10, Pic.x("Bill Payment", 35), "COBIL00C", "U"),
            new MenuOption(11, Pic.x("Pending Authorization View", 35), "COPAUS0C", "U"));

    private final List<MenuOption> options;
    /** EXEC CICS INQUIRE PROGRAM: true when the program is defined to the region. */
    private final Predicate<String> installed;

    @Autowired
    public MenuService() {
        this(COMEN02Y, pgm -> !"COPAUS0C".equals(pgm));
    }

    public MenuService(List<MenuOption> options, Predicate<String> installed) {
        this.options = options;
        this.installed = installed;
    }

    public List<MenuOption> options() {
        return options;
    }

    /**
     * First display or re-display of the menu.
     *
     * @see "BR-ONL-011, BR-ONL-012"
     */
    public MenuResponse display(Commarea in) {
        if (in == null) {
            // BR-ONL-011: EIBCALEN = 0 -> XCTL COSGN00C
            Commarea ca = new Commarea();
            ca.fromProgram = "COSGN00C";
            ca.toProgram = "COSGN00C";
            return new MenuResponse(options, "", "", null, "COSGN00C", ca);
        }
        Commarea ca = in.copy();
        if (ca.pgmContext == 0) {
            ca.pgmContext = 1; // SET CDEMO-PGM-REENTER
        }
        return new MenuResponse(options, "", "", null, null, ca);
    }

    /**
     * An attention on the menu (re-enter).
     *
     * @see "BR-ONL-013 .. BR-ONL-019"
     */
    public MenuResponse select(Commarea in, String action, String optionInput) {
        if (in == null) {
            return display(null);
        }
        Commarea ca = in.copy();
        String aid = Aid.normalize(action);
        if ("PF3".equals(aid)) {
            // BR-ONL-019: RETURN-TO-SIGNON-SCREEN
            ca.toProgram = "COSGN00C";
            return new MenuResponse(options, "", "", null, "COSGN00C", ca);
        }
        if (!"ENTER".equals(aid)) {
            return new MenuResponse(options, "", Messages.INVALID_KEY, null, null, ca);
        }
        // BR-ONL-013: trim trailing spaces, JUST RIGHT into X(2), spaces -> zeros
        String opt = Pic.x(optionInput, 2);
        int idx = 2;
        while (idx > 1 && opt.charAt(idx - 1) == ' ') {
            idx--;
        }
        String part = opt.substring(0, idx);
        String justRight = " ".repeat(2 - part.length()) + part;
        String wsOptionX = justRight.replace(' ', '0');
        String optionO = wsOptionX;
        boolean err = false;
        String message = "";
        String color = null;
        boolean numeric = Pic.numeric(wsOptionX);
        int wsOption = numeric ? Integer.parseInt(wsOptionX) : -1;
        // BR-ONL-014
        if (!numeric || wsOption > options.size() || wsOption == 0) {
            err = true;
            message = "Please enter a valid option number...";
        }
        // BR-ONL-015 (F-10: the subscript is not range-checked; outside the table we read
        // storage that is not 'A', so the edit cannot fire for an out-of-range option)
        if ("U".equals(ca.userType) && wsOption >= 1 && wsOption <= options.size()
                && "A".equals(options.get(wsOption - 1).userType())) {
            err = true;
            message = "No access - Admin Only option... ";
        }
        if (err) {
            return new MenuResponse(options, optionO, message, color, null, ca);
        }
        MenuOption o = options.get(wsOption - 1);
        String pgm = o.program();
        if ("COPAUS0C".equals(pgm)) {
            // BR-ONL-016: INQUIRE PROGRAM
            if (installed.test(pgm)) {
                return xctl(ca, pgm, optionO);
            }
            String name = o.name();
            int cut = name.indexOf("  ");
            return new MenuResponse(options, optionO,
                    "This option " + (cut < 0 ? name : name.substring(0, cut)) + " is not installed...", "RED", null, ca);
        }
        if (pgm.startsWith("DUMMY")) {
            // BR-ONL-017: name DELIMITED BY SPACE, no space before 'is' (F-10)
            String name = o.name();
            int cut = name.indexOf(' ');
            return new MenuResponse(options, optionO,
                    "This option " + (cut < 0 ? name : name.substring(0, cut)) + "is coming soon ...", "GREEN", null, ca);
        }
        // BR-ONL-018
        return xctl(ca, pgm, optionO);
    }

    private MenuResponse xctl(Commarea ca, String pgm, String optionO) {
        ca.fromTranid = TRANID;
        ca.fromProgram = PGMNAME;
        ca.pgmContext = 0;
        ca.toProgram = pgm;
        return new MenuResponse(options, optionO, "", null, pgm, ca);
    }
}
