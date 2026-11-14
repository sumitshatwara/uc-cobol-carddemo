package com.carddemo.posting.post;

import com.carddemo.posting.Cobol;
import java.math.BigDecimal;

/**
 * One 350-byte daily transaction record (copybook CVTRA06Y, {@code DALYTRAN-RECORD}).  The raw
 * bytes are kept because the reject record and the posted transaction copy them unchanged.
 */
public record DailyTranRecord(String raw) {

    public static final int LRECL = 350;

    public DailyTranRecord {
        raw = Cobol.pic(raw, LRECL);
    }

    public String id() { return raw.substring(0, 16); }
    public String typeCd() { return raw.substring(16, 18); }
    public String catCdRaw() { return raw.substring(18, 22); }
    public long catCd() { return Cobol.zoned(catCdRaw(), 0).longValueExact(); }
    public String source() { return raw.substring(22, 32); }
    public String desc() { return raw.substring(32, 132); }
    public BigDecimal amt() { return Cobol.zoned(raw.substring(132, 143), 2); }
    public long merchantId() { return Cobol.zoned(raw.substring(143, 152), 0).longValueExact(); }
    public String merchantName() { return raw.substring(152, 202); }
    public String merchantCity() { return raw.substring(202, 252); }
    public String merchantZip() { return raw.substring(252, 262); }
    public String cardNum() { return raw.substring(262, 278); }
    public String origTs() { return raw.substring(278, 304); }
    public String procTs() { return raw.substring(304, 330); }
}
