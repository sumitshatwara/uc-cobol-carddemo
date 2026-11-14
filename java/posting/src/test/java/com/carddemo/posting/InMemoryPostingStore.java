package com.carddemo.posting;

import com.carddemo.common.TestRepo;
import com.carddemo.common.codec.Copybook;
import com.carddemo.common.codec.RecordCodec;
import com.carddemo.common.fixture.FixtureLoader;
import com.carddemo.posting.post.Account;
import com.carddemo.posting.post.PostedTransaction;
import com.carddemo.posting.post.PostingStore;
import com.carddemo.posting.post.TcatKey;
import com.carddemo.posting.report.ReportLookups;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** In-memory VSAM files for unit tests: no database. Also serves the CBTRN03C lookups. */
public class InMemoryPostingStore implements PostingStore, ReportLookups {

    public final Map<String, Long> xref = new LinkedHashMap<>();
    public final Map<Long, Account> accounts = new TreeMap<>();
    public final Map<TcatKey, BigDecimal> tcat = new LinkedHashMap<>();
    public final TreeMap<String, PostedTransaction> transact = new TreeMap<>();
    public final Map<String, String> types = new LinkedHashMap<>();
    public final Map<String, String> cats = new LinkedHashMap<>();
    public final List<String> calls = new ArrayList<>();
    public boolean failAccountRewrite;

    public InMemoryPostingStore card(String card, long acct) {
        xref.put(card, acct);
        return this;
    }

    public InMemoryPostingStore account(long id, String limit, String cycCredit, String cycDebit, String expiry) {
        accounts.put(id, new Account(id, new BigDecimal("0.00"), new BigDecimal(limit), new BigDecimal(cycCredit), new BigDecimal(cycDebit), expiry));
        return this;
    }

    @Override public void openTransactOutput() { transact.clear(); calls.add("open-output"); }
    @Override public Optional<Long> findXrefAcctId(String c) { return Optional.ofNullable(xref.get(Cobol.rstrip(c))); }
    @Override public Optional<Account> findAccount(long id) {
        Account a = accounts.get(id);
        return a == null ? Optional.empty() : Optional.of(new Account(a.acctId, a.currBal, a.creditLimit, a.cycCredit, a.cycDebit, a.expirationDate));
    }
    @Override public boolean rewriteAccount(Account a) {
        calls.add("account");
        if (failAccountRewrite) {
            return false;
        }
        accounts.put(a.acctId, a);
        return true;
    }
    @Override public Optional<BigDecimal> findTcatBal(TcatKey k) { return Optional.ofNullable(tcat.get(k)); }
    @Override public boolean writeTcatBal(TcatKey k, BigDecimal b) { calls.add("tcat"); return tcat.putIfAbsent(k, b) == null; }
    @Override public boolean rewriteTcatBal(TcatKey k, BigDecimal b) { calls.add("tcat"); tcat.put(k, b); return true; }
    @Override public boolean writeTransaction(PostedTransaction t) { calls.add("transact"); return transact.putIfAbsent(t.tranId(), t) == null; }

    @Override public Optional<Long> xrefAcctId(String c) { return findXrefAcctId(c); }
    @Override public Optional<String> tranTypeDesc(String t) { return Optional.ofNullable(types.get(Cobol.rstrip(t))); }
    @Override public Optional<String> tranCatDesc(String t, long c) { return Optional.ofNullable(cats.get(Cobol.rstrip(t) + c)); }

    /** Loads a posting fixture case's input datasets, decoded with the copybooks. */
    public static InMemoryPostingStore fromFixture(String caseName) {
        InMemoryPostingStore s = new InMemoryPostingStore();
        var dir = TestRepo.fixture("posting", caseName);
        for (Map<String, Object> r : decode(dir, "AWS.M2.CARDDEMO.CARDXREF.PS", 50, "CVACT03Y")) {
            s.xref.put(Cobol.rstrip(r.get("XREF-CARD-NUM").toString()), ((BigDecimal) r.get("XREF-ACCT-ID")).longValue());
        }
        for (Map<String, Object> r : decode(dir, "AWS.M2.CARDDEMO.ACCTDATA.PS", 300, "CVACT01Y")) {
            long id = ((BigDecimal) r.get("ACCT-ID")).longValue();
            s.accounts.put(id, new Account(id, (BigDecimal) r.get("ACCT-CURR-BAL"), (BigDecimal) r.get("ACCT-CREDIT-LIMIT"),
                    (BigDecimal) r.get("ACCT-CURR-CYC-CREDIT"), (BigDecimal) r.get("ACCT-CURR-CYC-DEBIT"), r.get("ACCT-EXPIRAION-DATE").toString()));
        }
        for (Map<String, Object> r : decode(dir, "AWS.M2.CARDDEMO.TCATBALF.PS", 50, "CVTRA01Y")) {
            s.tcat.put(new TcatKey(((BigDecimal) r.get("TRANCAT-ACCT-ID")).longValue(), Cobol.rstrip(r.get("TRANCAT-TYPE-CD").toString()),
                    ((BigDecimal) r.get("TRANCAT-CD")).longValue()), (BigDecimal) r.get("TRAN-CAT-BAL"));
        }
        for (Map<String, Object> r : decode(dir, "AWS.M2.CARDDEMO.TRANTYPE.PS", 60, "CVTRA03Y")) {
            s.types.put(Cobol.rstrip(r.get("TRAN-TYPE").toString()), r.get("TRAN-TYPE-DESC").toString());
        }
        for (Map<String, Object> r : decode(dir, "AWS.M2.CARDDEMO.TRANCATG.PS", 60, "CVTRA04Y")) {
            s.cats.put(Cobol.rstrip(r.get("TRAN-TYPE-CD").toString()) + ((BigDecimal) r.get("TRAN-CAT-CD")).longValue(),
                    r.get("TRAN-CAT-TYPE-DESC").toString());
        }
        return s;
    }

    static List<Map<String, Object>> decode(java.nio.file.Path dir, String dsn, int lrecl, String copybook) {
        Copybook cb = TestRepo.copybooks().get(copybook);
        return FixtureLoader.readRecords(dir, dsn, lrecl).stream().map(l -> RecordCodec.decodeLine(cb, l)).toList();
    }

    /** TRANSACT as 350-byte CVTRA05Y records in key order (what the REPRO step unloads). */
    public List<String> transactRecords() {
        Copybook cb = TestRepo.copybooks().get("CVTRA05Y");
        List<String> out = new ArrayList<>();
        for (PostedTransaction t : transact.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("TRAN-ID", t.tranId());
            m.put("TRAN-TYPE-CD", t.typeCd());
            m.put("TRAN-CAT-CD", BigDecimal.valueOf(t.catCd()));
            m.put("TRAN-SOURCE", t.source());
            m.put("TRAN-DESC", t.desc());
            m.put("TRAN-AMT", t.amt());
            m.put("TRAN-MERCHANT-ID", BigDecimal.valueOf(t.merchantId()));
            m.put("TRAN-MERCHANT-NAME", t.merchantName());
            m.put("TRAN-MERCHANT-CITY", t.merchantCity());
            m.put("TRAN-MERCHANT-ZIP", t.merchantZip());
            m.put("TRAN-CARD-NUM", t.cardNum());
            m.put("TRAN-ORIG-TS", t.origTs());
            m.put("TRAN-PROC-TS", t.procTs());
            out.add(RecordCodec.encodeLine(cb, m));
        }
        return out;
    }
}
