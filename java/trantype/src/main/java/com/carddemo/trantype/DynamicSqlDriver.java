package com.carddemo.trantype;

import com.carddemo.trantype.program.CardDemoProgram;
import com.carddemo.trantype.program.CobolText;
import com.carddemo.trantype.program.InputFile;
import com.carddemo.trantype.program.ParmArea;
import com.carddemo.trantype.program.RunUnit;
import org.springframework.stereotype.Service;

/**
 * CBTRNDYN: reads one SQL statement from DYNSQL, runs it with PREPARE/EXECUTE, then dynamically
 * CALLs the program named in the run parameter (MNTTRDYN: {@code PARMS('COBTUPDT')}) in the same
 * unit of work.  A port of {@code app/app-transaction-type-db2/cbl/CBTRNDYN.cbl}.
 */
@Service
public class DynamicSqlDriver implements CardDemoProgram {

    public static final String PROGRAM_ID = "CBTRNDYN";
    public static final String DD_DYNSQL = "DYNSQL";
    public static final int LRECL = 80;
    static final int STMT_MAX = 800;

    @Override
    public String programId() {
        return PROGRAM_ID;
    }

    /** The statement built by 1000-READ-STATEMENT: WS-SQL-STMT (VARCHAR) and WS-LINE-COUNT. */
    public record Statement(String text, int lineCount) {}

    /**
     * Main line: 1000-READ-STATEMENT, 2000-RUN-DYNAMIC-SQL, the dynamic CALL, {@code CBTRNDYN: END}
     * and GOBACK.  When the called program ends with STOP RUN control never comes back here.
     *
     * @see "BR-TT-033, BR-TT-034, BR-TT-035, BR-TT-036, BR-TT-037"
     */
    @Override
    public void call(RunUnit ru, ParmArea parm) {
        Statement stmt = readStatement(ru.dd(DD_DYNSQL));
        ru.display("CBTRNDYN: ", String.format("%02d", stmt.lineCount()), " SQL LINES READ");
        runDynamicSql(ru, stmt);
        if (parm.length() > 0) {
            // MOVE PARM-VALUE(1:PARM-LENGTH) TO WS-PGM-NAME (PIC X(08))
            String pgmName = CobolText.picX(parm.value().substring(0, Math.min(parm.length(), parm.value().length())), 8);
            ru.display("CBTRNDYN: CALLING ", pgmName);
            CardDemoProgram target = ru.programs().resolve(pgmName);
            target.call(ru, ParmArea.NONE);
        }
        ru.display("CBTRNDYN: END");
    }

    /**
     * 1000-READ-STATEMENT: concatenate columns 1-72 of every non-comment record plus one space
     * with {@code STRING ... WITH POINTER} into an 800-byte buffer (no ON OVERFLOW: excess text is
     * dropped); {@code --} in columns 1-2 marks a comment; the PIC 9(02) line count wraps at 100.
     *
     * @see "BR-TT-027, BR-TT-028, BR-TT-029, BR-TT-030"
     */
    public static Statement readStatement(InputFile dynsql) {
        char[] arr = " ".repeat(STMT_MAX).toCharArray();
        int pos = 1;
        int lineCount = 0;
        dynsql.open();
        while (true) {
            InputFile.Read r = dynsql.read();
            if (r.atEnd() || r.record() == null) {
                break;
            }
            String rec = CobolText.picX(r.record(), LRECL);
            if (!rec.startsWith("--")) {
                pos = stringWithPointer(arr, pos, rec.substring(0, 72));
                pos = stringWithPointer(arr, pos, " ");
                lineCount = CobolText.addPic99(lineCount, 1);
            }
        }
        dynsql.close();
        return new Statement(new String(arr, 0, pos - 1), lineCount);
    }

    /** {@code STRING src DELIMITED BY SIZE INTO arr WITH POINTER pos}: returns the new pointer. */
    static int stringWithPointer(char[] arr, int pos, String src) {
        if (pos < 1 || pos > arr.length) {
            return pos; // overflow: nothing transferred
        }
        for (int i = 0; i < src.length() && pos <= arr.length; i++) {
            arr[pos - 1] = src.charAt(i);
            pos++;
        }
        return pos;
    }

    /**
     * 2000-RUN-DYNAMIC-SQL: a failed PREPARE or a negative EXECUTE SQLCODE moves 8 to RETURN-CODE;
     * +100 or other positive codes leave it unchanged.  The job goes on either way.
     *
     * @see "BR-TT-031, BR-TT-032, BR-TT-033, BR-TT-038"
     */
    void runDynamicSql(RunUnit ru, Statement stmt) {
        int sqlcode = ru.db2().prepare("DYNSTMT", stmt.text());
        if (sqlcode != 0) {
            ru.display("CBTRNDYN: PREPARE FAILED SQLCODE ", CobolText.signed10(sqlcode));
            ru.returnCode(8);
        } else {
            sqlcode = ru.db2().executePrepared("DYNSTMT");
            ru.display("CBTRNDYN: EXECUTE SQLCODE ", CobolText.signed10(sqlcode));
            if (sqlcode < 0) {
                ru.returnCode(8);
            }
        }
    }
}
