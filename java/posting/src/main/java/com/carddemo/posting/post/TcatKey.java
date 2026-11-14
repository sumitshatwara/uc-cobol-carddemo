package com.carddemo.posting.post;

import com.carddemo.posting.Cobol;

/** FD-TRAN-CAT-KEY of TCATBALF: account id 9(11), type X(02), category 9(04). */
public record TcatKey(long acctId, String typeCd, long catCd) {

    /** The key as DISPLAYed by CBTRN02C, e.g. {@code 00000001006020001}. */
    public String display() {
        return Cobol.nines(acctId, 11) + Cobol.pic(typeCd, 2) + Cobol.nines(catCd, 4);
    }
}
