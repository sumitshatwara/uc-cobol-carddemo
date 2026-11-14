package com.carddemo.online.account;

import com.carddemo.online.Commarea;
import java.util.Map;

/**
 * One COACTUPC interaction.  The pseudo-conversational state (ACUP-CHANGE-ACTION and the
 * ACUP-OLD-DETAILS snapshot) travels with the request, like WS-THIS-PROGCOMMAREA in the commarea.
 *
 * @param action   attention key (ENTER, PF3, PF5, PF12, ...)
 * @param state    ACUP-CHANGE-ACTION (" " = details not fetched, S, E, N, C, L, F)
 * @param acctId   ACCTSIDI as typed
 * @param snapshot ACUP-OLD-DETAILS from the previous response (null before a fetch)
 * @param fields   the screen input fields (keys as in {@link AccountUpdateService#FIELD_KEYS})
 * @param commarea CARDDEMO-COMMAREA (null = EIBCALEN 0)
 */
public record UpdateRequest(String action, String state, String acctId, AcupDetails snapshot,
                            Map<String, String> fields, Commarea commarea) {}
