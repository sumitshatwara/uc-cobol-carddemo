package com.carddemo.posting.report;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * TRANREPT STEP05R: DFSORT with the SYMNAMES of TRANREPT.jcl.
 * {@code INCLUDE COND=(TRAN-PROC-DT,GE,PARM-START-DATE,AND,TRAN-PROC-DT,LE,PARM-END-DATE)} and
 * {@code SORT FIELDS=(TRAN-CARD-NUM,A)}; keys compared byte by byte, equal keys keep input order.
 */
public final class TranReptSort {

    /** {@code PARM-START-DATE,C'2022-01-01'} in TRANREPT.jcl (hard-coded, not DATEPARM). */
    public static final String PARM_START_DATE = "2022-01-01";
    /** {@code PARM-END-DATE,C'2022-07-06'} in TRANREPT.jcl. */
    public static final String PARM_END_DATE = "2022-07-06";

    private TranReptSort() {}

    /** TRAN-PROC-DT,305,10,CH. */
    public static String procDate(String rec) {
        return rec.substring(304, 314);
    }

    /** TRAN-CARD-NUM,263,16. */
    public static String cardNum(String rec) {
        return rec.substring(262, 278);
    }

    /**
     * Filter on the processing date and stable-sort ascending on the card number.
     *
     * @see "BR-POST-031"
     */
    public static List<String> sort(List<String> backup, String start, String end) {
        List<String> out = new ArrayList<>();
        for (String r : backup) {
            String d = procDate(r);
            if (d.compareTo(start) >= 0 && d.compareTo(end) <= 0) {
                out.add(r);
            }
        }
        out.sort(Comparator.comparing(TranReptSort::cardNum)); // List.sort is stable
        return out;
    }

    public static List<String> sort(List<String> backup) {
        return sort(backup, PARM_START_DATE, PARM_END_DATE);
    }
}
