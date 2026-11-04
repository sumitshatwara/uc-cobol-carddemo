package com.carddemo.interest;

import java.util.List;

public record InterestRunOutcome(
        List<String> sysoutLines,
        int stepRc,
        boolean abended,
        List<InterestFiles.Transaction> transactions) {
    public InterestRunOutcome {
        sysoutLines = List.copyOf(sysoutLines);
        transactions = List.copyOf(transactions);
    }
}
