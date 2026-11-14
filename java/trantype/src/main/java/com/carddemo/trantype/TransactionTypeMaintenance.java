package com.carddemo.trantype;

import com.carddemo.trantype.db2.Db2Session;
import com.carddemo.trantype.db2.SqlCodes;
import com.carddemo.trantype.program.CardDemoProgram;
import com.carddemo.trantype.program.CobolText;
import com.carddemo.trantype.program.InputFile;
import com.carddemo.trantype.program.ParmArea;
import com.carddemo.trantype.program.RunUnit;
import com.carddemo.trantype.program.StopRun;
import org.springframework.stereotype.Service;

/**
 * COBTUPDT: batch maintenance of {@code CARDDEMO.TRANSACTION_TYPE} from the 53-byte INPFILE
 * (column 1 action, 2-3 type code, 4-53 description).  A port of
 * {@code app/app-transaction-type-db2/cbl/COBTUPDT.cbl}, paragraph by paragraph; the legacy
 * behaviour is kept as it is, including its quirks (see java/trantype/README.md, findings).
 * <p>
 * The working storage lives in a per-call {@link State} (a COBOL program called once per run
 * unit starts with its VALUE clauses); the bean itself is stateless.
 */
@Service
public class TransactionTypeMaintenance implements CardDemoProgram {

    public static final String PROGRAM_ID = "COBTUPDT";
    public static final String DD_INPFILE = "INPFILE";
    public static final int LRECL = 53;

    static final String SQL_INSERT = "INSERT INTO CARDDEMO.TRANSACTION_TYPE (TR_TYPE, TR_DESCRIPTION) VALUES (?, ?)";
    static final String SQL_UPDATE = "UPDATE CARDDEMO.TRANSACTION_TYPE SET TR_DESCRIPTION = ? WHERE TR_TYPE = ?";
    static final String SQL_DELETE = "DELETE FROM CARDDEMO.TRANSACTION_TYPE WHERE TR_TYPE = ?";

    /**
     * Guard against the endless read loop after a failed OPEN (BR-TT-003): on the mainframe the
     * job's TIME limit or the operator would cancel it; here the step fails after this many records.
     */
    private final int runawayLimit;

    public TransactionTypeMaintenance() {
        this(100_000);
    }

    public TransactionTypeMaintenance(int runawayLimit) {
        this.runawayLimit = runawayLimit;
    }

    @Override
    public String programId() {
        return PROGRAM_ID;
    }

    /** WORKING-STORAGE of COBTUPDT. */
    static final class State {
        boolean lastRec;                              // LASTREC = 'Y'
        final char[] returnMsg = " ".repeat(80).toCharArray(); // WS-RETURN-MSG PIC X(80)
        String inputRec = " ".repeat(LRECL);          // WS-INPUT-REC (VALUE SPACES)

        String recType() { return inputRec.substring(0, 1); }
        String recNumber() { return inputRec.substring(1, 3); }
        String recDesc() { return inputRec.substring(3, LRECL); }

        /** {@code STRING ... DELIMITED BY SIZE INTO WS-RETURN-MSG}: overwrites from position 1, never clears. */
        void stringIntoReturnMsg(String text) {
            for (int i = 0; i < text.length() && i < returnMsg.length; i++) {
                returnMsg[i] = text.charAt(i);
            }
        }

        String returnMsg() { return new String(returnMsg); }
    }

    /**
     * PROCEDURE DIVISION: 0001-OPEN-FILES falls through into 1001-READ-NEXT-RECORDS, which ends with
     * STOP RUN.  Never returns normally.
     *
     * @see "BR-TT-002, BR-TT-003, BR-TT-004, BR-TT-005, BR-TT-019, BR-TT-022"
     */
    @Override
    public void call(RunUnit ru, ParmArea parm) {
        State ws = new State();
        InputFile in = ru.dd(DD_INPFILE);
        // 0001-OPEN-FILES
        if (InputFile.OK.equals(in.open())) {
            ru.display("OPEN FILE OK");
        } else {
            ru.display("OPEN FILE NOT OK");
        }
        // 1001-READ-NEXT-RECORDS
        readRecord(ru, in, ws);
        int treated = 0;
        while (!ws.lastRec) {
            if (++treated > runawayLimit) {
                throw new IllegalStateException("COBTUPDT read loop did not end after " + runawayLimit
                        + " records (INPFILE status " + in.status() + "): job cancelled");
            }
            treatRecord(ru, ws);
            readRecord(ru, in, ws);
        }
        // 2001-CLOSE-STOP
        in.close();
        throw new StopRun(PROGRAM_ID);
    }

