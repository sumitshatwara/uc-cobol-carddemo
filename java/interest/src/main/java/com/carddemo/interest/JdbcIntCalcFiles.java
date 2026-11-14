package com.carddemo.interest;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@link IntCalcFiles} over the PostgreSQL tables that stand in for the VSAM clusters
 * ({@code carddemo.tran_cat_balance}, {@code account}, {@code card_xref}, {@code disclosure_group}).
 * Account rewrites are applied immediately (one UPDATE per REWRITE, BR-INT-048: no rollback of
 * earlier rewrites when a later record abends); SYSTRAN records are collected in write order and
 * written as the new generation by {@link InterestChainHandler}.
 */
public class JdbcIntCalcFiles implements IntCalcFiles {

    private final JdbcTemplate jdbc;
    private final List<InterestTran> transactions = new ArrayList<>();
    private Iterator<TcatBal> balances;

    public JdbcIntCalcFiles(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String open(Dd dd) {
        if (dd == Dd.TCATBALF) {
            // ACCESS MODE SEQUENTIAL on a KSDS: key order (COLLATE "C" keys, so ORDER BY = VSAM order)
            balances = jdbc.query("SELECT trancat_acct_id, trancat_type_cd, trancat_cd, tran_cat_bal FROM carddemo.tran_cat_balance"
                    + " ORDER BY trancat_acct_id, trancat_type_cd, trancat_cd",
                    (rs, i) -> new TcatBal(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getBigDecimal(4))).iterator();
        }
        if (dd == Dd.TRANSACT) {
            transactions.clear();
        }
        return "00";
    }

    @Override
    public String close(Dd dd) {
        return "00";
    }

    @Override
    public Io<TcatBal> readNextBalance() {
        if (balances == null) {
            return Io.status("47"); // read on a file not open for input
        }
        return balances.hasNext() ? Io.ok(balances.next()) : Io.status("10");
    }

    @Override
    public Io<AccountRecord> readAccount(long acctId) {
        List<AccountRecord> rows = jdbc.query("SELECT acct_id, acct_group_id, acct_curr_bal, acct_curr_cyc_credit, acct_curr_cyc_debit"
                + " FROM carddemo.account WHERE acct_id = ?",
                (rs, i) -> new AccountRecord(rs.getLong(1), rs.getString(2), rs.getBigDecimal(3), rs.getBigDecimal(4), rs.getBigDecimal(5)),
                acctId);
        return rows.isEmpty() ? Io.status("23") : Io.ok(rows.get(0));
    }

    @Override
    public String rewriteAccount(AccountRecord a) {
        int n = jdbc.update("UPDATE carddemo.account SET acct_curr_bal = ?, acct_curr_cyc_credit = ?, acct_curr_cyc_debit = ? WHERE acct_id = ?",
                a.currBal(), a.currCycCredit(), a.currCycDebit(), a.acctId());
        return n == 1 ? "00" : "23";
    }

    @Override
    public Io<Xref> readXrefByAcct(long acctId) {
        // alternate-index path: first base key (card number) for the account id
        List<Xref> rows = jdbc.query("SELECT xref_card_num, xref_cust_id, xref_acct_id FROM carddemo.card_xref"
                + " WHERE xref_acct_id = ? ORDER BY xref_card_num LIMIT 1",
                (rs, i) -> new Xref(rs.getString(1), rs.getLong(2), rs.getLong(3)), acctId);
        return rows.isEmpty() ? Io.status("23") : Io.ok(rows.get(0));
    }

    @Override
    public Io<BigDecimal> readRate(String groupId, String typeCd, long catCd) {
        List<BigDecimal> rows = jdbc.query("SELECT dis_int_rate FROM carddemo.disclosure_group"
                + " WHERE dis_acct_group_id = ? AND dis_tran_type_cd = ? AND dis_tran_cat_cd = ?",
                (rs, i) -> rs.getBigDecimal(1), rstrip(groupId), rstrip(typeCd), catCd);
        return rows.isEmpty() ? Io.status("23") : Io.ok(rows.get(0));
    }

    @Override
    public String writeTran(InterestTran tran) {
        transactions.add(tran);
        return "00";
    }

    /** SYSTRAN records in write order. */
    public List<InterestTran> transactions() {
        return List.copyOf(transactions);
    }

    private static String rstrip(String s) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == ' ') {
            end--;
        }
        return s.substring(0, end);
    }
}
