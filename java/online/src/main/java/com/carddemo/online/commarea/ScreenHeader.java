package com.carddemo.online.commarea;

import com.carddemo.common.clock.FrozenClock;

import java.time.format.DateTimeFormatter;
import java.util.Locale;

public record ScreenHeader(String title01, String title02, String tranId, String pgmName,
                           String curDate, String curTime) {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MM/dd/yy", Locale.ROOT);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);
    public static final String TITLE_01 = "      AWS Mainframe Modernization       ";
    public static final String TITLE_02 = "              CardDemo                  ";

    public static ScreenHeader from(FrozenClock clock, String title01, String title02,
                                    String tranId, String pgmName) {
        return new ScreenHeader(title01, title02, tranId, pgmName,
                DATE.format(clock.instant().atZone(clock.getZone())),
                TIME.format(clock.instant().atZone(clock.getZone())));
    }
}
