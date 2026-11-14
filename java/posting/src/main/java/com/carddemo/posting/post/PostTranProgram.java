package com.carddemo.posting.post;

import com.carddemo.common.run.SysoutWriter;
import com.carddemo.posting.Cobol;
import java.util.ArrayList;
import java.util.List;

/**
 * CBTRN02C (POSTTRAN STEP15) main line: counters, SYSOUT, rejects and the return code.  The
 * Spring Batch step calls {@link #start}, then {@link #validate} (processor) and {@link #write}
 * (writer) per record, then {@link #finish}; {@link #runAll} does the same in one call.
 */
public class PostTranProgram {

    public static final int REJECT_LRECL = 430;

    private final PostingStore store;
    private final SysoutWriter sysout;
    private final TransactionValidator validator;
    private final TransactionPoster poster;
    private final List<String> rejects = new ArrayList<>();
    private long processed;
    private long rejected;

    public PostTranProgram(PostingStore store, SysoutWriter sysout, String procTs) {
        this.store = store;
        this.sysout = sysout;
        this.validator = new TransactionValidator(store);
        this.poster = new TransactionPoster(store, sysout, procTs);
    }

    /** Start message and file opens (TRANFILE OUTPUT). @see "BR-POST-015 BR-POST-026" */
    public void start() {
        sysout.display("START OF EXECUTION OF PROGRAM CBTRN02C");
        store.openTransactOutput();
    }

    /** One record read: counted as processed, then validated. @see "BR-POST-002" */
    public Validation validate(DailyTranRecord tran) {
        processed++;
        return validator.validate(tran);
    }

    /** Valid: post it.  Invalid: count and write the reject, continue. @see "BR-POST-012 BR-POST-013" */
    public void write(Validation v) {
        if (v.valid()) {
            poster.post(v);
        } else {
            rejected++;
            rejects.add(rejectRecord(v));
        }
    }

    /** The 350-byte daily record unchanged plus the 80-byte validation trailer. @see "BR-POST-012" */
    public static String rejectRecord(Validation v) {
        return v.tran().raw() + v.trailer();
    }

    /** Counters, RC 4 when anything was rejected, end message. @see "BR-POST-014 BR-POST-015 BR-POST-027" */
    public int finish() {
        sysout.display("TRANSACTIONS PROCESSED :", Cobol.nines(processed, 9));
        sysout.display("TRANSACTIONS REJECTED  :", Cobol.nines(rejected, 9));
        int rc = rejected > 0 ? 4 : 0;
        sysout.display("END OF EXECUTION OF PROGRAM CBTRN02C");
        return rc;
    }

    public int runAll(List<String> dailyRecords) {
        start();
        for (String raw : dailyRecords) {
            write(validate(new DailyTranRecord(raw)));
        }
        return finish();
    }

    public List<String> rejects() { return List.copyOf(rejects); }
    public long processed() { return processed; }
    public long rejected() { return rejected; }
}
