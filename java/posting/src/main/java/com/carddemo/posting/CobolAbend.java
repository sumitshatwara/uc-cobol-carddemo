package com.carddemo.posting;

/**
 * A user abend of a legacy program ({@code 9999-ABEND-PROGRAM}: {@code CALL 'CEE3ABD'} with
 * abend code 999).  Off-host the CEE3ABD stub ends the step with RC 12; the program's abend
 * DISPLAYs have already been written to SYSOUT when this is thrown.
 */
public class CobolAbend extends RuntimeException {

    public static final int ABEND_RC = 12;

    public CobolAbend(String message) {
        super(message);
    }
}
