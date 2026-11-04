package com.carddemo.posting;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class JdbcReportLookups implements ReportLookups {
    private final JdbcTemplate jdbcTemplate;

    public JdbcReportLookups(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<Map<String, Object>> transactions() {
        return jdbcTemplate.query("""
                        SELECT tran_id, tran_type_cd, tran_cat_cd, tran_source, tran_desc, tran_amt,
                               tran_merchant_id, tran_merchant_name, tran_merchant_city, tran_merchant_zip,
                               tran_card_num, tran_orig_ts, tran_proc_ts
                          FROM carddemo."transaction"
                         ORDER BY tran_id COLLATE "C"
                        """, (resultSet, rowNum) -> {
            Map<String, Object> transaction = new LinkedHashMap<>();
            transaction.put("TRAN-ID", resultSet.getString("tran_id"));
            transaction.put("TRAN-TYPE-CD", resultSet.getString("tran_type_cd"));
            transaction.put("TRAN-CAT-CD", resultSet.getBigDecimal("tran_cat_cd"));
            transaction.put("TRAN-SOURCE", resultSet.getString("tran_source"));
            transaction.put("TRAN-DESC", resultSet.getString("tran_desc"));
            transaction.put("TRAN-AMT", resultSet.getBigDecimal("tran_amt"));
            transaction.put("TRAN-MERCHANT-ID", resultSet.getBigDecimal("tran_merchant_id"));
            transaction.put("TRAN-MERCHANT-NAME", resultSet.getString("tran_merchant_name"));
            transaction.put("TRAN-MERCHANT-CITY", resultSet.getString("tran_merchant_city"));
            transaction.put("TRAN-MERCHANT-ZIP", resultSet.getString("tran_merchant_zip"));
            transaction.put("TRAN-CARD-NUM", resultSet.getString("tran_card_num"));
            transaction.put("TRAN-ORIG-TS", resultSet.getString("tran_orig_ts"));
            transaction.put("TRAN-PROC-TS", resultSet.getString("tran_proc_ts"));
            return transaction;
        });
    }

    @Override
    public Optional<BigDecimal> accountIdForCard(String cardNumber) {
        return jdbcTemplate.query("SELECT xref_acct_id FROM carddemo.card_xref WHERE xref_card_num = ?",
                (resultSet, rowNum) -> resultSet.getBigDecimal("xref_acct_id"), cardNumber).stream().findFirst();
    }

    @Override
    public Optional<String> transactionTypeDescription(String typeCode) {
        return jdbcTemplate.query("SELECT tran_type_desc FROM carddemo.tran_type WHERE tran_type = ?",
                (resultSet, rowNum) -> resultSet.getString("tran_type_desc"), typeCode).stream().findFirst();
    }

    @Override
    public Optional<String> transactionCategoryDescription(String typeCode, BigDecimal categoryCode) {
        return jdbcTemplate.query("""
                        SELECT tran_cat_type_desc FROM carddemo.tran_category
                         WHERE tran_type_cd = ? AND tran_cat_cd = ?
                        """, (resultSet, rowNum) -> resultSet.getString("tran_cat_type_desc"),
                typeCode, categoryCode).stream().findFirst();
    }
}
