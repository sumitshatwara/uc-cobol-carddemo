package com.carddemo.online;

import com.carddemo.online.fees.FeeRequest;
import com.carddemo.online.fees.LateFeeCalculator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LateFeeCalculatorTest extends OnlineTestSupport {
    private final LateFeeCalculator calculator = new LateFeeCalculator();

    @Test
    @DisplayName("BR-ONL-049 CBFEECAL late fee is truncated to cents")
    void brOnl049_truncatedFee() {
        // Given
        FeeRequest request = fee("STANDARD", "1234.56", 10, 30);
        List<String> sysprint = new ArrayList<>();

        // When
        calculator.calculate(request, sysprint);

        // Then
        assertEquals("30.86", request.feeAmt.toPlainString());
        assertEquals("41.15", request.avgDaily.toPlainString());
        assertEquals(0, request.rc);
        assertEquals(List.of(), sysprint);
    }

    @Test
    @DisplayName("BR-ONL-050 CBFEECAL floor 5.00 and cap 35.00")
    void brOnl050_floorAndCap() {
        // Given
        FeeRequest floor = fee("STANDARD", "100.00", 10, 30);
        FeeRequest cap = fee("STANDARD", "5000.00", 10, 30);
        List<String> sysprint = new ArrayList<>();

        // When
        calculator.calculate(floor, sysprint);
        calculator.calculate(cap, sysprint);

        // Then
        assertEquals("5.00", floor.feeAmt.toPlainString());
        assertEquals("3.33", floor.avgDaily.toPlainString());
        assertEquals(0, floor.rc);
        assertEquals("35.00", cap.feeAmt.toPlainString());
        assertEquals("166.66", cap.avgDaily.toPlainString());
        assertEquals(0, cap.rc);
        assertEquals(List.of(), sysprint);
    }

    @Test
    @DisplayName("BR-ONL-051 CBFEECAL GOLD waiver (RC 4)")
    void brOnl051_goldWaiver() {
        // Given
        FeeRequest request = fee("GOLD", "80.00", 10, 30);
        List<String> sysprint = new ArrayList<>();

        // When
        calculator.calculate(request, sysprint);

        // Then
        assertEquals("0.00", request.feeAmt.toPlainString());
        assertEquals("2.66", request.avgDaily.toPlainString());
        assertEquals(4, request.rc);
        assertEquals(List.of(), sysprint);
    }

    @Test
    @DisplayName("BR-ONL-052 CBFEECAL average daily balance and ZERODIVIDE (RC 8)")
    void brOnl052_zeroDays() {
        // Given
        FeeRequest request = fee("00000000005", "STANDARD", "1000.00", 10, 0);
        List<String> sysprint = new ArrayList<>();

        // When
        calculator.calculate(request, sysprint);

        // Then
        assertEquals(List.of("CBFEECAL: ZERODIVIDE FOR ACCOUNT "
                + " ".repeat(15) + "00000000005"), sysprint);
        assertEquals("0.00", request.feeAmt.toPlainString());
        assertEquals("0.00", request.avgDaily.toPlainString());
        assertEquals(8, request.rc);
    }

    @Test
    @DisplayName("BR-ONL-053 CBFEECAL nothing overdue (late days <= 0)")
    void brOnl053_notLate() {
        // Given
        FeeRequest noLateDays = fee("GOLD", "0.00", 0, 30);
        FeeRequest zeroCycleDays = fee("00000000053", "GOLD", "0.00", 0, 0);
        List<String> sysprint = new ArrayList<>();

        // When
        calculator.calculate(noLateDays, sysprint);
        calculator.calculate(zeroCycleDays, sysprint);

        // Then
        assertEquals("0.00", noLateDays.feeAmt.toPlainString());
        assertEquals("0.00", noLateDays.avgDaily.toPlainString());
        assertEquals(0, noLateDays.rc);
        assertEquals("0.00", zeroCycleDays.feeAmt.toPlainString());
        assertEquals(8, zeroCycleDays.rc);
        assertEquals(List.of("CBFEECAL: ZERODIVIDE FOR ACCOUNT "
                + " ".repeat(15) + "00000000053"), sysprint);
    }

    @Test
    @DisplayName("BR-ONL-054 CBFEECAL SIZE and ERROR conditions (RC 8)")
    void brOnl054_sizeAndErrorConditions() {
        // Given
        FeeRequest size = new FeeRequest();
        size.acctId = "00000000054";
        size.groupId = "STANDARD";
        // Eleven integer digits overflow DECIMAL FIXED(12,2) after division by one.
        size.cycleBal = new BigDecimal("99999999999.99");
        size.lateDays = 10;
        size.cycleDays = 1;
        size.feeAmt = new BigDecimal("9.99");
        size.avgDaily = new BigDecimal("7.77");
        FeeRequest error = fee("00000000055", "STANDARD", "100.00", 10, 30);
        error.cycleBal = null;
        error.feeAmt = new BigDecimal("9.99");
        error.avgDaily = new BigDecimal("7.77");
        List<String> sysprint = new ArrayList<>();

        // When
        calculator.calculate(size, sysprint);
        calculator.calculate(error, sysprint);

        // Then
        assertEquals(8, size.rc);
        assertEquals("0.00", size.feeAmt.toPlainString());
        assertEquals("7.77", size.avgDaily.toPlainString());
        assertEquals(8, error.rc);
        assertEquals("0.00", error.feeAmt.toPlainString());
        assertEquals("7.77", error.avgDaily.toPlainString());
        assertEquals(List.of("CBFEECAL: SIZE CONDITION FOR ACCOUNT "
                        + " ".repeat(11) + "00000000054",
                "CBFEECAL: UNEXPECTED ERROR, ACCOUNT "
                        + " ".repeat(12) + "00000000055"), sysprint);
    }
}
