package com.carddemo.posting;

import com.carddemo.common.codec.RecordCodec;
import com.carddemo.common.run.ChainHandler;
import com.carddemo.common.run.RunContext;
import com.carddemo.common.run.RunResult;
import com.carddemo.common.export.JobRc;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Component
public class PostingHandler implements ChainHandler {
    private static final String DAILY_INPUT = "AWS.M2.CARDDEMO.DALYTRAN.PS.txt";
    private static final String TRANSACT = "AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS";
    private static final String ACCOUNT = "AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS";
    private static final String CATEGORY_BALANCE = "AWS.M2.CARDDEMO.TCATBALF.VSAM.KSDS";
    private static final String REJECTS = "AWS.M2.CARDDEMO.DALYREJS.G0001V00";
    private static final String REPORT = "AWS.M2.CARDDEMO.TRANREPT.G0001V00";

    private final DailyPostingService dailyPostingService;
    private final TransactionReportService transactionReportService;

    public PostingHandler(DailyPostingService dailyPostingService,
                          TransactionReportService transactionReportService) {
        this.dailyPostingService = dailyPostingService;
        this.transactionReportService = transactionReportService;
    }

    @Override
    public String chain() {
        return "posting";
    }

    @Override
    public List<String> extraTables() {
        return List.of();
    }

    @Override
    public RunResult run(RunContext context) throws Exception {
        DailyPostingService.Result posting = dailyPostingService.post(
                readRecords(context.caseDirectory().resolve("input").resolve(DAILY_INPUT), 350),
                context.clock().cobolCurrentDate());
        context.outputWriter().writeSysout("POSTTRAN", "STEP15", posting.sysout());
        context.outputWriter().writeDataset(REJECTS, "REJTRAN", 430, posting.rejects());

        TransactionReportService.Result report = transactionReportService.report(
                context.fixtureLoader().rawRecords("AWS.M2.CARDDEMO.DATEPARM"));
        context.outputWriter().writeSysout("TRANREPT", "STEP10R", report.sysout());
        context.outputWriter().writeTextDataset(REPORT, 133, report.records());

        exportTable(context, TRANSACT, "carddemo.\"transaction\"", "tran_id COLLATE \"C\"", "CVTRA05Y", 350);
        exportTable(context, ACCOUNT, "carddemo.account", "acct_id", "CVACT01Y", 300);
        exportTable(context, CATEGORY_BALANCE, "carddemo.tran_cat_balance",
                "trancat_acct_id, trancat_type_cd COLLATE \"C\", trancat_cd", "CVTRA01Y", 50);
        context.outputWriter().snapshotTable("carddemo.transaction", "tran_id COLLATE \"C\"");
        context.outputWriter().snapshotTable("carddemo.account", "acct_id");
        context.outputWriter().snapshotTable("carddemo.tran_cat_balance",
                "trancat_acct_id, trancat_type_cd COLLATE \"C\", trancat_cd");

        Map<String, JobRc> returnCodes = new TreeMap<>();
        returnCodes.put("POSTTRAN", jobRc(posting.returnCode(), Map.of(
                "CLCIFIL", 0, "OPCIFIL", 0, "STEP05", 0, "STEP10", 0,
                "STEP15", posting.returnCode(), "STEP20", 0, "STEP25", 0, "STEP30", 0)));
        returnCodes.put("TRANREPT", jobRc(report.returnCode(), Map.of(
                "PRC001", 0, "STEP05R", 0, "STEP10R", report.returnCode())));
        return new RunResult(returnCodes);
    }

    private static JobRc jobRc(int maxRc, Map<String, Integer> steps) {
        return new JobRc(maxRc, new TreeMap<>(steps));
    }

    private static List<byte[]> readRecords(Path file, int lrecl) {
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        try {
            List<byte[]> records = new ArrayList<>();
            for (String line : Files.readAllLines(file, StandardCharsets.ISO_8859_1)) {
                byte[] source = line.getBytes(StandardCharsets.ISO_8859_1);
                byte[] record = new byte[lrecl];
                java.util.Arrays.fill(record, (byte) ' ');
                System.arraycopy(source, 0, record, 0, Math.min(source.length, lrecl));
                records.add(record);
            }
            return records;
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read posting input " + file, exception);
        }
    }

    private static void exportTable(RunContext context, String dsn, String table, String orderBy,
                                    String copybook, int lrecl) {
        List<byte[]> records = context.jdbcTemplate().query("SELECT * FROM " + table + " ORDER BY " + orderBy,
                resultSet -> {
                    List<byte[]> rows = new ArrayList<>();
                    var metadata = resultSet.getMetaData();
                    while (resultSet.next()) {
                        Map<String, Object> values = new TreeMap<>();
                        for (int column = 1; column <= metadata.getColumnCount(); column++) {
                            String name = metadata.getColumnLabel(column).toUpperCase().replace('_', '-');
                            Object value = resultSet.getObject(column);
                            if (value instanceof BigDecimal decimal) {
                                value = decimal;
                            }
                            values.put(name, value);
                        }
                        rows.add(RecordCodec.encode(copybook, values));
                    }
                    return rows;
                });
        context.outputWriter().writeDataset(dsn, copybook, lrecl, records);
    }
}
