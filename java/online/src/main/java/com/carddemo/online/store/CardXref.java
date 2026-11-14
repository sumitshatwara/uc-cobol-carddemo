package com.carddemo.online.store;

/** CXACAIX (card xref by account) record, copybook CVACT03Y. Table carddemo_online.card_xref. */
public record CardXref(String cardNum, long custId, long acctId) {}
