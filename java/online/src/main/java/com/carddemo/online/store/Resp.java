package com.carddemo.online.store;

/** CICS EIBRESP values used by the converted programs (DFHRESP). */
public final class Resp {
    private Resp() {}

    public static final int NORMAL = 0;
    public static final int NOTFND = 13;
    public static final int NOTOPEN = 19;
    public static final int DISABLED = 84;
    public static final int IOERR = 17;
    /** RESP2 CICS returns with NOTFND on a keyed READ. */
    public static final int NOTFND_RESP2 = 80;
}
