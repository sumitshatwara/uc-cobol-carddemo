package com.carddemo.online.signon;

import com.carddemo.online.Commarea;

/**
 * Result of one COSGN00C interaction.
 *
 * @param screen  "COSGN0A" (map sent) or "PLAIN" (SEND TEXT, session ended)
 * @param message ERRMSG / plain text
 * @param cursor  field that gets the cursor (USERID / PASSWD), null for PLAIN
 * @param next    program XCTLed to (COADM01C / COMEN01C), null when the screen is resent
 * @param commarea CARDDEMO-COMMAREA after the interaction
 * @param readUsrsec whether USRSEC was read (for the tests of BR-ONL-004)
 */
public record SignOnResponse(String screen, String message, String cursor, String next, Commarea commarea,
                             boolean readUsrsec) {}
