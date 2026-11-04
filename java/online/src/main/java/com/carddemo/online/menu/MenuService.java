package com.carddemo.online.menu;

import com.carddemo.common.clock.FrozenClock;
import com.carddemo.online.commarea.CardDemoCommarea;
import com.carddemo.online.commarea.ScreenHeader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

@Service
public class MenuService {
    private static final List<MenuOption> DEFAULT_OPTIONS = List.of(
            new MenuOption(1, "Account View", "COACTVWC", "U"),
            new MenuOption(2, "Account Update", "COACTUPC", "U"),
            new MenuOption(3, "Credit Card List", "COCRDLIC", "U"),
            new MenuOption(4, "Credit Card View", "COCRDSLC", "U"),
            new MenuOption(5, "Credit Card Update", "COCRDUPC", "U"),
            new MenuOption(6, "Transaction List", "COTRN00C", "U"),
            new MenuOption(7, "Transaction View", "COTRN01C", "U"),
            new MenuOption(8, "Transaction Add", "COTRN02C", "U"),
            new MenuOption(9, "Transaction Reports", "CORPT00C", "U"),
            new MenuOption(10, "Bill Payment", "COBIL00C", "U"),
            new MenuOption(11, "Pending Authorization View", "COPAUS0C", "U"));

    private final FrozenClock clock;
    private final List<MenuOption> options;
    private final Predicate<String> programInstalled;

    @Autowired
    public MenuService(FrozenClock clock) {
        this(clock, DEFAULT_OPTIONS, program -> !"COPAUS0C".equals(program));
    }

    public MenuService(FrozenClock clock, List<MenuOption> options, Predicate<String> programInstalled) {
        this.clock = clock;
        this.options = List.copyOf(options);
        this.programInstalled = programInstalled;
    }

    public MenuResponse menu(String userType) {
        CardDemoCommarea commarea = new CardDemoCommarea();
        commarea.userType = userType;
        commarea.fromTranId = "CM00";
        commarea.fromProgram = "COMEN01C";
        commarea.pgmContext = 1;
        return new MenuResponse(header(), options, menuLines(), "", commarea);
    }

    public MenuSelectionResponse select(String aid, String option, CardDemoCommarea commarea) {
        if (commarea == null) {
            return new MenuSelectionResponse("", null, option, "COSGN00C", new CardDemoCommarea());
        }
        CardDemoCommarea state = commarea;
        String action = aid == null || aid.isBlank() ? "ENTER" : aid.trim().toUpperCase();
        if ("PF3".equals(action) || "F3".equals(action)) {
            state.toProgram = "COSGN00C";
            return new MenuSelectionResponse("", null, option, state.toProgram, state);
        }
        if (!"ENTER".equals(action)) {
            return new MenuSelectionResponse("Invalid key pressed. Please see below...", "RED",
                    option, null, state);
        }
        String normalizedOption = normalizeOption(option);
        Integer selected = parseOption(normalizedOption);
        if (selected == null || selected < 1 || selected > options.size()) {
            return new MenuSelectionResponse("Please enter a valid option number...", "RED",
                    normalizedOption, null, state);
        }
        MenuOption item = options.stream()
                .filter(candidate -> candidate.num() == selected)
                .findFirst()
                .orElse(null);
        if (item == null) {
            return new MenuSelectionResponse("Please enter a valid option number...", "RED",
                    normalizedOption, null, state);
        }
        if ("U".equals(state.userType) && "A".equals(item.userType())) {
            return new MenuSelectionResponse("No access - Admin Only option... ", "RED",
                    normalizedOption, null, state);
        }
        if (item.program().startsWith("DUMMY")) {
            return new MenuSelectionResponse("This option " + delimitedBySpace(item.name())
                    + "is coming soon ...", "GREEN", normalizedOption, null, state);
        }
        if ("COPAUS0C".equals(item.program()) && !programInstalled.test(item.program())) {
            return new MenuSelectionResponse("This option " + delimitedByTwoSpaces(item.name())
                    + " is not installed...", "RED", normalizedOption, null, state);
        }
        state.fromTranId = "CM00";
        state.fromProgram = "COMEN01C";
        state.pgmContext = 0;
        return new MenuSelectionResponse("", null, normalizedOption, item.program(), state);
    }

    private ScreenHeader header() {
        return ScreenHeader.from(clock, ScreenHeader.TITLE_01, ScreenHeader.TITLE_02,
                "CC00", "COMEN01C");
    }

    private List<String> menuLines() {
        List<String> lines = new ArrayList<>(12);
        for (int number = 1; number <= 12; number++) {
            int optionNumber = number;
            MenuOption option = options.stream()
                    .filter(candidate -> candidate.num() == optionNumber)
                    .findFirst()
                    .orElse(null);
            if (option == null) {
                lines.add(null);
            } else {
                String text = String.format("%02d. %s", number, option.name());
                lines.add(fixed(text, 40));
            }
        }
        return Collections.unmodifiableList(lines);
    }

    private static String delimitedBySpace(String value) {
        String name = value == null ? "" : value;
        int end = name.indexOf(' ');
        return end < 0 ? name : name.substring(0, end);
    }

    private static String delimitedByTwoSpaces(String value) {
        String name = value == null ? "" : value;
        int end = name.indexOf("  ");
        return end < 0 ? name.stripTrailing() : name.substring(0, end);
    }

    private static String normalizeOption(String option) {
        String input = option == null ? "" : option;
        input = fixed(input, 2).substring(0, 2);
        int lastNonSpace = 1;
        while (lastNonSpace > 0 && input.charAt(lastNonSpace) == ' ') {
            lastNonSpace--;
        }
        String prefix = input.substring(0, lastNonSpace + 1);
        String rightJustified = " ".repeat(2 - prefix.length()) + prefix;
        return rightJustified.replace(' ', '0');
    }

    private static Integer parseOption(String option) {
        if (!option.chars().allMatch(Character::isDigit)) {
            return null;
        }
        try {
            return Integer.parseInt(option);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String fixed(String value, int width) {
        String source = value == null ? "" : value;
        return source.length() >= width ? source.substring(0, width)
                : source + " ".repeat(width - source.length());
    }

    public record MenuResponse(ScreenHeader header, List<MenuOption> options, List<String> menuLines,
                               String errorMessage,
                               CardDemoCommarea commarea) {
    }

    public record MenuSelectionResponse(String errorMessage, String errorColor, String option,
                                        String nextProgram, CardDemoCommarea commarea) {
    }
}
