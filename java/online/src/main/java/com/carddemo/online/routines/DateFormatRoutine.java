package com.carddemo.online.routines;

import org.springframework.stereotype.Component;

/**
 * Port of HLASM COBDATFT (app/asm/COBDATFT.asm), called as {@code CALL 'COBDATFT' USING
 * CODATECN-REC}.  The record (DSECT COCDATFT / copybook CODATECN) is updated in place and the
 * routine always returns R15 = 0; errors only appear in COERMSG.
 *
 * <p>Register / linkage notes (BR-ONL-060): STM R14,R12,12(R13) saves the caller's registers
 * in the caller's save area; the routine chains its own 18-fullword SAVE area (back pointer in
 * SAVE+4, forward pointer in 8(R13)), uses R12 as base, and loads R2 from the one-entry
 * parameter list at 0(R1): R2 addresses CODATECN-REC.  On exit R13 is restored from SAVE+4,
 * LM R14,R12 restores the caller's registers and SR R15,R15 sets the return code 0.  In Java the
 * register save/restore has no equivalent; the record is a {@code char[80]} passed by reference.
 *
 * <p>Finding F-12 (reproduced): {@code CLC COINPDT+4,=C'-'} takes its length from COINPDT
 * (20 bytes), so it compares 20 bytes of the record starting at COINPDT+4 with the literal
 * {@code '-'} followed by the next 19 bytes of the literal pool ({@code 'INVALID INPUT'} and
 * whatever follows the pool).  The hyphen guard for type-1 input is therefore effectively never
 * true, and '2025-03-15' sent as type 1 is reformatted to '2025--0-3-'.
 */
@Component
public class DateFormatRoutine {

    /** Offsets in CODATECN-REC (DSECT COCDATFT). */
    public static final int COINTYPE = 0;
    public static final int COINPDT = 1;
    public static final int COOUTYPE = 21;
    public static final int COOUTDT = 22;
    public static final int COERMSG = 42;
    public static final int LENGTH = 80;

    /**
     * Literal pool of the COBDATFT CSECT as the 20-byte CLC sees it: =C'-' then =C'INVALID INPUT'
     * (literals of odd length, in order of first use), then 6 bytes past the pool that the
     * assembler does not define (modelled as X'00').
     */
    static final String LITERAL_POOL_20 = "-" + "INVALID INPUT" + "\0".repeat(6);

    /** Build a CODATECN-REC: blank-padded fields like a COBOL MOVE. */
    public static char[] record(String inType, String inDate, String outType, String outDate, String errMsg) {
        char[] r = " ".repeat(LENGTH).toCharArray();
        put(r, COINTYPE, 1, inType);
        put(r, COINPDT, 20, inDate);
        put(r, COOUTYPE, 1, outType);
        put(r, COOUTDT, 20, outDate);
        put(r, COERMSG, 38, errMsg);
        return r;
    }

    private static void put(char[] r, int off, int len, String v) {
        String s = v == null ? "" : v;
        for (int i = 0; i < len; i++) {
            r[off + i] = i < s.length() ? s.charAt(i) : ' ';
        }
    }

    public static String field(char[] r, int off, int len) {
        return new String(r, off, len);
    }

    /**
     * COBDATFT entry point.  Mutates {@code rec} in place and returns R15 (always 0).
     *
     * @see "BR-ONL-060, BR-ONL-061, BR-ONL-062, BR-ONL-063"
     */
    public int call(char[] rec) {
        char inType = rec[COINTYPE];
        if (inType == '1') {
            // VALIDIN1: CLC COINPDT+4(20),=C'-' (F-12)
            if (new String(rec, COINPDT + 4, 20).equals(LITERAL_POOL_20)) {
                return error(rec);
            }
            if (rec[COOUTYPE] == '2') {
                return error(rec);
            }
            // MVC COOUTDT(4),COINPDT ; MVI +4,'-' ; MVC +5(2),COINPDT+4 ; MVI +7,'-' ; MVC +8(2),COINPDT+6
            System.arraycopy(rec, COINPDT, rec, COOUTDT, 4);
            rec[COOUTDT + 4] = '-';
            System.arraycopy(rec, COINPDT + 4, rec, COOUTDT + 5, 2);
            rec[COOUTDT + 7] = '-';
            System.arraycopy(rec, COINPDT + 6, rec, COOUTDT + 8, 2);
            return 0;
        }
        if (inType == '2') {
            // VALIDIN2: the separator check is commented out in the source (lines 47-48)
            if (rec[COOUTYPE] == '1') {
                return error(rec);
            }
            System.arraycopy(rec, COINPDT, rec, COOUTDT, 4);
            System.arraycopy(rec, COINPDT + 5, rec, COOUTDT + 4, 2);
            System.arraycopy(rec, COINPDT + 8, rec, COOUTDT + 6, 2);
            return 0;
        }
        return error(rec);
    }

    /** GOTOERR: MVC COERMSG,=C'INVALID INPUT' (length 38 from COERMSG: the literal and pool bytes). */
    private int error(char[] rec) {
        String moved = (LITERAL_POOL_20.substring(1) + "\0".repeat(38)).substring(0, 38);
        // the visible text is 'INVALID INPUT'; bytes after it come from beyond the literal pool
        for (int i = 0; i < 38; i++) {
            rec[COERMSG + i] = moved.charAt(i) == '\0' ? ' ' : moved.charAt(i);
        }
        return 0;
    }
}
