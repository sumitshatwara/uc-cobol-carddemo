package com.carddemo.interest;

import com.carddemo.common.TestRepo;
import com.carddemo.common.codec.Copybook;
import com.carddemo.common.codec.RecordCodec;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * In-memory stand-in for the five INTCALC datasets (no database).  Behaves like the VSAM
 * clusters: TCATBALF is read in key order, keyed reads answer {@code 23} when the key is
 * missing.  Any operation can be made to return a chosen file status to drive error paths,
 * and every call is logged so tests can assert the order of I/O.
 */
final class FakeFiles implements IntCalcFiles {

    final List<TcatBal> balances = new ArrayList<>();
    /** Full CVACT01Y rows (copybook field -> value) by account id. */
    final Map<Long, Map<String, Object>> accounts = new TreeMap<>();
    final List<Xref> xrefs = new ArrayList<>();
    final Map<String, BigDecimal> rates = new HashMap<>();
    final List<InterestTran> written = new ArrayList<>();
    final List<String> log = new ArrayList<>();
    /** Forced statuses: "open:DD", "close:DD", "rewrite", "write", "rate", "rate:DEFAULT", "read:N" (N-th balance read). */
    final Map<String, String> forced = new HashMap<>();

    private Iterator<TcatBal> cursor;
    private int reads;

    // ------------------------------------------------------------------ builders

    FakeFiles balance(long acct, String type, long cat, String bal) {
        balances.add(new TcatBal(acct, type, cat, new BigDecimal(bal)));
        return this;
    }

