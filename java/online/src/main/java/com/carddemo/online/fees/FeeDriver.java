package com.carddemo.online.fees;

import com.carddemo.online.files.Account;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Service
public class FeeDriver {
    private final LateFeeCalculator calculator;

    public FeeDriver(LateFeeCalculator calculator) {
        this.calculator = calculator;
    }

    public String feeLine(Account account) {
        BigDecimal balance = account.currCycCredit().subtract(account.currCycDebit());
        FeeRequest request = new FeeRequest();
        request.acctId = account.acctId();
        request.groupId = account.groupId();
        request.cycleBal = balance.setScale(2);
        request.lateDays = (short) (balance.signum() > 0 ? 10 : 0);
        request.cycleDays = 30;
        calculator.calculate(request, new ArrayList<>());
        return fixed(account.acctId(), 11) + " " + fixed(account.groupId(), 10) + " "
                + formatPic(balance, 10) + " " + formatPic(request.feeAmt, 7) + " "
                + String.format("%02d", request.rc) + " ".repeat(28);
    }

    public List<String> run(List<Account> accounts) {
        return accounts.stream().map(this::feeLine).toList();
    }

    private static String formatPic(BigDecimal value, int suppressedDigits) {
        String number = value.setScale(2).toPlainString();
        boolean negative = number.startsWith("-");
        String digits = negative ? number.substring(1) : number;
        int width = suppressedDigits + 4;
        int padding = Math.max(0, width - digits.length() - (negative ? 1 : 0));
        return " ".repeat(padding) + (negative ? "-" : "") + digits;
    }

    private static String fixed(String value, int width) {
        String source = value == null ? "" : value;
        return source.length() >= width ? source.substring(0, width)
                : source + " ".repeat(width - source.length());
    }
}
