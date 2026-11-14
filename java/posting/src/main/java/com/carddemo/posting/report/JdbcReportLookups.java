package com.carddemo.posting.report;

import com.carddemo.posting.Cobol;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;

/** {@link ReportLookups} over carddemo.card_xref, tran_type and tran_category. */
public class JdbcReportLookups implements ReportLookups {

    private final JdbcTemplate jdbc;

    public JdbcReportLookups(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Long> xrefAcctId(String cardNum) {
        return jdbc.queryForList("SELECT xref_acct_id FROM carddemo.card_xref WHERE xref_card_num = ?", Long.class,
                Cobol.rstrip(cardNum)).stream().findFirst();
    }

    @Override
    public Optional<String> tranTypeDesc(String typeCd) {
        return jdbc.queryForList("SELECT tran_type_desc FROM carddemo.tran_type WHERE tran_type = ?", String.class,
                Cobol.rstrip(typeCd)).stream().findFirst();
    }

    @Override
    public Optional<String> tranCatDesc(String typeCd, long catCd) {
        return jdbc.queryForList("SELECT tran_cat_type_desc FROM carddemo.tran_category WHERE tran_type_cd = ? AND tran_cat_cd = ?",
                String.class, Cobol.rstrip(typeCd), catCd).stream().findFirst();
    }
}
