package com.carddemo.posting.post;

import com.carddemo.posting.Cobol;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;

/** {@link PostingStore} over the foundation's carddemo tables (card_xref, account, tran_cat_balance, transact). */
public class JdbcPostingStore implements PostingStore {

    private final JdbcTemplate jdbc;

    public JdbcPostingStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void openTransactOutput() {
        jdbc.update("DELETE FROM carddemo.transact");
    }

    @Override
    public Optional<Long> findXrefAcctId(String cardNum) {
        List<Long> r = jdbc.queryForList("SELECT xref_acct_id FROM carddemo.card_xref WHERE xref_card_num = ?", Long.class,
                Cobol.rstrip(cardNum));
        return r.stream().findFirst();
    }

    @Override
    public Optional<Account> findAccount(long acctId) {
        List<Account> r = jdbc.query("SELECT acct_id, acct_curr_bal, acct_credit_limit, acct_curr_cyc_credit, acct_curr_cyc_debit, "
                + "acct_expiraion_date FROM carddemo.account WHERE acct_id = ?", (rs, i) -> new Account(rs.getLong(1),
                rs.getBigDecimal(2), rs.getBigDecimal(3), rs.getBigDecimal(4), rs.getBigDecimal(5), rs.getString(6)), acctId);
        return r.stream().findFirst();
    }

    @Override
    public boolean rewriteAccount(Account a) {
        return jdbc.update("UPDATE carddemo.account SET acct_curr_bal = ?, acct_curr_cyc_credit = ?, acct_curr_cyc_debit = ? "
                + "WHERE acct_id = ?", a.currBal, a.cycCredit, a.cycDebit, a.acctId) == 1;
    }

    @Override
    public Optional<BigDecimal> findTcatBal(TcatKey k) {
        List<BigDecimal> r = jdbc.queryForList("SELECT tran_cat_bal FROM carddemo.tran_cat_balance WHERE trancat_acct_id = ? "
                + "AND trancat_type_cd = ? AND trancat_cd = ?", BigDecimal.class, k.acctId(), k.typeCd(), k.catCd());
        return r.stream().findFirst();
    }

    @Override
    public boolean writeTcatBal(TcatKey k, BigDecimal balance) {
        return jdbc.update("INSERT INTO carddemo.tran_cat_balance (trancat_acct_id, trancat_type_cd, trancat_cd, tran_cat_bal) "
                + "VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING", k.acctId(), k.typeCd(), k.catCd(), balance) == 1;
    }

    @Override
    public boolean rewriteTcatBal(TcatKey k, BigDecimal balance) {
        return jdbc.update("UPDATE carddemo.tran_cat_balance SET tran_cat_bal = ? WHERE trancat_acct_id = ? AND trancat_type_cd = ? "
                + "AND trancat_cd = ?", balance, k.acctId(), k.typeCd(), k.catCd()) == 1;
    }

    @Override
    public boolean writeTransaction(PostedTransaction t) {
        return jdbc.update("INSERT INTO carddemo.transact (tran_id, tran_type_cd, tran_cat_cd, tran_source, tran_desc, tran_amt, "
                + "tran_merchant_id, tran_merchant_name, tran_merchant_city, tran_merchant_zip, tran_card_num, tran_orig_ts, tran_proc_ts) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING", t.tranId(), t.typeCd(), t.catCd(), t.source(),
                t.desc(), t.amt(), t.merchantId(), t.merchantName(), t.merchantCity(), t.merchantZip(), t.cardNum(), t.origTs(),
                t.procTs()) == 1;
    }
}
