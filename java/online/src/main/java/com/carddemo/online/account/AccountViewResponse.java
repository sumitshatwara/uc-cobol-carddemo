package com.carddemo.online.account;

import com.carddemo.online.Commarea;
import java.util.Map;

/**
 * Map CACTVWA (mapset COACTVW) after one COACTVWC interaction.
 *
 * @param acctIdField   ACCTSIDO as shown ('*' in red after a blank search)
 * @param acctIdColor   ACCTSIDC: RED or DEFAULT
 * @param errorMessage  ERRMSGO
 * @param infoMessage   INFOMSGO
 * @param account       account fields (null unless the account or the customer was found)
 * @param customer      customer fields (null unless the customer was found)
 * @param next          program XCTLed to on PF3, else null
 * @param abend         abend data for an unexpected scenario, else null
 */
public record AccountViewResponse(String acctIdField, String acctIdColor, String errorMessage, String infoMessage,
                                  Map<String, String> account, Map<String, String> customer, long custId,
                                  String cardNum, String next, Map<String, String> abend, Commarea commarea) {}
