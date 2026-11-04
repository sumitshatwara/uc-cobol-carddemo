package com.carddemo.posting;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface ReportLookups {
    List<Map<String, Object>> transactions();

    Optional<BigDecimal> accountIdForCard(String cardNumber);

    Optional<String> transactionTypeDescription(String typeCode);

    Optional<String> transactionCategoryDescription(String typeCode, BigDecimal categoryCode);
}
