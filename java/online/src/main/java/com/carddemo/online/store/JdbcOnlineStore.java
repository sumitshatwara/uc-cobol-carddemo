package com.carddemo.online.store;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

/**
 * {@link OnlineStore} over schema {@code carddemo_online} (Flyway V400-V499).  The schema is
 * separate from {@code carddemo}, which every batch run truncates.  {@code READ UPDATE} is
 * {@code SELECT ... FOR UPDATE NOWAIT} (a row held by another session = the CICS lock failure);
 * the unit of work is the caller's Spring transaction.
 */
@Repository
public class JdbcOnlineStore implements OnlineStore {

    private static final String S = "carddemo_online.";
    private final JdbcTemplate jdbc;

    public JdbcOnlineStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<UserSecurity> USER = (rs, i) -> new UserSecurity(
            rs.getString("sec_usr_id"), rs.getString("sec_usr_fname"), rs.getString("sec_usr_lname"),
            rs.getString("sec_usr_pwd"), rs.getString("sec_usr_type"));

    private static final RowMapper<CardXref> XREF = (rs, i) -> new CardXref(
            rs.getString("xref_card_num"), rs.getLong("xref_cust_id"), rs.getLong("xref_acct_id"));

    private static final RowMapper<AccountRecord> ACCT = (rs, i) -> new AccountRecord(
            rs.getLong("acct_id"), rs.getString("acct_active_status"), rs.getBigDecimal("acct_curr_bal"),
            rs.getBigDecimal("acct_credit_limit"), rs.getBigDecimal("acct_cash_credit_limit"),
            rs.getString("acct_open_date"), rs.getString("acct_expiraion_date"), rs.getString("acct_reissue_date"),
            rs.getBigDecimal("acct_curr_cyc_credit"), rs.getBigDecimal("acct_curr_cyc_debit"),
            rs.getString("acct_addr_zip"), rs.getString("acct_group_id"));

    private static final RowMapper<CustomerRecord> CUST = JdbcOnlineStore::customer;

    private static CustomerRecord customer(ResultSet rs, int i) throws SQLException {
        return new CustomerRecord(rs.getLong("cust_id"), rs.getString("cust_first_name"),
                rs.getString("cust_middle_name"), rs.getString("cust_last_name"), rs.getString("cust_addr_line_1"),
                rs.getString("cust_addr_line_2"), rs.getString("cust_addr_line_3"), rs.getString("cust_addr_state_cd"),
                rs.getString("cust_addr_country_cd"), rs.getString("cust_addr_zip"), rs.getString("cust_phone_num_1"),
                rs.getString("cust_phone_num_2"), rs.getLong("cust_ssn"), rs.getString("cust_govt_issued_id"),
                rs.getString("cust_dob_yyyy_mm_dd"), rs.getString("cust_eft_account_id"),
                rs.getString("cust_pri_card_holder_ind"), rs.getInt("cust_fico_credit_score"));
    }

    private static Long key(String x) {
        try {
            return Long.parseLong(x.trim());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private <T> FileResult<T> one(String sql, RowMapper<T> m, Object key) {
        if (key == null) {
            return FileResult.notFound();
        }
        try {
            List<T> rows = jdbc.query(sql, m, key);
            return rows.isEmpty() ? FileResult.notFound() : FileResult.ok(rows.get(0));
        } catch (PessimisticLockingFailureException e) {
            return FileResult.error(Resp.IOERR, 0); // record held by another task
        } catch (DataAccessException e) {
            return FileResult.error(Resp.NOTOPEN, 0);
        }
    }

    @Override
    public FileResult<UserSecurity> readUser(String userId) {
        return one("SELECT * FROM " + S + "user_security WHERE sec_usr_id = ?", USER, userId);
    }

    @Override
    public FileResult<CardXref> readXrefByAcct(String acctIdX) {
        return one("SELECT * FROM " + S + "card_xref WHERE xref_acct_id = ? ORDER BY xref_card_num LIMIT 1",
                XREF, key(acctIdX));
    }

    @Override
    public FileResult<AccountRecord> readAccount(String acctIdX) {
        return one("SELECT * FROM " + S + "account WHERE acct_id = ?", ACCT, key(acctIdX));
    }

    @Override
    public FileResult<CustomerRecord> readCustomer(String custIdX) {
        return one("SELECT * FROM " + S + "customer WHERE cust_id = ?", CUST, key(custIdX));
    }

    @Override
    public FileResult<AccountRecord> readAccountForUpdate(String acctIdX) {
        return one("SELECT * FROM " + S + "account WHERE acct_id = ? FOR UPDATE NOWAIT", ACCT, key(acctIdX));
    }

    @Override
    public FileResult<CustomerRecord> readCustomerForUpdate(String custIdX) {
        return one("SELECT * FROM " + S + "customer WHERE cust_id = ? FOR UPDATE NOWAIT", CUST, key(custIdX));
    }

    private FileResult<Void> update(String sql, Object... args) {
        try {
            Object[] a = new Object[args.length];
            for (int i = 0; i < args.length; i++) {
                // LOW-VALUES (field not supplied) cannot be stored in PostgreSQL text: store blanks
                a[i] = args[i] instanceof String str ? str.replace('\0', ' ') : args[i];
            }
            return jdbc.update(sql, a) == 1 ? FileResult.ok(null) : FileResult.notFound();
        } catch (DataAccessException e) {
            return FileResult.error(Resp.IOERR, 0);
        }
    }

    @Override
    public FileResult<Void> rewriteAccount(AccountRecord a) {
        return update("UPDATE " + S + "account SET acct_active_status=?, acct_curr_bal=?, acct_credit_limit=?,"
                + " acct_cash_credit_limit=?, acct_open_date=?, acct_expiraion_date=?, acct_reissue_date=?,"
                + " acct_curr_cyc_credit=?, acct_curr_cyc_debit=?, acct_addr_zip=?, acct_group_id=? WHERE acct_id=?",
                a.activeStatus(), a.currBal(), a.creditLimit(), a.cashCreditLimit(), a.openDate(), a.expirationDate(),
                a.reissueDate(), a.currCycCredit(), a.currCycDebit(), a.addrZip(), a.groupId(), a.acctId());
    }

    @Override
    public FileResult<Void> rewriteCustomer(CustomerRecord c) {
        return update("UPDATE " + S + "customer SET cust_first_name=?, cust_middle_name=?, cust_last_name=?,"
                + " cust_addr_line_1=?, cust_addr_line_2=?, cust_addr_line_3=?, cust_addr_state_cd=?,"
                + " cust_addr_country_cd=?, cust_addr_zip=?, cust_phone_num_1=?, cust_phone_num_2=?, cust_ssn=?,"
                + " cust_govt_issued_id=?, cust_dob_yyyy_mm_dd=?, cust_eft_account_id=?,"
                + " cust_pri_card_holder_ind=?, cust_fico_credit_score=? WHERE cust_id=?",
                c.firstName(), c.middleName(), c.lastName(), c.addrLine1(), c.addrLine2(), c.addrLine3(),
                c.addrStateCd(), c.addrCountryCd(), c.addrZip(), c.phoneNum1(), c.phoneNum2(), c.ssn(),
                c.govtIssuedId(), c.dob(), c.eftAccountId(), c.priCardHolderInd(), c.ficoCreditScore(), c.custId());
    }

    @Override
    public void rollback() {
        TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
    }
}
