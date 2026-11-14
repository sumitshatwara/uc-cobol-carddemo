package com.carddemo.trantype;

import com.carddemo.trantype.db2.Db2Session;
import com.carddemo.trantype.db2.SqlCodes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Database-free {@link Db2Session} for unit tests: CARDDEMO.TRANSACTION_TYPE as a sorted map, with
 * PostgreSQL's unit-of-work rules as Open COBOL ESQL reports them (duplicate key -403, any other
 * error -400, after an error every statement fails with -400 and COMMIT keeps nothing but reports 0,
 * zero-row UPDATE/DELETE = 0).  Understands COBTUPDT's three static statements and the dynamic
 * statements of the fixtures; anything else fails like an unknown column (-400) unless scripted.
 */
class InMemoryDb2Session implements Db2Session {

    final TreeMap<String, String> committed = new TreeMap<>();
    TreeMap<String, String> work;
    final Set<String> typesWithCategories = new TreeSet<>();
    final Map<String, Function<TreeMap<String, String>, Integer>> scripted = new HashMap<>();
    final Map<String, String> prepared = new HashMap<>();
    final List<String> executed = new ArrayList<>();
    Integer prepareSqlcode;
    Integer zeroRowSqlcode = SqlCodes.OK; // what the driver reports for a searched UPDATE/DELETE with no row
    Integer commitSqlcode;
    boolean aborted;
    boolean commitCalled;

    InMemoryDb2Session(Map<String, String> seed) {
        committed.putAll(seed);
        work = new TreeMap<>(committed);
    }

    static InMemoryDb2Session seeded() {
        return new InMemoryDb2Session(Map.of("01", "Purchase", "02", "Payment", "03", "Credit"));
    }

    private int fail(int sqlcode) {
        aborted = true;
        return sqlcode;
    }

    @Override
    public int execute(String sql, String... hv) {
        executed.add(sql);
        if (aborted) {
            return SqlCodes.fromSqlState("25P02");
        }
        switch (sql) {
            case TransactionTypeMaintenance.SQL_INSERT -> {
                if (work.containsKey(hv[0])) {
                    return fail(SqlCodes.fromSqlState("23505"));
                }
                work.put(hv[0], hv[1]);
                return 0;
            }
            case TransactionTypeMaintenance.SQL_UPDATE -> {
                if (!work.containsKey(hv[1])) {
                    return zeroRowSqlcode;
                }
                work.put(hv[1], hv[0]);
                return 0;
            }
            case TransactionTypeMaintenance.SQL_DELETE -> {
                if (typesWithCategories.contains(hv[0])) {
                    return fail(SqlCodes.fromSqlState("23503"));
                }
                return work.remove(hv[0]) == null ? zeroRowSqlcode : 0;
            }
            default -> throw new IllegalArgumentException("unexpected static SQL " + sql);
        }
    }

    @Override
    public int prepare(String name, String text) {
        if (prepareSqlcode != null) {
            return prepareSqlcode;
        }
        prepared.put(name, text);
        return 0;
    }

    private static final Pattern UPD = Pattern.compile(
            "UPDATE TRANSACTION_TYPE SET TR_DESCRIPTION = '([^']*)' WHERE TR_TYPE = '([^']*)'", Pattern.CASE_INSENSITIVE);
    private static final Pattern DEL = Pattern.compile(
            "DELETE FROM TRANSACTION_TYPE WHERE TR_TYPE = '([^']*)'", Pattern.CASE_INSENSITIVE);

    @Override
    public int executePrepared(String name) {
        String text = prepared.get(name);
        String norm = text.strip().replaceAll("\\s+", " ");
        executed.add(norm);
        if (aborted) {
            return SqlCodes.fromSqlState("25P02");
        }
        Function<TreeMap<String, String>, Integer> f = scripted.get(norm);
        if (f != null) {
            int rc = f.apply(work);
            return rc < 0 ? fail(rc) : rc;
        }
        Matcher m = UPD.matcher(norm);
        if (m.matches()) {
            if (work.containsKey(m.group(2))) {
                work.put(m.group(2), m.group(1));
            }
            return 0;
        }
        m = DEL.matcher(norm);
        if (m.matches()) {
            work.remove(m.group(1));
            return 0;
        }
        return fail(SqlCodes.fromSqlState("42703")); // undefined column / anything else
    }

    @Override
    public int commitAtEndOfTask() {
        commitCalled = true;
        if (commitSqlcode != null) {
            return commitSqlcode;
        }
        if (!aborted) {
            committed.clear();
            committed.putAll(work);
        }
        work = new TreeMap<>(committed);
        aborted = false;
        return 0;
    }

    /** The table as the harness snapshots it: {@code COPY ... ORDER BY tr_type CSV HEADER}. */
    String csv() {
        StringBuilder sb = new StringBuilder("tr_type,tr_description\n");
        committed.forEach((k, v) -> sb.append(k).append(',').append(v).append('\n'));
        return sb.toString();
    }
}
