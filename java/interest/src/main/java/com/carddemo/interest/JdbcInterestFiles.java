package com.carddemo.interest;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import static com.carddemo.interest.InterestFiles.Account;
import static com.carddemo.interest.InterestFiles.CardXref;
import static com.carddemo.interest.InterestFiles.DisclosureGroup;
import static com.carddemo.interest.InterestFiles.ReadResult;
import static com.carddemo.interest.InterestFiles.TranCatBal;
import static com.carddemo.interest.InterestFiles.Transaction;

public final class JdbcInterestFiles implements InterestFiles {
    private static final String ACCOUNT_COLUMNS = """
            acct_id, acct_active_status, acct_curr_bal, acct_credit_limit, acct_cash_credit_limit,
            acct_open_date, acct_expiraion_date, acct_reissue_date, acct_curr_cyc_credit,
            acct_curr_cyc_debit, acct_addr_zip, acct_group_id
            """;

    private final JdbcTemplate jdbcTemplate;
    private List<TranCatBal> tranCatBalances;
    private int nextTranCatBalance;

    public JdbcInterestFiles(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public String openTcatBal() {
        return "00";
    }

    @Override
    public String openXref() {
        return "00";
    }

    @Override
    public String openDiscGrp() {
        return "00";
    }

    @Override
    public String openAccount() {
        return "00";
    }

    @Override
    public String openTransaction() {
        return "00";
    }

    @Override
    public ReadResult<TranCatBal> readNextTcatBal() {
        if (tranCatBalances == null) {
            tranCatBalances = jdbcTemplate.query("""
                    SELECT trancat_acct_id, trancat_type_cd, trancat_cd, tran_cat_bal
                    FROM carddemo.tran_cat_balance
                    ORDER BY trancat_acct_id, trancat_type_cd COLLATE "C", trancat_cd
                    """, (resultSet, row) -> new TranCatBal(
                    resultSet.getLong("trancat_acct_id"),
                    resultSet.getString("trancat_type_cd"),
                    resultSet.getInt("trancat_cd"),
                    resultSet.getBigDecimal("tran_cat_bal")));
        }
        return nextTranCatBalance < tranCatBalances.size()
                ? new ReadResult<>("00", tranCatBalances.get(nextTranCatBalance++))
                : new ReadResult<>("10", null);
    }

    @Override
    public ReadResult<Account> readAccount(long accountId) {
        List<Account> accounts = jdbcTemplate.query(
                "SELECT " + ACCOUNT_COLUMNS + " FROM carddemo.account WHERE acct_id = ?",
                (resultSet, row) -> account(resultSet),
                accountId);
        return accounts.isEmpty()
                ? new ReadResult<>("23", null)
                : new ReadResult<>("00", accounts.getFirst());
    }

    @Override
    public ReadResult<CardXref> readXrefByAccount(long accountId) {
        List<CardXref> xrefs = jdbcTemplate.query("""
                SELECT xref_card_num, xref_cust_id, xref_acct_id
                FROM carddemo.card_xref
                WHERE xref_acct_id = ?
                ORDER BY xref_card_num COLLATE "C"
                LIMIT 1
                """, (resultSet, row) -> new CardXref(
                resultSet.getString("xref_card_num"),
                resultSet.getLong("xref_cust_id"),
                resultSet.getLong("xref_acct_id")),
                accountId);
        return xrefs.isEmpty()
                ? new ReadResult<>("23", null)
                : new ReadResult<>("00", xrefs.getFirst());
    }

    @Override
    public ReadResult<DisclosureGroup> readDiscGrp(String groupId, String typeCd, int categoryCd) {
        List<DisclosureGroup> groups = jdbcTemplate.query("""
                SELECT dis_acct_group_id, dis_tran_type_cd, dis_tran_cat_cd, dis_int_rate
                FROM carddemo.disclosure_group
                WHERE RTRIM(dis_acct_group_id) = RTRIM(?)
                  AND RTRIM(dis_tran_type_cd) = RTRIM(?)
                  AND dis_tran_cat_cd = ?
                """, (resultSet, row) -> new DisclosureGroup(
                resultSet.getString("dis_acct_group_id"),
                resultSet.getString("dis_tran_type_cd"),
                resultSet.getInt("dis_tran_cat_cd"),
                resultSet.getBigDecimal("dis_int_rate")),
                groupId, typeCd, categoryCd);
        return groups.isEmpty()
                ? new ReadResult<>("23", null)
                : new ReadResult<>("00", groups.getFirst());
    }

    @Override
    public String rewriteAccount(Account account) {
        return jdbcTemplate.update("""
                UPDATE carddemo.account
                SET acct_active_status = ?, acct_curr_bal = ?, acct_credit_limit = ?,
                    acct_cash_credit_limit = ?, acct_open_date = ?, acct_expiraion_date = ?,
                    acct_reissue_date = ?, acct_curr_cyc_credit = ?, acct_curr_cyc_debit = ?,
                    acct_addr_zip = ?, acct_group_id = ?
                WHERE acct_id = ?
                """,
                account.activeStatus(), account.currentBalance(), account.creditLimit(),
                account.cashCreditLimit(), account.openDate(), account.expirationDate(),
                account.reissueDate(), account.currentCycleCredit(), account.currentCycleDebit(),
                account.zip(), account.groupId(), account.accountId()) == 1 ? "00" : "23";
    }

    @Override
    public String writeTransaction(Transaction transaction) {
        return "00";
    }

    @Override
    public String closeTcatBal() {
        return "00";
    }

    @Override
    public String closeXref() {
        return "00";
    }

    @Override
    public String closeDiscGrp() {
        return "00";
    }

    @Override
    public String closeAccount() {
        return "00";
    }

    @Override
    public String closeTransaction() {
        return "00";
    }

    public List<Account> accountsInOrder() {
        return jdbcTemplate.query("SELECT " + ACCOUNT_COLUMNS + " FROM carddemo.account ORDER BY acct_id",
                (resultSet, row) -> account(resultSet));
    }

    private static Account account(ResultSet resultSet) throws SQLException {
        return new Account(
                resultSet.getLong("acct_id"),
                resultSet.getString("acct_active_status"),
                resultSet.getBigDecimal("acct_curr_bal"),
                resultSet.getBigDecimal("acct_credit_limit"),
                resultSet.getBigDecimal("acct_cash_credit_limit"),
                resultSet.getString("acct_open_date"),
                resultSet.getString("acct_expiraion_date"),
                resultSet.getString("acct_reissue_date"),
                resultSet.getBigDecimal("acct_curr_cyc_credit"),
                resultSet.getBigDecimal("acct_curr_cyc_debit"),
                resultSet.getString("acct_addr_zip"),
                resultSet.getString("acct_group_id"));
    }
}
