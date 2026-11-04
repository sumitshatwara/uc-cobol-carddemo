package com.carddemo.online.routines;

import org.springframework.stereotype.Service;

import java.util.function.LongConsumer;

@Service
public class MvsWait {
    private final LongConsumer sleeper;

    public MvsWait() {
        this(hundredths -> { });
    }

    public MvsWait(LongConsumer sleeper) {
        this.sleeper = sleeper;
    }

    public WaitResult run(String sysinCard) {
        String parm = sysinCard == null ? "" : sysinCard;
        parm = (parm + " ".repeat(8)).substring(0, 8);
        StringBuilder digits = new StringBuilder(8);
        for (char value : parm.toCharArray()) {
            digits.append((char) ('0' + (value & 0x0f)));
        }
        long time = Long.parseLong(digits.toString());
        sleeper.accept(time);
        return new WaitResult(parm, time, 0, 0xff);
    }

    public record WaitResult(String parmValue, long mvsWaitTime, int rc, int callerSaveAreaByte12) {
    }
}
