package com.carddemo.online;

/**
 * CARDDEMO-COMMAREA (copybook COCOM01Y): the state CICS passes between the pseudo-conversational
 * programs.  REST clients carry it between calls, exactly like the terminal's commarea.
 */
public class Commarea {
    public String fromTranid = "";
    public String fromProgram = "";
    public String toTranid = "";
    public String toProgram = "";
    public String userId = "";
    /** CDEMO-USER-TYPE: 'A' admin, 'U' user. */
    public String userType = "";
    /** CDEMO-PGM-CONTEXT: 0 first entry, 1 re-enter. */
    public int pgmContext;
    public long custId;
    public String custFname = "";
    public String custMname = "";
    public String custLname = "";
    public long acctId;
    public String acctStatus = "";
    public String cardNum = "";
    public String lastMap = "";
    public String lastMapset = "";

    public Commarea copy() {
        Commarea c = new Commarea();
        c.fromTranid = fromTranid;
        c.fromProgram = fromProgram;
        c.toTranid = toTranid;
        c.toProgram = toProgram;
        c.userId = userId;
        c.userType = userType;
        c.pgmContext = pgmContext;
        c.custId = custId;
        c.custFname = custFname;
        c.custMname = custMname;
        c.custLname = custLname;
        c.acctId = acctId;
        c.acctStatus = acctStatus;
        c.cardNum = cardNum;
        c.lastMap = lastMap;
        c.lastMapset = lastMapset;
        return c;
    }

    public static Commarea orEmpty(Commarea c) {
        return c == null ? new Commarea() : c.copy();
    }
}
