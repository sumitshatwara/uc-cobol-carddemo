package com.carddemo.posting.post;

import java.math.BigDecimal;

/** TRAN-RECORD (CVTRA05Y) as written by {@code 2000-POST-TRANSACTION}. */
public record PostedTransaction(String tranId, String typeCd, long catCd, String source, String desc, BigDecimal amt,
                                long merchantId, String merchantName, String merchantCity, String merchantZip,
                                String cardNum, String origTs, String procTs) {
}
