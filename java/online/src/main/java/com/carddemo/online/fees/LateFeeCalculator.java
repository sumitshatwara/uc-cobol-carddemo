package com.carddemo.online.fees;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Converts CBFEECAL's PL/I arithmetic and ON-unit output for the off-host runtime.
 * List-directed output uses tab stops at columns 1, 25, 49, and 73; the account
 * identifier remains an untrimmed CHAR(11) item. This rendering is a proposed
 * off-host interpretation of PL/I list-directed output.
 */
@Service
public class LateFeeCalculator {
    private static final BigDecimal HUNDRED = new BigDecimal("100.00");
    private static final BigDecimal MINIMUM = new BigDecimal("5.00");
    private static final BigDecimal MAXIMUM = new BigDecimal("35.00");

    public void calculate(FeeRequest request, List<String> sysprint) {
        try {
            request.rc = 0;
            request.feeAmt = new BigDecimal("0.00");
            if (request.cycleDays == 0) {
                throw new ZeroDivideCondition();
            }
            BigDecimal average = assign(request.cycleBal.divide(BigDecimal.valueOf(request.cycleDays), 2,
                    RoundingMode.DOWN), 12, 2);
            request.avgDaily = average;
            if (request.lateDays <= 0) {
                return;
            }
            if (fixed(request.groupId, 10).equals("GOLD      ")
                    && request.cycleBal.compareTo(HUNDRED) < 0) {
                request.rc = 4;
                return;
            }
            BigDecimal work = assign(request.cycleBal.multiply(new BigDecimal("2.500"))
                    .divide(HUNDRED, 5, RoundingMode.DOWN), 15, 5);
            BigDecimal fee = assign(work.setScale(2, RoundingMode.DOWN), 9, 2);
            request.feeAmt = fee.max(MINIMUM).min(MAXIMUM);
        } catch (ZeroDivideCondition e) {
            request.rc = 8;
            request.feeAmt = new BigDecimal("0.00");
            request.avgDaily = new BigDecimal("0.00");
            if (sysprint != null) {
                sysprint.add(listDirected("CBFEECAL: ZERODIVIDE FOR ACCOUNT ", request.acctId));
            }
        } catch (SizeCondition e) {
            request.rc = 8;
            request.feeAmt = new BigDecimal("0.00");
            if (sysprint != null) {
                sysprint.add(listDirected("CBFEECAL: SIZE CONDITION FOR ACCOUNT ", request.acctId));
            }
        } catch (RuntimeException e) {
            request.rc = 8;
            if (sysprint != null) {
                sysprint.add(listDirected("CBFEECAL: UNEXPECTED ERROR, ACCOUNT ", request.acctId));
            }
        }
    }

    private static BigDecimal assign(BigDecimal value, int precision, int scale) {
        BigDecimal result = value.setScale(scale, RoundingMode.DOWN);
        if (result.precision() > precision) {
            throw new SizeCondition();
        }
        return result;
    }

    private static String listDirected(String... items) {
        StringBuilder line = new StringBuilder();
        for (int index = 0; index < items.length; index++) {
            if (index > 0) {
                int nextTabStop = ((line.length() + 23) / 24) * 24;
                line.append(" ".repeat(nextTabStop - line.length()));
            }
            line.append(items[index]);
        }
        return line.toString();
    }

    private static String fixed(String value, int width) {
        String source = value == null ? "" : value;
        return source.length() >= width ? source.substring(0, width)
                : source + " ".repeat(width - source.length());
    }

    private static class SizeCondition extends RuntimeException {
    }

    private static class ZeroDivideCondition extends RuntimeException {
    }
}
