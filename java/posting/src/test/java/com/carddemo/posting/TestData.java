package com.carddemo.posting;

import java.math.BigDecimal;

/** Builds synthetic 350-byte daily transaction records (CVTRA06Y). */
final class TestData {

    static final String PROC_TS = "2022-07-05-10.00.00.000000";

    private TestData() {}

    static String daily(String id, String type, int cat, String amount, String card, String origTs) {
        return Cobol.pic(id, 16) + Cobol.pic(type, 2) + Cobol.nines(cat, 4) + Cobol.pic("POS TERM", 10)
                + Cobol.pic("Synthetic purchase", 100) + Cobol.zonedStorage(new BigDecimal(amount), 9, 2) + Cobol.nines(100000001, 9)
                + Cobol.pic("Test Merchant", 50) + Cobol.pic("Testville", 50) + Cobol.pic("12345", 10) + Cobol.pic(card, 16)
                + Cobol.pic(origTs, 26) + Cobol.pic("", 26) + Cobol.pic("", 20);
    }

    static String daily(String id, String amount, String card) {
        return daily(id, "01", 1, amount, card, "2022-07-04-09.15.00.000000");
    }

    /** A posted CVTRA05Y record (350 bytes) for report tests. */
    static String tran(String id, String type, int cat, String amount, String card, String procTs) {
        return Cobol.pic(id, 16) + Cobol.pic(type, 2) + Cobol.nines(cat, 4) + Cobol.pic("POS TERM", 10)
                + Cobol.pic("Synthetic", 100) + Cobol.zonedStorage(new BigDecimal(amount), 9, 2) + Cobol.nines(1, 9)
                + Cobol.pic("M", 50) + Cobol.pic("C", 50) + Cobol.pic("Z", 10) + Cobol.pic(card, 16)
                + Cobol.pic("2022-07-04-09.15.00.000000", 26) + Cobol.pic(procTs, 26) + Cobol.pic("", 20);
    }

    static String tran(String id, String amount, String card) {
        return tran(id, "01", 1, amount, card, PROC_TS);
    }
}
