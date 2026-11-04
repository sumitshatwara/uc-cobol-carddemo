package com.carddemo.posting;

import com.carddemo.common.codec.RecordCodec;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class TransactionReportService {
    private static final String START = "START OF EXECUTION OF PROGRAM CBTRN03C";
    private static final String END = "END OF EXECUTION OF PROGRAM CBTRN03C";
    private static final String SORT_START = "2022-01-01";
    private static final String SORT_END = "2022-07-06";
    private static final List<String> IO_ERRORS = List.of(
            "ERROR OPENING TRANSACTION FILE",
            "ERROR OPENING REPORT FILE",
            "ERROR OPENING CROSS REF FILE",
            "ERROR OPENING TRANSACTION TYPE FILE",
            "ERROR OPENING TRANSACTION CATG FILE",
            "ERROR OPENING DATE PARM FILE",
            "ERROR READING TRANSACTION FILE",
            "ERROR WRITING REPTFILE",
            "ERROR CLOSING POSTED TRANSACTION FILE",
            "ERROR CLOSING REPORT FILE",
            "ERROR CLOSING CROSS REF FILE",
            "ERROR CLOSING TRANSACTION TYPE FILE",
            "ERROR CLOSING TRANSACTION CATG FILE",
            "ERROR CLOSING DATE PARM FILE");
    private static final byte[] BLANK_RECORD = new byte[350];

    static {
        Arrays.fill(BLANK_RECORD, (byte) ' ');
    }

    private final ReportLookups lookups;

    public TransactionReportService(ReportLookups lookups) {
        this.lookups = lookups;
    }

    public Result report(List<byte[]> dateParmRecords) {
        List<String> sysout = new ArrayList<>();
        List<String> output = new ArrayList<>();
        sysout.add(START);
        boolean endOfFile = dateParmRecords.isEmpty();
        String startDate = " ".repeat(10);
        String endDate = " ".repeat(10);
        if (!endOfFile) {
            byte[] dateParm = fixedRecord(dateParmRecords.getFirst(), 80);
            startDate = new String(dateParm, 0, 10, StandardCharsets.ISO_8859_1);
            endDate = new String(dateParm, 11, 10, StandardCharsets.ISO_8859_1);
            sysout.add("Reporting from " + startDate + " to " + endDate);
        }

        List<byte[]> transactions = lookups.transactions().stream()
                .map(transaction -> RecordCodec.encode("CVTRA05Y", transaction))
                .filter(TransactionReportService::inSortWindow)
                .sorted(Comparator.comparing(record -> new String(record, 262, 16, StandardCharsets.ISO_8859_1)))
                .toList();
        ReportState state = new ReportState(output, sysout, startDate, endDate);
        byte[] current = BLANK_RECORD.clone();
        int index = 0;

        while (!endOfFile) {
            if (index < transactions.size()) {
                current = transactions.get(index++);
            } else {
                endOfFile = true;
            }
            String currentDate = new String(current, 304, 10, StandardCharsets.ISO_8859_1);
            if (currentDate.compareTo(startDate) < 0 || currentDate.compareTo(endDate) > 0) {
                break;
            }
            if (!endOfFile) {
                sysout.add(new String(current, StandardCharsets.ISO_8859_1));
                Result aborted = processRecord(current, state);
                if (aborted != null) {
                    return aborted;
                }
            } else {
                BigDecimal amount = decimal(RecordCodec.decode("CVTRA05Y", current).get("TRAN-AMT"));
                sysout.add("TRAN-AMT " + CobolFormat.displaySigned(amount));
                sysout.add("WS-PAGE-TOTAL" + CobolFormat.displaySigned(state.pageTotal));
                state.pageTotal = add(state.pageTotal, amount);
                state.accountTotal = add(state.accountTotal, amount);
                state.writePageTotal();
                state.writeGrandTotal();
            }
        }

        sysout.add(END);
        return new Result(sysout, output, 0, false);
    }

    public static List<String> ioErrorMessages() {
        return IO_ERRORS;
    }

    public static List<String> ioError(String message, String status) {
        return CobolFormat.abendLines(message, status);
    }

    private Result processRecord(byte[] record, ReportState state) {
        Map<String, Object> transaction = RecordCodec.decode("CVTRA05Y", record);
        String cardNumber = text(transaction.get("TRAN-CARD-NUM"));
        if (!cardNumber.equals(state.currentCard)) {
            if (!state.firstTime) {
                state.writeAccountTotal();
            }
            state.currentCard = cardNumber;
            Optional<BigDecimal> accountId = lookups.accountIdForCard(cardNumber);
            if (accountId.isEmpty()) {
                return state.abend("INVALID CARD NUMBER : " + CobolFormat.rightPad(cardNumber, 16));
            }
            state.accountId = CobolFormat.zeroPad(accountId.get(), 11);
        }

        String typeCode = text(transaction.get("TRAN-TYPE-CD"));
        Optional<String> typeDescription = lookups.transactionTypeDescription(typeCode);
        if (typeDescription.isEmpty()) {
            return state.abend("INVALID TRANSACTION TYPE : " + CobolFormat.rightPad(typeCode, 2));
        }
        BigDecimal categoryCode = decimal(transaction.get("TRAN-CAT-CD"));
        Optional<String> categoryDescription =
                lookups.transactionCategoryDescription(typeCode, categoryCode);
        if (categoryDescription.isEmpty()) {
            return state.abend("INVALID TRAN CATG KEY : " + CobolFormat.rightPad(typeCode, 2)
                    + CobolFormat.zeroPad(categoryCode, 4));
        }
        state.writeTransactionReport(transaction, typeDescription.get(), categoryDescription.get());
        return null;
    }

    private static boolean inSortWindow(byte[] record) {
        String date = new String(record, 304, 10, StandardCharsets.ISO_8859_1);
        return date.compareTo(SORT_START) >= 0 && date.compareTo(SORT_END) <= 0;
    }

    private static BigDecimal decimal(Object value) {
        return value instanceof BigDecimal number ? number : new BigDecimal(String.valueOf(value));
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static byte[] fixedRecord(byte[] source, int length) {
        byte[] result = new byte[length];
        Arrays.fill(result, (byte) ' ');
        System.arraycopy(source, 0, result, 0, Math.min(source.length, length));
        return result;
    }

    private static BigDecimal add(BigDecimal left, BigDecimal right) {
        return CobolFormat.truncateHighOrder(left.add(right), 9);
    }

    private static String reportHeader(String start, String end) {
        return CobolFormat.rightPad("DALYREPT" + " ".repeat(30) + "Daily Transaction Report"
                + " ".repeat(17) + "Date Range: " + start + " to " + end, 133);
    }

    private static String heading() {
        return CobolFormat.rightPad(CobolFormat.rightPad("Transaction ID", 17)
                + CobolFormat.rightPad("Account ID", 12)
                + CobolFormat.rightPad("Transaction Type", 19)
                + CobolFormat.rightPad("Tran Category", 35)
                + CobolFormat.rightPad("Tran Source", 14)
                + " " + CobolFormat.rightPad("        Amount", 16), 133);
    }

    private static String detail(Map<String, Object> transaction, String accountId,
                                 String typeDescription, String categoryDescription) {
        String typeCode = text(transaction.get("TRAN-TYPE-CD"));
        String categoryCode = CobolFormat.zeroPad(decimal(transaction.get("TRAN-CAT-CD")), 4);
        String line = CobolFormat.rightPad(text(transaction.get("TRAN-ID")), 16) + " "
                + accountId + " " + CobolFormat.rightPad(typeCode, 2) + "-"
                + CobolFormat.rightPad(typeDescription, 15) + " " + categoryCode + "-"
                + CobolFormat.rightPad(categoryDescription, 29) + " "
                + CobolFormat.rightPad(text(transaction.get("TRAN-SOURCE")), 10) + "    "
                + CobolFormat.editMinus(decimal(transaction.get("TRAN-AMT"))) + "  ";
        return CobolFormat.rightPad(line, 133);
    }

    private static String reportTotal(String label, int labelWidth, int dotWidth, BigDecimal amount) {
        return CobolFormat.rightPad(CobolFormat.rightPad(label, labelWidth) + ".".repeat(dotWidth)
                + CobolFormat.editSigned(amount), 133);
    }

    private static final class ReportState {
        private final List<String> output;
        private final List<String> sysout;
        private final String startDate;
        private final String endDate;
        private BigDecimal pageTotal = BigDecimal.ZERO.setScale(2);
        private BigDecimal accountTotal = BigDecimal.ZERO.setScale(2);
        private BigDecimal grandTotal = BigDecimal.ZERO.setScale(2);
        private String currentCard = " ".repeat(16);
        private String accountId = " ".repeat(11);
        private int lineCounter;
        private boolean firstTime = true;

        private ReportState(List<String> output, List<String> sysout, String startDate, String endDate) {
            this.output = output;
            this.sysout = sysout;
            this.startDate = startDate;
            this.endDate = endDate;
        }

        private void writeTransactionReport(Map<String, Object> transaction,
                                            String typeDescription, String categoryDescription) {
            if (firstTime) {
                firstTime = false;
                writeHeaders();
            }
            if (lineCounter % 20 == 0) {
                writePageTotal();
                writeHeaders();
            }
            BigDecimal amount = decimal(transaction.get("TRAN-AMT"));
            pageTotal = add(pageTotal, amount);
            accountTotal = add(accountTotal, amount);
            output.add(detail(transaction, accountId, typeDescription, categoryDescription));
            lineCounter++;
        }

        private void writeHeaders() {
            output.add(reportHeader(startDate, endDate));
            lineCounter++;
            output.add(" ".repeat(133));
            lineCounter++;
            output.add(heading());
            lineCounter++;
            output.add("-".repeat(133));
            lineCounter++;
        }

        private void writePageTotal() {
            output.add(reportTotal("Page Total", 11, 86, pageTotal));
            grandTotal = add(grandTotal, pageTotal);
            pageTotal = BigDecimal.ZERO.setScale(2);
            lineCounter++;
            output.add("-".repeat(133));
            lineCounter++;
        }

        private void writeAccountTotal() {
            output.add(reportTotal("Account Total", 13, 84, accountTotal));
            accountTotal = BigDecimal.ZERO.setScale(2);
            lineCounter++;
            output.add("-".repeat(133));
            lineCounter++;
        }

        private void writeGrandTotal() {
            output.add(reportTotal("Grand Total", 11, 86, grandTotal));
        }

        private Result abend(String message) {
            sysout.addAll(CobolFormat.abendLines(message, "23"));
            return new Result(sysout, output, 12, true);
        }
    }

    public record Result(List<String> sysout, List<String> records, int returnCode, boolean abended) {
        public Result {
            sysout = List.copyOf(sysout);
            records = List.copyOf(records);
        }
    }
}
