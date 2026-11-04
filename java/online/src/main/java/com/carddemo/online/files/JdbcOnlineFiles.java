package com.carddemo.online.files;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.NoTransactionException;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

@Component
public class JdbcOnlineFiles implements OnlineFiles {
    private final JdbcTemplate jdbc;

    public JdbcOnlineFiles(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<UserSecurity> USER = (rs, row) -> new UserSecurity(
            trim(rs.getString("sec_usr_id")), trim(rs.getString("sec_usr_fname")),
            trim(rs.getString("sec_usr_lname")), trim(rs.getString("sec_usr_pwd")),
            trim(rs.getString("sec_usr_type")));
    private static final RowMapper<CardXref> XREF = (rs, row) -> new CardXref(
            trim(rs.getString("xref_card_num")), digits(rs.getLong("xref_cust_id"), 9),
            digits(rs.getLong("xref_acct_id"), 11));
    private static final RowMapper<Account> ACCOUNT = (rs, row) -> account(rs);
    private static final RowMapper<Customer> CUSTOMER = (rs, row) -> customer(rs);

    @Override
    public FileResult<UserSecurity> readUserSec(String userId) {
        return first("SELECT sec_usr_id, sec_usr_fname, sec_usr_lname, sec_usr_pwd, sec_usr_type "
                + "FROM carddemo.user_security WHERE sec_usr_id = ?", USER, text(userId, 8));
    }

    @Override
    public FileResult<CardXref> readXrefByAcct(String acctId) {
        return first("SELECT xref_card_num, xref_cust_id, xref_acct_id FROM carddemo.card_xref "
                + "WHERE xref_acct_id = ? ORDER BY xref_card_num LIMIT 1", XREF, number(acctId));
    }

    @Override
    public FileResult<Account> readAccount(String acctId) {
        return first(accountSql(false), ACCOUNT, number(acctId));
    }

    @Override
    public FileResult<Customer> readCustomer(String custId) {
        return first(customerSql(false), CUSTOMER, number(custId));
    }

    @Override
    public FileResult<Account> readAccountForUpdate(String acctId) {
        return first(accountSql(true), ACCOUNT, number(acctId));
    }

    @Override
    public FileResult<Customer> readCustomerForUpdate(String custId) {
        return first(customerSql(true), CUSTOMER, number(custId));
    }

    @Override
    public FileResult<Void> rewriteAccount(Account account) {
        try {
            int count = jdbc.update("UPDATE carddemo.account SET acct_active_status=?, acct_curr_bal=?, "
                            + "acct_credit_limit=?, acct_cash_credit_limit=?, acct_open_date=?, "
                            + "acct_expiraion_date=?, acct_reissue_date=?, acct_curr_cyc_credit=?, "
                            + "acct_curr_cyc_debit=?, acct_addr_zip=?, acct_group_id=? WHERE acct_id=?",
                    account.activeStatus(), account.currBal(), account.creditLimit(),
                    account.cashCreditLimit(), account.openDate(), account.expirationDate(),
                    account.reissueDate(), account.currCycCredit(), account.currCycDebit(),
                    account.addrZip(), account.groupId(), number(account.acctId()));
            return count == 0 ? new FileResult<>(13, 0, null) : new FileResult<>(0, 0, null);
        } catch (DataAccessException e) {
            return new FileResult<>(12, 0, null);
        }
    }

    @Override
    public FileResult<Void> rewriteCustomer(Customer customer) {
        try {
            int count = jdbc.update("UPDATE carddemo.customer SET cust_first_name=?, cust_middle_name=?, "
                            + "cust_last_name=?, cust_addr_line_1=?, cust_addr_line_2=?, cust_addr_line_3=?, "
                            + "cust_addr_state_cd=?, cust_addr_country_cd=?, cust_addr_zip=?, "
                            + "cust_phone_num_1=?, cust_phone_num_2=?, cust_ssn=?, cust_govt_issued_id=?, "
                            + "cust_dob_yyyy_mm_dd=?, cust_eft_account_id=?, cust_pri_card_holder_ind=?, "
                            + "cust_fico_credit_score=? WHERE cust_id=?",
                    customer.firstName(), customer.middleName(), customer.lastName(), customer.addrLine1(),
                    customer.addrLine2(), customer.addrLine3(), customer.stateCd(), customer.countryCd(),
                    customer.zip(), customer.phone1(), customer.phone2(), number(customer.ssn()),
                    customer.govtId(), customer.dob(), customer.eftAccountId(), customer.priCardHolder(),
                    customer.ficoScore(), number(customer.custId()));
            return count == 0 ? new FileResult<>(13, 0, null) : new FileResult<>(0, 0, null);
        } catch (DataAccessException e) {
            return new FileResult<>(12, 0, null);
        }
    }

    @Override
    public void syncpointRollback() {
        try {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
        } catch (NoTransactionException ignored) {
        }
    }

    private <T> FileResult<T> first(String sql, RowMapper<T> mapper, Object key) {
        try {
            List<T> rows = jdbc.query(sql, mapper, key);
            return rows.isEmpty() ? new FileResult<>(13, 0, null)
                    : new FileResult<>(0, 0, rows.get(0));
        } catch (DataAccessException e) {
            return new FileResult<>(12, 0, null);
        }
    }

    private static String accountSql(boolean lock) {
        return "SELECT acct_id, acct_active_status, acct_curr_bal, acct_credit_limit, "
                + "acct_cash_credit_limit, acct_open_date, acct_expiraion_date, acct_reissue_date, "
                + "acct_curr_cyc_credit, acct_curr_cyc_debit, acct_addr_zip, acct_group_id "
                + "FROM carddemo.account WHERE acct_id = ?" + (lock ? " FOR UPDATE NOWAIT" : "");
    }

    private static String customerSql(boolean lock) {
        return "SELECT cust_id, cust_first_name, cust_middle_name, cust_last_name, cust_addr_line_1, "
                + "cust_addr_line_2, cust_addr_line_3, cust_addr_state_cd, cust_addr_country_cd, "
                + "cust_addr_zip, cust_phone_num_1, cust_phone_num_2, cust_ssn, cust_govt_issued_id, "
                + "cust_dob_yyyy_mm_dd, cust_eft_account_id, cust_pri_card_holder_ind, "
                + "cust_fico_credit_score FROM carddemo.customer WHERE cust_id = ?"
                + (lock ? " FOR UPDATE NOWAIT" : "");
    }

    private static Account account(ResultSet rs) throws SQLException {
        return new Account(digits(rs.getLong("acct_id"), 11), trim(rs.getString("acct_active_status")),
                rs.getBigDecimal("acct_curr_bal"), rs.getBigDecimal("acct_credit_limit"),
                rs.getBigDecimal("acct_cash_credit_limit"), trim(rs.getString("acct_open_date")),
                trim(rs.getString("acct_expiraion_date")), trim(rs.getString("acct_reissue_date")),
                rs.getBigDecimal("acct_curr_cyc_credit"), rs.getBigDecimal("acct_curr_cyc_debit"),
                trim(rs.getString("acct_addr_zip")), trim(rs.getString("acct_group_id")));
    }

    private static Customer customer(ResultSet rs) throws SQLException {
        return new Customer(digits(rs.getLong("cust_id"), 9), trim(rs.getString("cust_first_name")),
                trim(rs.getString("cust_middle_name")), trim(rs.getString("cust_last_name")),
                trim(rs.getString("cust_addr_line_1")), trim(rs.getString("cust_addr_line_2")),
                trim(rs.getString("cust_addr_line_3")), trim(rs.getString("cust_addr_state_cd")),
                trim(rs.getString("cust_addr_country_cd")), trim(rs.getString("cust_addr_zip")),
                trim(rs.getString("cust_phone_num_1")), trim(rs.getString("cust_phone_num_2")),
                digits(rs.getLong("cust_ssn"), 9), trim(rs.getString("cust_govt_issued_id")),
                trim(rs.getString("cust_dob_yyyy_mm_dd")), trim(rs.getString("cust_eft_account_id")),
                trim(rs.getString("cust_pri_card_holder_ind")), rs.getInt("cust_fico_credit_score"));
    }

    private static String text(String value, int width) {
        String source = value == null ? "" : value;
        return source.length() >= width ? source.substring(0, width) : source + " ".repeat(width - source.length());
    }

    private static String trim(String value) {
        return value == null ? "" : value.replaceFirst("\\s+$", "");
    }

    private static String digits(long value, int width) {
        return String.format("%0" + width + "d", value);
    }

    private static long number(String value) {
        return Long.parseLong(value.trim());
    }
}
