package com.carddemo.online;

import java.util.Locale;

/**
 * Attention identifiers (EIBAID).  REST requests carry them as an {@code action} string: ENTER,
 * CLEAR, PA1..PA3, PF1..PF24.  {@link #fold} reproduces CSSTRPFY, which maps PF13-PF24 to
 * PF1-PF12 for the programs that use it (COACTVWC, COACTUPC).
 */
public final class Aid {
    private Aid() {}

    public static String normalize(String action) {
        if (action == null || action.isBlank()) {
            return "ENTER";
        }
        String a = action.trim().toUpperCase(Locale.ROOT);
        if (a.matches("PF0\\d")) {
            a = "PF" + a.substring(3);
        }
        return a;
    }

    /** CSSTRPFY YYYY-STORE-PFKEY: PF13..PF24 behave as PF1..PF12. */
    public static String fold(String action) {
        String a = normalize(action);
        if (a.matches("PF\\d+")) {
            int n = Integer.parseInt(a.substring(2));
            if (n >= 13 && n <= 24) {
                return "PF" + (n - 12);
            }
        }
        return a;
    }
}
