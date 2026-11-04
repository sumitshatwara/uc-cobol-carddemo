package com.carddemo.online;

import com.carddemo.online.fees.FeeDriver;
import com.carddemo.online.fees.FeeRequest;
import com.carddemo.online.fees.LateFeeCalculator;
import com.carddemo.online.files.Account;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FeeDriverTest extends OnlineTestSupport {
    @Test
    @DisplayName("BR-ONL-055 CBFEEDRV driver: fee request and FEEOUT line")
    void brOnl055_feeRequestAndOutput() {
        // Given
        AtomicReference<FeeRequest> captured = new AtomicReference<>();
        LateFeeCalculator calculator = new LateFeeCalculator() {
            @Override
            public void calculate(FeeRequest request, List<String> sysprint) {
                captured.set(request);
                super.calculate(request, sysprint);
            }
        };
        FeeDriver driver = new FeeDriver(calculator);

        // When
        String positiveLine = driver.feeLine(files.account);

        // Then
        assertEquals("00000000001 STANDARD          1234.56       30.86 00"
                + " ".repeat(28), positiveLine);
        assertEquals(80, positiveLine.length());
        assertEquals("1234.56", captured.get().cycleBal.toPlainString());
        assertEquals(10, captured.get().lateDays);
        assertEquals(30, captured.get().cycleDays);
        assertEquals("30.86", captured.get().feeAmt.toPlainString());
        assertEquals(0, captured.get().rc);

        // Given
        Account debitExceedsCredit = new Account(files.account.acctId(), files.account.activeStatus(),
                files.account.currBal(), files.account.creditLimit(), files.account.cashCreditLimit(),
                files.account.openDate(), files.account.expirationDate(), files.account.reissueDate(),
                new BigDecimal("100.00"), new BigDecimal("265.44"), files.account.addrZip(),
                files.account.groupId());

        // When
        String negativeLine = driver.feeLine(debitExceedsCredit);

        // Then
        assertEquals("00000000001 STANDARD          -165.44        0.00 00"
                + " ".repeat(28), negativeLine);
        assertEquals(80, negativeLine.length());
        assertEquals("-165.44", captured.get().cycleBal.toPlainString());
        assertEquals(0, captured.get().lateDays);
        assertEquals("0.00", captured.get().feeAmt.toPlainString());
        assertEquals(0, captured.get().rc);
    }
}
