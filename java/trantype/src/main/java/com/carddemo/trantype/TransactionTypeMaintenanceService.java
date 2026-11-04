package com.carddemo.trantype;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

public final class TransactionTypeMaintenanceService {
    public static final String INSERT_SQL =
            "INSERT INTO CARDDEMO.TRANSACTION_TYPE (TR_TYPE, TR_DESCRIPTION) VALUES (?, ?)";
    public static final String UPDATE_SQL =
            "UPDATE CARDDEMO.TRANSACTION_TYPE SET TR_DESCRIPTION = ? WHERE TR_TYPE = ?";
    public static final String DELETE_SQL =
            "DELETE FROM CARDDEMO.TRANSACTION_TYPE WHERE TR_TYPE = ?";

    /**
     * A null input represents an OPEN failure. Reading an unopened file is not reproducible off-host,
     * so the legacy READ is modeled as immediate EOF.
     */
    public int run(List<byte[]> inpfile, SqlSession sql, List<String> sysout) {
        char[] returnMessage = new char[80];
        Arrays.fill(returnMessage, ' ');
        int returnCode = 0;

        sysout.add(inpfile == null ? "OPEN FILE NOT OK" : "OPEN FILE OK");
        if (inpfile == null) {
            return returnCode;
        }

        for (byte[] input : inpfile) {
            String card = fixedCard(input, 53);
            sysout.add("PROCESSING   " + card);
            char action = card.charAt(0);
            switch (action) {
                case 'A' -> {
                    sysout.add("ADDING RECORD");
                    returnCode = 0;
                    int sqlcode = sql.executeUpdate(INSERT_SQL, card.substring(1, 3), card.substring(3, 53));
                    if (sqlcode == 0) {
                        sysout.add("RECORD INSERTED SUCCESSFULLY");
                    } else if (sqlcode < 0) {
                        writeSqlError(returnMessage, sqlcode);
                        returnCode = abend(returnMessage, sysout);
                    }
                }
                case 'U' -> {
                    sysout.add("UPDATING RECORD");
                    returnCode = 0;
                    int sqlcode = sql.executeUpdate(UPDATE_SQL, card.substring(3, 53), card.substring(1, 3));
                    if (sqlcode == 0) {
                        sysout.add("RECORD UPDATED SUCCESSFULLY");
                    } else if (sqlcode == 100) {
                        writeMessage(returnMessage, "No records found.");
                        returnCode = abend(returnMessage, sysout);
                    } else if (sqlcode < 0) {
                        writeSqlError(returnMessage, sqlcode);
                        returnCode = abend(returnMessage, sysout);
                    }
                }
                case 'D' -> {
                    sysout.add("DELETING RECORD");
                    returnCode = 0;
                    int sqlcode = sql.executeUpdate(DELETE_SQL, card.substring(1, 3));
                    if (sqlcode == 0) {
                        sysout.add("RECORD DELETED SUCCESSFULLY");
                    } else if (sqlcode == 100) {
                        writeMessage(returnMessage, "No records found.");
                        returnCode = abend(returnMessage, sysout);
                    } else if (sqlcode < 0) {
                        writeSqlError(returnMessage, sqlcode);
                        returnCode = abend(returnMessage, sysout);
                    }
                }
                case '*' -> sysout.add("IGNORING COMMENTED LINE");
                default -> {
                    writeMessage(returnMessage, "ERROR: TYPE NOT VALID");
                    returnCode = abend(returnMessage, sysout);
                }
            }
        }
        return returnCode;
    }

    private static void writeSqlError(char[] message, int sqlcode) {
        writeMessage(message, "Error accessing: TRANSACTION_TYPE table. SQLCODE:"
                + SqlCodeTranslator.editMinus4(sqlcode));
    }

    private static void writeMessage(char[] message, String value) {
        value.getChars(0, Math.min(value.length(), message.length), message, 0);
    }

    private static int abend(char[] message, List<String> sysout) {
        sysout.add(new String(message));
        return 4;
    }

    private static String fixedCard(byte[] input, int length) {
        byte[] record = new byte[length];
        Arrays.fill(record, (byte) ' ');
        if (input != null) {
            System.arraycopy(input, 0, record, 0, Math.min(input.length, length));
        }
        return new String(record, StandardCharsets.ISO_8859_1);
    }
}
