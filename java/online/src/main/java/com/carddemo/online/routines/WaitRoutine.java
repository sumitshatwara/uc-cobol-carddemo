package com.carddemo.online.routines;

import java.nio.charset.Charset;
import org.springframework.stereotype.Component;

/**
 * Port of HLASM MVSWAIT (app/asm/MVSWAIT.asm) and its COBOL caller COBSWAIT.
 *
 * <p>Linkage (BR-ONL-064): MVSWAIT has no save area of its own; STM 14,12,12(13) stores the
 * caller's registers in the caller's save area, R5 = 0(R1) is the address of the binary fullword
 * delay, which is stored in BINLBL inside the CSECT (not reentrant, F-13) and passed to the
 * ASMWAIT macro (STIMER WAIT, units of 1/100 s).  LM restores the registers, MVI 12(13),X'FF'
 * flags the caller's save area and SR 15,15 returns RC 0.
 *
 * <p>Java: {@code sleeper.sleepMillis(hundredths * 10)}; the sleeper is injectable so tests and
 * batch runs can use a no-op.
 */
@Component
public class WaitRoutine {

    /** Injectable sleep (the STIMER WAIT). */
    @FunctionalInterface
    public interface Sleeper {
        void sleepMillis(long millis);

        Sleeper NO_OP = millis -> {};
        Sleeper THREAD = millis -> {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
    }

    private final Sleeper sleeper;

    public WaitRoutine() {
        this(Sleeper.THREAD);
    }

    public WaitRoutine(Sleeper sleeper) {
        this.sleeper = sleeper;
    }

    /** MVSWAIT: wait {@code hundredths} of a second, return R15 = 0. */
    public int mvswait(long hundredths) {
        sleeper.sleepMillis(hundredths * 10L);
        return 0;
    }

    /**
     * COBSWAIT: ACCEPT PARM-VALUE X(8) FROM SYSIN, MOVE to MVSWAIT-TIME 9(8) COMP (no NUMERIC
     * check, F-13), CALL 'MVSWAIT'.  The MOVE of an alphanumeric to an unsigned numeric item
     * takes the numeric (low) nibble of each EBCDIC byte, which is what this reproduces.
     *
     * @return the delay passed to MVSWAIT, in hundredths of a second
     */
    public long cobswait(String sysinCard) {
        long time = parmToComp(sysinCard);
        mvswait(time);
        return time;
    }

    static long parmToComp(String card) {
        String v = (card == null ? "" : card);
        v = (v + " ".repeat(8)).substring(0, 8);
        byte[] ebcdic = v.getBytes(Charset.forName("IBM037"));
        long n = 0;
        for (byte b : ebcdic) {
            n = n * 10 + Math.min(9, b & 0x0F);
        }
        return n;
    }
}
