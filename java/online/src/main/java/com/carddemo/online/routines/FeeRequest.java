package com.carddemo.online.routines;

import java.math.BigDecimal;

/**
 * FEE_REQ area of CBFEECAL (built by CBFEEDRV as FEE-REQ).  DECIMAL FIXED fields are COMP-3
 * {@link BigDecimal}s at their declared scale; FIXED BINARY(15) are COMP halfwords ({@code int}).
 *
 * @param acctId      FEE_ACCT_ID CHAR(11)
 * @param groupId     FEE_GROUP_ID CHAR(10)
 * @param cycleBal    FEE_CYCLE_BAL DECIMAL FIXED(12,2)
 * @param lateDays    FEE_LATE_DAYS FIXED BINARY(15)
 * @param cycleDays   FEE_CYCLE_DAYS FIXED BINARY(15)
 */
public record FeeRequest(String acctId, String groupId, BigDecimal cycleBal, int lateDays, int cycleDays) {}
