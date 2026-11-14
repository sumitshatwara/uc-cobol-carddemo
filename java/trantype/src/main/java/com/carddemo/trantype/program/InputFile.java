package com.carddemo.trantype.program;

import java.util.Iterator;
import java.util.List;

/**
 * A sequential input DD (QSAM, RECFM=F) as a COBOL program sees it: {@code OPEN INPUT} sets a
 * file status, {@code READ} returns the next record or end of file.  Reading a file that is not
 * open returns status 47 and never raises AT END (GnuCOBOL and z/OS alike).
 */
public final class InputFile {

    public static final String OK = "00";
    public static final String NOT_FOUND = "35";
    public static final String NOT_OPEN = "47";

    private final List<String> records;
    private Iterator<String> it;
    private String status = NOT_OPEN;

    private InputFile(List<String> records) {
        this.records = records;
    }

    public static InputFile of(List<String> records) {
        return new InputFile(List.copyOf(records));
    }

    /** A DD whose dataset does not exist: OPEN fails with status 35. */
    public static InputFile missing() {
        return new InputFile(null);
    }

    public String open() {
        if (records == null) {
            status = NOT_FOUND;
        } else {
            it = records.iterator();
            status = OK;
        }
        return status;
    }

    /** Result of one READ. */
    public record Read(String status, String record) {
        public boolean atEnd() { return "10".equals(status); }
    }

    public Read read() {
        if (it == null) {
            return new Read(NOT_OPEN, null);
        }
        return it.hasNext() ? new Read(OK, it.next()) : new Read("10", null);
    }

    public void close() {
        it = null;
    }

    public String status() {
        return status;
    }
}