    FakeFiles account(long acct, String group, String bal, String cycCredit, String cycDebit) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("ACCT-ID", BigDecimal.valueOf(acct));
        row.put("ACCT-ACTIVE-STATUS", "Y");
        row.put("ACCT-CURR-BAL", new BigDecimal(bal));
        row.put("ACCT-CURR-CYC-CREDIT", new BigDecimal(cycCredit));
        row.put("ACCT-CURR-CYC-DEBIT", new BigDecimal(cycDebit));
        row.put("ACCT-GROUP-ID", group);
        accounts.put(acct, row);
        return this;
    }

    FakeFiles xref(String card, long acct) {
        xrefs.add(new Xref(card, acct, acct));
        return this;
    }

    FakeFiles rate(String group, String type, long cat, String rate) {
        rates.put(key(group, type, cat), new BigDecimal(rate));
        return this;
    }

    FakeFiles force(String op, String status) {
        forced.put(op, status);
        return this;
    }

    /** Loads the input datasets of a fixture case (decoded through the copybooks, as the REPRO setup jobs do). */
    static FakeFiles fromFixture(String caseName) {
        FakeFiles f = new FakeFiles();
        Copybook acct = TestRepo.copybooks().get("CVACT01Y");
        Copybook xref = TestRepo.copybooks().get("CVACT03Y");
        Copybook tcat = TestRepo.copybooks().get("CVTRA01Y");
        Copybook disc = TestRepo.copybooks().get("CVTRA02Y");
        for (String l : input(caseName, "AWS.M2.CARDDEMO.ACCTDATA.PS")) {
            Map<String, Object> row = RecordCodec.decodeLine(acct, l);
            f.accounts.put(((BigDecimal) row.get("ACCT-ID")).longValueExact(), row);
        }
        for (String l : input(caseName, "AWS.M2.CARDDEMO.CARDXREF.PS")) {
            Map<String, Object> r = RecordCodec.decodeLine(xref, l);
            f.xrefs.add(new Xref((String) r.get("XREF-CARD-NUM"), num(r, "XREF-CUST-ID"), num(r, "XREF-ACCT-ID")));
        }
        for (String l : input(caseName, "AWS.M2.CARDDEMO.TCATBALF.PS")) {
            Map<String, Object> r = RecordCodec.decodeLine(tcat, l);
            f.balances.add(new TcatBal(num(r, "TRANCAT-ACCT-ID"), (String) r.get("TRANCAT-TYPE-CD"), num(r, "TRANCAT-CD"),
                    (BigDecimal) r.get("TRAN-CAT-BAL")));
        }
        for (String l : input(caseName, "AWS.M2.CARDDEMO.DISCGRP.PS")) {
            Map<String, Object> r = RecordCodec.decodeLine(disc, l);
            f.rates.put(key((String) r.get("DIS-ACCT-GROUP-ID"), (String) r.get("DIS-TRAN-TYPE-CD"), num(r, "DIS-TRAN-CAT-CD")),
                    (BigDecimal) r.get("DIS-INT-RATE"));
        }
        return f;
    }

    private static List<String> input(String caseName, String dsn) {
        Path p = TestRepo.fixture("interest", caseName).resolve("input").resolve(dsn + ".txt");
        try {
            return Files.readAllLines(p, StandardCharsets.ISO_8859_1).stream().filter(l -> !l.isEmpty()).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static long num(Map<String, Object> r, String field) {
        return ((BigDecimal) r.get(field)).longValueExact();
    }

    private static String key(String group, String type, long cat) {
        return group.strip() + "|" + type.strip() + "|" + cat;
    }

    // ------------------------------------------------------------------ IntCalcFiles

    @Override
    public String open(Dd dd) {
        log.add("open " + dd);
        if (dd == Dd.TCATBALF) {
            cursor = balances.stream()
                    .sorted(Comparator.comparingLong(TcatBal::acctId).thenComparing(TcatBal::typeCd).thenComparingLong(TcatBal::catCd))
                    .toList().iterator();
        }
        return forced.getOrDefault("open:" + dd, "00");
    }

    @Override
    public String close(Dd dd) {
        log.add("close " + dd);
        return forced.getOrDefault("close:" + dd, "00");
    }

    @Override
    public Io<TcatBal> readNextBalance() {
        reads++;
        log.add("read TCATBALF");
        String f = forced.get("read:" + reads);
        if (f != null) {
            return Io.status(f);
        }
        return cursor.hasNext() ? Io.ok(cursor.next()) : Io.status("10");
    }

    @Override
    public Io<AccountRecord> readAccount(long acctId) {
        log.add("read ACCTFILE " + acctId);
        Map<String, Object> r = accounts.get(acctId);
        if (r == null) {
            return Io.status("23");
        }
        return Io.ok(new AccountRecord(acctId, (String) r.get("ACCT-GROUP-ID"), (BigDecimal) r.get("ACCT-CURR-BAL"),
                (BigDecimal) r.get("ACCT-CURR-CYC-CREDIT"), (BigDecimal) r.get("ACCT-CURR-CYC-DEBIT")));
    }

    @Override
    public String rewriteAccount(AccountRecord a) {
        log.add("rewrite ACCTFILE " + a.acctId());
        String f = forced.get("rewrite");
        if (f != null) {
            return f;
        }
        Map<String, Object> r = accounts.get(a.acctId());
        r.put("ACCT-CURR-BAL", a.currBal());
        r.put("ACCT-CURR-CYC-CREDIT", a.currCycCredit());
        r.put("ACCT-CURR-CYC-DEBIT", a.currCycDebit());
        return "00";
    }

    @Override
    public Io<Xref> readXrefByAcct(long acctId) {
        log.add("read XREFFILE " + acctId);
        return xrefs.stream().filter(x -> x.acctId() == acctId).sorted(Comparator.comparing(Xref::cardNum)).findFirst()
                .map(Io::ok).orElse(Io.status("23"));
    }

    @Override
    public Io<BigDecimal> readRate(String groupId, String typeCd, long catCd) {
        log.add("read DISCGRP " + key(groupId, typeCd, catCd));
        String f = forced.get("DEFAULT".equals(groupId) ? "rate:DEFAULT" : "rate");
        if (f != null) {
            return Io.status(f);
        }
        BigDecimal r = rates.get(key(groupId, typeCd, catCd));
        return r == null ? Io.status("23") : Io.ok(r);
    }

    @Override
    public String writeTran(InterestTran tran) {
        log.add("write TRANSACT " + tran.tranId());
        String f = forced.get("write");
        if (f != null) {
            return f;
        }
        written.add(tran);
        return "00";
    }

    // ------------------------------------------------------------------ helpers for assertions

    BigDecimal balanceOf(long acct) {
        return (BigDecimal) accounts.get(acct).get("ACCT-CURR-BAL");
    }

    BigDecimal cycCreditOf(long acct) {
        return (BigDecimal) accounts.get(acct).get("ACCT-CURR-CYC-CREDIT");
    }

    BigDecimal cycDebitOf(long acct) {
        return (BigDecimal) accounts.get(acct).get("ACCT-CURR-CYC-DEBIT");
    }

    /** The account master unloaded in key order, encoded with CVACT01Y and trimmed like a dataset line. */
    List<String> accountLines() {
        Copybook cb = TestRepo.copybooks().get("CVACT01Y");
        return accounts.values().stream().map(r -> RecordCodec.encodeLine(cb, r).stripTrailing()).toList();
    }

    /** SYSTRAN in write order, encoded with CVTRA05Y and trimmed like a dataset line. */
    List<String> systranLines() {
        Copybook cb = TestRepo.copybooks().get("CVTRA05Y");
        return written.stream().map(t -> RecordCodec.encodeLine(cb, t.toFields()).stripTrailing()).toList();
    }
}
