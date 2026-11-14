package com.carddemo.online.account;

import com.carddemo.online.Pic;
import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.Set;

/**
 * The COACTUPC edit paragraphs (1215-1280) and the date edits of CSUTLDPY.  Every edit runs; the
 * first failing edit's message is kept ({@code IF WS-RETURN-MSG-OFF}), any failure sets
 * INPUT-ERROR.  Messages are {@code FUNCTION TRIM(WS-EDIT-VARIABLE-NAME)} + the literal, cut to
 * WS-RETURN-MSG X(75).
 */
final class FieldEdits {

    String message = "";
    boolean inputError;

    FieldEdits(String initialMessage) {
        this.message = initialMessage == null ? "" : initialMessage;
    }

    private void fail(String text) {
        inputError = true;
        if (Pic.isSpaces(message)) {
            message = Pic.x(text, 75);
        }
    }

    private static boolean blank(String v) {
        return Pic.isLowOrSpaces(v) || Pic.trim(v).isEmpty();
    }

    /** 1215-EDIT-MANDATORY. */
    boolean mandatory(String name, String v, int len) {
        if (blank(sub(v, len))) {
            fail(name + " must be supplied.");
            return false;
        }
        return true;
    }

    /** 1220-EDIT-YESNO. */
    boolean yesNo(String name, String v) {
        if (Pic.isLowOrSpaces(v) || "0".equals(v)) {
            fail(name + " must be supplied.");
            return false;
        }
        if (!"Y".equals(v) && !"N".equals(v)) {
            fail(name + " must be Y or N.");
            return false;
        }
        return true;
    }

