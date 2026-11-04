package com.carddemo.online.fees;

import java.math.BigDecimal;

public class FeeRequest {
    public String acctId = " ".repeat(11);
    public String groupId = " ".repeat(10);
    public BigDecimal cycleBal = BigDecimal.ZERO.setScale(2);
    public short lateDays;
    public short cycleDays;
    public BigDecimal feeAmt = BigDecimal.ZERO.setScale(2);
    public BigDecimal avgDaily = BigDecimal.ZERO.setScale(2);
    public short rc;
}
