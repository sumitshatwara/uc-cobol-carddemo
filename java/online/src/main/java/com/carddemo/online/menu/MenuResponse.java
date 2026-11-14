package com.carddemo.online.menu;

import com.carddemo.online.Commarea;
import java.util.List;

/**
 * Result of one COMEN01C interaction.
 *
 * @param options      the OPTN001O..OPTN012O lines
 * @param option       OPTIONO as redisplayed (e.g. "02")
 * @param message      ERRMSGO
 * @param messageColor ERRMSGC when set: RED / GREEN
 * @param next         program XCTLed to, null when the menu is resent
 */
public record MenuResponse(List<MenuOption> options, String option, String message, String messageColor,
                           String next, Commarea commarea) {}
