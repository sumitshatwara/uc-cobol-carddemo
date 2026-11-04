package com.carddemo.trantype;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

public final class DynamicSqlService {
    private static final int STATEMENT_LENGTH = 800;

    public Result run(List<byte[]> dynsql, String parms, SqlSession sql,
                      TransactionTypeMaintenanceService cobtupdt, List<byte[]> inpfile, List<String> sysout) {
        char[] statement = new char[STATEMENT_LENGTH];
        Arrays.fill(statement, ' ');
        int position = 0;
        int lineCount = 0;

        if (dynsql != null) {
            for (byte[] input : dynsql) {
                String card = fixedCard(input);
                if (!card.startsWith("--")) {
                    for (int index = 0; index < 72 && position < statement.length; index++) {
                        statement[position++] = card.charAt(index);
                    }
                    if (position < statement.length) {
                        statement[position++] = ' ';
                    }
                    lineCount = (lineCount + 1) % 100;
                }
            }
        }

        sysout.add("CBTRNDYN: " + String.format("%02d", lineCount) + " SQL LINES READ");
        int returnCode;
        int prepareCode = sql.prepare(new String(statement, 0, position));
        if (prepareCode != 0) {
            sysout.add("CBTRNDYN: PREPARE FAILED SQLCODE " + SqlCodeTranslator.displaySqlca(prepareCode));
            returnCode = 8;
        } else {
            returnCode = 0;
            int executeCode = sql.executePrepared();
            sysout.add("CBTRNDYN: EXECUTE SQLCODE " + SqlCodeTranslator.displaySqlca(executeCode));
            if (executeCode < 0) {
                returnCode = 8;
            }
        }

        if (parms != null && !parms.isEmpty()) {
            String program = (parms.length() > 8 ? parms.substring(0, 8) : parms);
            program = String.format("%-8s", program);
            sysout.add("CBTRNDYN: CALLING " + program);
            if (!program.trim().equals("COBTUPDT")) {
                throw new IllegalStateException("module not found: " + program.trim());
            }
            return new Result(cobtupdt.run(inpfile, sql, sysout), true);
        }

        sysout.add("CBTRNDYN: END");
        return new Result(returnCode, false);
    }

    private static String fixedCard(byte[] input) {
        byte[] record = new byte[80];
        Arrays.fill(record, (byte) ' ');
        if (input != null) {
            System.arraycopy(input, 0, record, 0, Math.min(input.length, record.length));
        }
        return new String(record, StandardCharsets.ISO_8859_1);
    }

    public record Result(int returnCode, boolean stopRun) {
    }
}