    /**
     * 1002-READ-RECORDS: READ ... INTO WS-INPUT-REC; echo every record read.  A READ that fails
     * without AT END (file not open) leaves WS-INPUT-REC unchanged.
     *
     * @see "BR-TT-001, BR-TT-004"
     */
    void readRecord(RunUnit ru, InputFile in, State ws) {
        InputFile.Read r = in.read();
        if (r.atEnd()) {
            ws.lastRec = true;
        } else if (r.record() != null) {
            ws.inputRec = CobolText.picX(r.record(), LRECL);
        }
        if (!ws.lastRec) {
            ru.display("PROCESSING   ", ws.inputRec);
        }
    }

    /**
     * 1003-TREAT-RECORD: EVALUATE INPUT-REC-TYPE (case-sensitive, type code not validated).
     *
     * @see "BR-TT-006, BR-TT-009, BR-TT-012, BR-TT-015, BR-TT-016, BR-TT-021"
     */
    void treatRecord(RunUnit ru, State ws) {
        switch (ws.recType()) {
            case "A" -> {
                ru.display("ADDING RECORD");
                insertDb(ru, ws);
            }
            case "U" -> {
                ru.display("UPDATING RECORD");
                updateDb(ru, ws);
            }
            case "D" -> {
                ru.display("DELETING RECORD");
                deleteDb(ru, ws);
            }
            case "*" -> ru.display("IGNORING COMMENTED LINE");
            default -> {
                ws.stringIntoReturnMsg("ERROR: TYPE NOT VALID");
                abend(ru, ws);
            }
        }
    }

    /**
     * 10031-INSERT-DB.  The description host variable is the fixed PIC X(50) field, so it is stored
     * space-padded to 50.
     *
     * @see "BR-TT-006, BR-TT-007, BR-TT-008, BR-TT-017, BR-TT-018, BR-TT-026"
     */
    void insertDb(RunUnit ru, State ws) {
        int sqlcode = ru.db2().execute(SQL_INSERT, ws.recNumber(), ws.recDesc());
        if (sqlcode == SqlCodes.OK) {
            ru.display("RECORD INSERTED SUCCESSFULLY");
        } else if (sqlcode < 0) {
            sqlError(ru, ws, sqlcode);
        }
    }

    /**
     * 10032-UPDATE-DB.
     *
     * @see "BR-TT-008, BR-TT-009, BR-TT-010, BR-TT-011, BR-TT-017, BR-TT-024"
     */
    void updateDb(RunUnit ru, State ws) {
        int sqlcode = ru.db2().execute(SQL_UPDATE, ws.recDesc(), ws.recNumber());
        if (sqlcode == SqlCodes.OK) {
            ru.display("RECORD UPDATED SUCCESSFULLY");
        } else if (sqlcode == SqlCodes.NOT_FOUND) {
            ws.stringIntoReturnMsg("No records found.");
            abend(ru, ws);
        } else if (sqlcode < 0) {
            sqlError(ru, ws, sqlcode);
        }
    }

    /**
     * 10033-DELETE-DB.  Columns 4-53 are ignored.
     *
     * @see "BR-TT-012, BR-TT-013, BR-TT-014, BR-TT-017, BR-TT-025"
     */
    void deleteDb(RunUnit ru, State ws) {
        int sqlcode = ru.db2().execute(SQL_DELETE, ws.recNumber());
        if (sqlcode == SqlCodes.OK) {
            ru.display("RECORD DELETED SUCCESSFULLY");
        } else if (sqlcode == SqlCodes.NOT_FOUND) {
            ws.stringIntoReturnMsg("No records found.");
            abend(ru, ws);
        } else if (sqlcode < 0) {
            sqlError(ru, ws, sqlcode);
        }
    }

    /** @see "BR-TT-017, BR-TT-020" */
    private void sqlError(RunUnit ru, State ws, int sqlcode) {
        ws.stringIntoReturnMsg("Error accessing:" + " TRANSACTION_TYPE table. SQLCODE:" + CobolText.editMinus4(sqlcode));
        abend(ru, ws);
    }

    /**
     * 9999-ABEND: despite its name only displays the 80-byte message and sets RETURN-CODE 4;
     * processing continues and the 4 is never reset.
     *
     * @see "BR-TT-019, BR-TT-020"
     */
    private void abend(RunUnit ru, State ws) {
        ru.display(ws.returnMsg());
        ru.returnCode(4);
    }
}
