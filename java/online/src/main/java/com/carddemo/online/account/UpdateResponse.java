package com.carddemo.online.account;

import com.carddemo.online.Commarea;
import java.util.Map;

/** Map CACTUPA (mapset COACTUP) after one COACTUPC interaction. */
public record UpdateResponse(String state, String infoMessage, String errorMessage, String acctIdField,
                             Map<String, String> fields, AcupDetails snapshot, boolean showSaveKeys, String next,
                             Map<String, String> abend, Commarea commarea) {}
