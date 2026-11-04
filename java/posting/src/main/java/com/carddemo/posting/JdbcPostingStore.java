package com.carddemo.posting;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

@Repository
public class JdbcPostingStore implements PostingStore {
    private final JdbcTemplate jdbcTemplate;

    public JdbcPostingStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<BigDecimal> accountIdForCard(String cardNumber) {
        return jdbcTemplate.query("SELECT xref_acct_id FROM carddemo.card_xref WHERE xref_card_num = ?",
                (resultSet, rowNum) -> resultSet.getBigDecimal("xref_acct_id"), cardNumber).stream().findFirst();
    }

    @Override
    public Optional<Account> account(BigDecimal accountId) {
        return jdbcTemplate.query("""
                        SELECT acct_id, acct_active_status, acct_curr_bal, acct_credit_limit,
                               acct_cash_credit_limit, acct_expiraion_date,
                               acct_curr_cyc_credit, acct_curr_cyc_debit
                          FROM carddemo.account WHERE acct_id = ?
                        """,
                (resultSet, rowNum) -> new Account(
                        resultSet.getBigDecimal("acct_id"),
                        resultSet.getString("acct_active_status"),
                        resultSet.getBigDecimal("acct_curr_bal"),
                        resultSet.getBigDecimal("acct_credit_limit"),
                        resultSet.getBigDecimal("acct_cash_credit_limit"),
                        resultSet.getString("acct_expiraion_date"),
                        resultSet.getBigDecimal("acct_curr_cyc_credit"),
                        resultSet.getBigDecimal("acct_curr_cyc_debit")), accountId).stream().findFirst();
    }

    @Override
    public Optional<BigDecimal> categoryBalance(BigDecimal accountId, String typeCode, BigDecimal categoryCode) {
        return jdbcTemplate.query("""
                        SELECT tran_cat_bal FROM carddemo.tran_cat_balance
                         WHERE trancat_acct_id = ? AND trancat_type_cd = ? AND trancat_cd = ?
                        """,
                (resultSet, rowNum) -> resultSet.getBigDecimal("tran_cat_bal"),
                accountId, typeCode, categoryCode).stream().findFirst();
    }

    @Override
    public void createCategoryBalance(BigDecimal accountId, String typeCode, BigDecimal categoryCode,
                                      BigDecimal balance) {
        jdbcTemplate.update("""
                        INSERT INTO carddemo.tran_cat_balance
                            (trancat_acct_id, trancat_type_cd, trancat_cd, tran_cat_bal)
                        VALUES (?, ?, ?, ?)
                        """, accountId, typeCode, categoryCode, balance);
    }

    @Override
    public int updateCategoryBalance(BigDecimal accountId, String typeCode, BigDecimal categoryCode,
                                     BigDecimal balance) {
        return jdbcTemplate.update("""
                        UPDATE carddemo.tran_cat_balance SET tran_cat_bal = ?
                         WHERE trancat_acct_id = ? AND trancat_type_cd = ? AND trancat_cd = ?
                        """, balance, accountId, typeCode, categoryCode);
    }

    @Override
    public int updateAccount(Account account) {
        return jdbcTemplate.update("""
                        UPDATE carddemo.account
                           SET acct_curr_bal = ?, acct_curr_cyc_credit = ?, acct_curr_cyc_debit = ?
                         WHERE acct_id = ?
                        """, account.currentBalance(), account.cycleCredit(), account.cycleDebit(), account.id());
    }

    @Override
    public void insertTransaction(Map<String, Object> transaction) {
        try {
            jdbcTemplate.update("""
                            INSERT INTO carddemo."transaction"
                                (tran_id, tran_type_cd, tran_cat_cd, tran_source, tran_desc, tran_amt,
                                 tran_merchant_id, tran_merchant_name, tran_merchant_city, tran_merchant_zip,
                                 tran_card_num, tran_orig_ts, tran_proc_ts)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """,
                    transaction.get("TRAN-ID"), transaction.get("TRAN-TYPE-CD"), transaction.get("TRAN-CAT-CD"),
                    transaction.get("TRAN-SOURCE"), transaction.get("TRAN-DESC"), transaction.get("TRAN-AMT"),
                    transaction.get("TRAN-MERCHANT-ID"), transaction.get("TRAN-MERCHANT-NAME"),
                    transaction.get("TRAN-MERCHANT-CITY"), transaction.get("TRAN-MERCHANT-ZIP"),
                    transaction.get("TRAN-CARD-NUM"), transaction.get("TRAN-ORIG-TS"),
                    transaction.get("TRAN-PROC-TS"));
        } catch (DuplicateKeyException exception) {
            throw new DuplicateTransactionException(exception);
        }
    }

    @Override
    public void truncateTransactions() {
        jdbcTemplate.execute("TRUNCATE TABLE carddemo.\"transaction\"");
    }
}
