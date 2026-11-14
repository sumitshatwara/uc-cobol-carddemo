package com.carddemo.interest;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/** One TRAN-RECORD (CVTRA05Y) written to the new SYSTRAN generation by 1300-B-WRITE-TX. */
public record InterestTran(String tranId, String typeCd, long catCd, String source, String desc, BigDecimal amt,
                           long merchantId, String merchantName, String merchantCity, String merchantZip,
                           String cardNum, String origTs, String procTs) {

    /** Copybook field name -> value, for {@code DatasetWriter.writeFieldMaps(dsn, "CVTRA05Y", ...)}. */
    public Map<String, Object> toFields() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("TRAN-ID", tranId);
        m.put("TRAN-TYPE-CD", typeCd);
        m.put("TRAN-CAT-CD", BigDecimal.valueOf(catCd));
        m.put("TRAN-SOURCE", source);
        m.put("TRAN-DESC", desc);
        m.put("TRAN-AMT", amt);
        m.put("TRAN-MERCHANT-ID", BigDecimal.valueOf(merchantId));
        m.put("TRAN-MERCHANT-NAME", merchantName);
        m.put("TRAN-MERCHANT-CITY", merchantCity);
        m.put("TRAN-MERCHANT-ZIP", merchantZip);
        m.put("TRAN-CARD-NUM", cardNum);
        m.put("TRAN-ORIG-TS", origTs);
        m.put("TRAN-PROC-TS", procTs);
        return m;
    }
}
