package com.carddemo.online;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carddemo.online.routines.FeeDriver;
import com.carddemo.online.routines.FeeRequest;
import com.carddemo.online.routines.FeeResult;
import com.carddemo.online.routines.LateFeeCalculator;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** PL/I CBFEECAL port, checked against the six worked examples of docs/LEGACY-LANGUAGES.md. */
class LateFeeCalculatorTest {

    private final LateFeeCalculator calc = new LateFeeCalculator();
    private final FeeDriver driver = new FeeDriver();
    private static final String ACCT = "00000000099";

    private FeeResult run(String group, String bal, int late, int days) {
        return calc.calculate(new FeeRequest(ACCT, group, new BigDecimal(bal), late, days));
    }

    private static void expect(FeeResult r, String fee, String avg, int rc) {
        assertEquals(new BigDecimal(fee), r.feeAmt());
        assertEquals(new BigDecimal(avg), r.avgDaily());
        assertEquals(rc, r.rc());
    }

    // ---- the six worked examples ----

    @Test
    void BR_ONL_069_workedExample1_feeTruncatesTo3086() {
        expect(run("STANDARD", "1234.56", 10, 30), "30.86", "41.15", 0);
    }

    @Test
    void BR_ONL_070_workedExample2_floorFiveDollars() {
        expect(run("STANDARD", "100.00", 10, 30), "5.00", "3.33", 0);
    }

    @Test
    void BR_ONL_071_workedExample3_capThirtyFiveDollars() {
        expect(run("STANDARD", "5000.00", 10, 30), "35.00", "166.66", 0);
    }

    @Test
    void BR_ONL_068_workedExample4_goldWaiverRc4() {
        expect(run("GOLD", "80.00", 10, 30), "0.00", "2.66", 4);
    }

    @Test
    void BR_ONL_072_workedExample5_zeroDivideRc8() {
        FeeResult r = run("STANDARD", "1000.00", 10, 0);
        expect(r, "0.00", "0.00", 8);
        assertEquals(List.of("CBFEECAL: ZERODIVIDE FOR ACCOUNT 00000000099"), r.sysprint());
    }

    @Test
    void BR_ONL_067_workedExample6_nothingOverdue() {
        expect(run("GOLD", "0.00", 0, 30), "0.00", "0.00", 0);
    }

    // ---- further rule tests ----

    @Test
    void BR_ONL_065_driverBuildsRequestArea() {
        FeeRequest req = driver.buildRequest(Fixtures.account99());
        assertEquals(ACCT, req.acctId());
        assertEquals("STANDARD  ", req.groupId());
        assertEquals(new BigDecimal("1234.56"), req.cycleBal());
        assertEquals(10, req.lateDays());
        assertEquals(30, req.cycleDays());
        // non-positive balance: late days 0
        var a = Fixtures.account99();
        var zero = new com.carddemo.online.store.AccountRecord(a.acctId(), a.activeStatus(), a.currBal(),
                a.creditLimit(), a.cashCreditLimit(), a.openDate(), a.expirationDate(), a.reissueDate(),
                new BigDecimal("10.00"), new BigDecimal("10.00"), a.addrZip(), a.groupId());
        assertEquals(0, driver.buildRequest(zero).lateDays());
        // GOLD compare against the blank-padded CHAR(10) group id from the driver
        assertEquals(4, calc.calculate(new FeeRequest(ACCT, "GOLD      ", new BigDecimal("80.00"), 10, 30)).rc());
    }

    @Test
    void BR_ONL_066_averageDailyBalanceTruncatesAndIsAlwaysComputed() {
        assertEquals(new BigDecimal("3.33"), run("STANDARD", "100.00", 10, 30).avgDaily());
        assertEquals(new BigDecimal("3.33"), run("STANDARD", "100.00", 0, 30).avgDaily()); // before late test
        // F-15: not late but 0 cycle days still raises ZERODIVIDE
        assertEquals(8, run("STANDARD", "100.00", 0, 0).rc());
        // F-15: the GOLD test is case-sensitive
        assertEquals(0, run("gold", "80.00", 10, 30).rc());
    }

    @Test
    void BR_ONL_067_negativeLateDaysMeansNoFee() {
        expect(run("STANDARD", "1234.56", -1, 30), "0.00", "41.15", 0);
    }

    @Test
    void BR_ONL_068_goldAtHundredIsNotWaived() {
        expect(run("GOLD", "100.00", 10, 30), "5.00", "3.33", 0);
    }

    @Test
    void BR_ONL_069_lateFeeTruncatesToCents() {
        assertEquals(new BigDecimal("30.86"), run("STANDARD", "1234.56", 10, 30).feeAmt()); // 30.864, not 30.87
        assertEquals(new BigDecimal("30.87"), run("STANDARD", "1234.99", 10, 30).feeAmt()); // 30.87475 -> 30.87
    }

    @Test
    void BR_ONL_070_feeJustBelowFloorIsLifted() {
        assertEquals(new BigDecimal("5.00"), run("STANDARD", "199.99", 10, 30).feeAmt()); // 4.99975
        assertEquals(new BigDecimal("5.00"), run("STANDARD", "200.00", 10, 30).feeAmt());
    }

    @Test
    void BR_ONL_071_feeJustAboveCapIsCapped() {
        assertEquals(new BigDecimal("35.00"), run("STANDARD", "1400.04", 10, 30).feeAmt()); // 35.001
        assertEquals(new BigDecimal("34.99"), run("STANDARD", "1399.99", 10, 30).feeAmt());
    }

    @Test
    void BR_ONL_072_zeroDivideMessage() {
        FeeResult r = calc.calculate(new FeeRequest("00000000042", "STANDARD", new BigDecimal("1.00"), 10, 0));
        assertEquals("CBFEECAL: ZERODIVIDE FOR ACCOUNT 00000000042", r.sysprint().get(0));
    }

    @Test
    void BR_ONL_073_sizeConditionRc8() {
        FeeResult r = run("STANDARD", "9999999999.99", 10, 30);
        assertEquals(8, r.rc());
        assertEquals(new BigDecimal("0.00"), r.feeAmt());
        assertEquals(List.of("CBFEECAL: SIZE CONDITION FOR ACCOUNT 00000000099"), r.sysprint());
    }

    @Test
    void BR_ONL_073_unexpectedErrorRc8() {
        FeeResult r = calc.calculate(new FeeRequest(ACCT, "STANDARD", null, 10, 30));
        assertEquals(8, r.rc());
        assertTrue(r.sysprint().get(0).startsWith("CBFEECAL: UNEXPECTED ERROR, ACCOUNT 00000000099"));
    }

    @Test
    void BR_ONL_074_feeOutputLine() {
        FeeRequest req = new FeeRequest(ACCT, "STANDARD", new BigDecimal("1234.56"), 10, 30);
        String line = driver.feeLine(req, calc.calculate(req));
        assertEquals("00000000099 STANDARD          1234.56       30.86 00" + " ".repeat(28), line);
        assertEquals(80, line.length());
        FeeRequest neg = new FeeRequest(ACCT, "GOLD", new BigDecimal("-12.50"), 0, 30);
        assertTrue(driver.feeLine(neg, calc.calculate(neg)).startsWith(
                "00000000099 " + "GOLD      " + " " + "        -12.50" + " " + "       0.00" + " 00"));
    }
}