    private static boolean onlyLetters(String v) {
        StringBuilder b = new StringBuilder();
        for (char c : v.toCharArray()) {
            b.append((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') ? ' ' : c);
        }
        return Pic.trim(b.toString()).isEmpty();
    }

    /** 1225-EDIT-ALPHA-REQD. */
    boolean alphaReqd(String name, String v, int len) {
        String s = sub(v, len);
        if (blank(s)) {
            fail(name + " must be supplied.");
            return false;
        }
        if (!onlyLetters(s)) {
            fail(name + " can have alphabets only.");
            return false;
        }
        return true;
    }

    /** 1235-EDIT-ALPHA-OPT. */
    boolean alphaOpt(String name, String v, int len) {
        String s = sub(v, len);
        if (blank(s)) {
            return true;
        }
        if (!onlyLetters(s)) {
            fail(name + " can have alphabets only.");
            return false;
        }
        return true;
    }

    /** 1245-EDIT-NUM-REQD. */
    boolean numReqd(String name, String v, int len) {
        String s = sub(v, len);
        if (blank(s)) {
            fail(name + " must be supplied.");
            return false;
        }
        if (!Pic.numeric(s)) {
            fail(name + " must be all numeric.");
            return false;
        }
        if (new BigDecimal(s).signum() == 0) {
            fail(name + " must not be zero.");
            return false;
        }
        return true;
    }

    /** 1250-EDIT-SIGNED-9V2 on the X(15) input. */
    boolean signed9v2(String name, String x15) {
        if (Pic.isLowOrSpaces(x15)) {
            fail(name + " must be supplied.");
            return false;
        }
        if (!Pic.testNumvalC(x15)) {
            fail(name + " is not valid");
            return false;
        }
        return true;
    }

    /** 1260-EDIT-US-PHONE-NUM, including the F-04 blank test (area code tested twice). */
    boolean phone(String name, String a, String b, String c) {
        if (Pic.isLowOrSpaces(a) && Pic.isLowOrSpaces(b) && (Pic.isSpaces(a) || Pic.isLow(c))) {
            return true;
        }
        boolean ok = true;
        // EDIT-AREA-CODE
        if (Pic.isLowOrSpaces(a)) {
            fail(name + ": Area code must be supplied.");
            ok = false;
        } else if (!Pic.numeric(a)) {
            fail(name + ": Area code must be A 3 digit number.");
            ok = false;
        } else if (Integer.parseInt(a) == 0) {
            fail(name + ": Area code cannot be zero");
            ok = false;
        } else if (!LookupCodes.GENERAL_PURPOSE_AREA_CODES.contains(Pic.trim(a))) {
            fail(name + ": Not valid North America general purpose area code");
            ok = false;
        }
        // EDIT-US-PHONE-PREFIX
        if (Pic.isLowOrSpaces(b)) {
            fail(name + ": Prefix code must be supplied.");
            ok = false;
        } else if (!Pic.numeric(b)) {
            fail(name + ": Prefix code must be A 3 digit number.");
            ok = false;
        } else if (Integer.parseInt(b) == 0) {
            fail(name + ": Prefix code cannot be zero");
            ok = false;
        }
        // EDIT-US-PHONE-LINENUM
        if (Pic.isLowOrSpaces(c)) {
            fail(name + ": Line number code must be supplied.");
            ok = false;
        } else if (!Pic.numeric(c)) {
            fail(name + ": Line number code must be A 4 digit number.");
            ok = false;
        } else if (Integer.parseInt(c) == 0) {
            fail(name + ": Line number code cannot be zero");
            ok = false;
        }
        return ok;
    }

    /** INVALID-SSN-PART1: 000, 666, 900-999. */
    static boolean invalidSsnPart1(int v) {
        return v == 0 || v == 666 || (v >= 900 && v <= 999);
    }

    /** 1265-EDIT-US-SSN. */
    void ssn(String p1, String p2, String p3) {
        String name1 = "SSN: First 3 chars";
        if (numReqd(name1, p1, 3) && invalidSsnPart1(Integer.parseInt(p1))) {
            fail(name1 + ": should not be 000, 666, or between 900 and 999");
        }
        numReqd("SSN 4th & 5th chars", p2, 2);
        numReqd("SSN Last 4 chars", p3, 4);
    }

    /** 1270-EDIT-US-STATE-CD. */
    boolean usState(String name, String st) {
        if (!LookupCodes.US_STATE_CODES.contains(st)) {
            fail(name + ": is not a valid state code");
            return false;
        }
        return true;
    }

    /** 1275-EDIT-FICO-SCORE (FICO-RANGE-IS-VALID 300 THRU 850). */
    void ficoRange(String name, String ficoX) {
        int v = Integer.parseInt(ficoX);
        if (v < 300 || v > 850) {
            fail(name + ": should be between 300 and 850");
        }
    }

    /** 1280-EDIT-US-STATE-ZIP-CD. */
    void stateZip(String st, String zip) {
        if (!LookupCodes.STATE_ZIP2_COMBOS.contains(st + sub(zip, 2))) {
            fail("Invalid zip code for state");
        }
    }

    private static int num2(String s) {
        return Pic.numeric(s) ? Integer.parseInt(s) : -1;
    }

    static final Set<Integer> DAY31_MONTHS = Set.of(1, 3, 5, 7, 8, 10, 12);

    /**
     * EDIT-DATE-CCYYMMDD (CSUTLDPY).
     *
     * @return WS-EDIT-DATE-IS-VALID after the edit (true once EDIT-DATE-LE has been reached)
     */
    boolean date(String name, String ccyy, String mm, String dd) {
        boolean yearOk = false;
        boolean monthOk = false;
        boolean dayOk = false;
        // EDIT-YEAR-CCYY
        if (Pic.isLowOrSpaces(ccyy)) {
            fail(name + " : Year must be supplied.");
        } else if (!Pic.numeric(ccyy)) {
            fail(name + " must be 4 digit number.");
        } else if (!ccyy.startsWith("19") && !ccyy.startsWith("20")) {
            fail(name + " : Century is not valid.");
        } else {
            yearOk = true;
        }
        // EDIT-MONTH
        int m = num2(mm);
        if (Pic.isLowOrSpaces(mm)) {
            fail(name + " : Month must be supplied.");
        } else if (m < 1 || m > 12) {
            fail(name + ": Month must be a number between 1 and 12.");
        } else {
            monthOk = true;
        }
        // EDIT-DAY
        int d = num2(dd);
        if (Pic.isLowOrSpaces(dd)) {
            fail(name + " : Day must be supplied.");
        } else if (!Pic.numeric(Pic.trim(dd)) || d < 1 || d > 31) {
            fail(name + ":day must be a number between 1 and 31.");
        } else {
            dayOk = true;
        }
        // EDIT-DAY-MONTH-YEAR
        if (!DAY31_MONTHS.contains(m) && d == 31) {
            fail(name + ":Cannot have 31 days in this month.");
            return false;
        }
        if (m == 2 && d == 30) {
            fail(name + ":Cannot have 30 days in this month.");
            return false;
        }
        if (m == 2 && d == 29 && Pic.numeric(ccyy)) {
            int yy = Integer.parseInt(ccyy.substring(2));
            int divBy = yy == 0 ? 400 : 4;
            if (Integer.parseInt(ccyy) % divBy != 0) {
                fail(name + ":Not a leap year.Cannot have 29 days in this month.");
                return false;
            }
        }
        if (!(yearOk && monthOk && dayOk)) {
            return false;
        }
        // EDIT-DATE-LE: CALL 'CSUTLDTC' (LE CEEDAYS); the date has passed every edit above
        try {
            LocalDate.of(Integer.parseInt(ccyy), m, d);
        } catch (DateTimeException e) {
            fail(name + " validation error Sev code: 0003 Message code: 2513");
        }
        return true; // EDIT-DATE-LE-EXIT: SET WS-EDIT-DATE-IS-VALID TO TRUE
    }

    /** EDIT-DATE-OF-BIRTH: INTEGER-OF-DATE(today) must be greater than INTEGER-OF-DATE(dob). */
    void dateOfBirth(String name, String ccyy, String mm, String dd, LocalDate today) {
        LocalDate dob = LocalDate.of(Integer.parseInt(ccyy), Integer.parseInt(mm), Integer.parseInt(dd));
        if (!today.isAfter(dob)) {
            fail(name + ":cannot be in the future ");
        }
    }

    static String sub(String v, int len) {
        String s = v == null ? "" : v;
        return s.length() > len ? s.substring(0, len) : s;
    }
}
