package com.carddemo.online.routines;

import java.math.BigDecimal;
import java.util.List;

/**
 * Output fields of FEE_REQ after CBFEECAL returns, plus the SYSPRINT lines it wrote.
 *
 * @param feeAmt   FEE_AMT DECIMAL FIXED(9,2)
 * @param avgDaily FEE_AVG_DAILY DECIMAL FIXED(12,2)
 * @param rc       FEE_RC FIXED BINARY(15): 0 ok, 4 waived, 8 condition raised
 * @param sysprint PUT SKIP LIST output
 */
public record FeeResult(BigDecimal feeAmt, BigDecimal avgDaily, int rc, List<String> sysprint) {}
