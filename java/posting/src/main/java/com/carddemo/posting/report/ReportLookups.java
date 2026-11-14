package com.carddemo.posting.report;

import java.util.Optional;

/** The keyed reads of CBTRN03C: CARDXREF, TRANTYPE and TRANCATG (all opened INPUT, random access). */
public interface ReportLookups {

    Optional<Long> xrefAcctId(String cardNum);

    Optional<String> tranTypeDesc(String typeCd);

    Optional<String> tranCatDesc(String typeCd, long catCd);
}
