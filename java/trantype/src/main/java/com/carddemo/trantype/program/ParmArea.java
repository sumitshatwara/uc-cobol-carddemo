package com.carddemo.trantype.program;

/**
 * z/OS parameter area passed by {@code DSN RUN ... PARMS('...')}: a halfword length and the
 * text (DSNRUN's WS-PARM-AREA, CBTRNDYN's LINKAGE PARM-AREA).  The DSN attach splits its
 * command line on spaces, so only the first token of PARMS reaches the program.
 */
public record ParmArea(int length, String value) {

    public static final ParmArea NONE = new ParmArea(0, "");

    public static ParmArea of(String parms) {
        if (parms == null || parms.isBlank()) {
            return NONE;
        }
        String token = parms.strip().split(" +")[0];
        return new ParmArea(token.length(), token);
    }
}
