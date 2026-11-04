package com.carddemo.trantype;

import com.carddemo.common.estate.ChainConfig;
import com.carddemo.common.estate.EstateRoot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TrantypeRuleTest {
    private final TransactionTypeMaintenanceService maintenance = new TransactionTypeMaintenanceService();
    private final DynamicSqlService dynamic = new DynamicSqlService();

    @Test
    @DisplayName("BR-TRT-001 INPFILE record layout")
    void brTrt001_inpfileRecordLayout() {
        FakeSqlSession sql = new FakeSqlSession();
        Outcome result = run(sql, "A04Authorization");

        assertThat(result.returnCode()).isZero();
        assertThat(sql.updates.getFirst().params()).containsExactly("04", "Authorization" + " ".repeat(37));
    }

    @Test
    @DisplayName("BR-TRT-002 Short cards are space-padded to LRECL 53")
    void brTrt002_shortCardsAreSpacePadded() {
        Outcome result = run(new FakeSqlSession(), "D03");

        assertThat(result.sysout().get(1)).isEqualTo(fixed("PROCESSING   D03", 66));
    }

    @Test
    @DisplayName("BR-TRT-003 Sequential read loop until end of file")
    void brTrt003_sequentialReadLoop() {
        FakeSqlSession sql = new FakeSqlSession();
        Outcome result = run(sql, "A07Chargeback", "U01Purchase - card present", "*  skip", "D02");

        assertThat(result.sysout().stream().filter(line -> line.startsWith("PROCESSING")).toList())
                .containsExactly(processing("A07Chargeback"), processing("U01Purchase - card present"),
                        processing("*  skip"), processing("D02"));
        assertThat(sql.updates.stream().map(Call::sql).toList()).containsExactly(
                TransactionTypeMaintenanceService.INSERT_SQL,
                TransactionTypeMaintenanceService.UPDATE_SQL,
                TransactionTypeMaintenanceService.DELETE_SQL);
    }

    @Test
    @DisplayName("BR-TRT-004 PROCESSING echo for every record")
    void brTrt004_processingEcho() {
        Outcome result = run(new FakeSqlSession(), "*  Nightly maintenance batch");

        assertThat(result.sysout()).contains(processing("*  Nightly maintenance batch"));
        assertThat(processing("*  Nightly maintenance batch")).hasSize(66);
    }

    @Test
    @DisplayName("BR-TRT-005 Open status message, no stop on open failure")
    void brTrt005_openStatus() {
        Outcome opened = run(new FakeSqlSession());
        List<String> failedOutput = new ArrayList<>();
        int failedRc = maintenance.run(null, new FakeSqlSession(), failedOutput);

        assertThat(opened.sysout()).containsExactly("OPEN FILE OK");
        assertThat(failedOutput).containsExactly("OPEN FILE NOT OK");
        assertThat(failedRc).isZero();
    }

    @Test
    @DisplayName("BR-TRT-006 Action dispatch on column 1 (case-sensitive)")
    void brTrt006_caseSensitiveDispatch() {
        FakeSqlSession sql = new FakeSqlSession();
        Outcome result = run(sql, "a08lower");

        assertThat(result.returnCode()).isEqualTo(4);
        assertThat(sql.updates).isEmpty();
        assertThat(result.sysout()).contains(message("ERROR: TYPE NOT VALID"));
    }

    @Test
    @DisplayName("BR-TRT-007 Comment cards are skipped")
    void brTrt007_commentCardsAreSkipped() {
        FakeSqlSession sql = new FakeSqlSession();
        Outcome result = run(sql, "*  Nightly maintenance batch", "A06Fee", "*  end");

        assertThat(sql.updates).hasSize(1);
        assertThat(result.sysout()).containsExactly(
                "OPEN FILE OK", processing("*  Nightly maintenance batch"), "IGNORING COMMENTED LINE",
                processing("A06Fee"), "ADDING RECORD", "RECORD INSERTED SUCCESSFULLY",
                processing("*  end"), "IGNORING COMMENTED LINE");
        assertThat(result.returnCode()).isZero();
    }

    @Test
    @DisplayName("BR-TRT-008 Invalid action code reports an error and sets RC 4")
    void brTrt008_invalidActionSetsRc4() {
        Outcome result = run(new FakeSqlSession(), "X01bad", "*  c");

        assertThat(result.sysout()).containsExactly("OPEN FILE OK", processing("X01bad"),
                message("ERROR: TYPE NOT VALID"), processing("*  c"), "IGNORING COMMENTED LINE");
        assertThat(result.returnCode()).isEqualTo(4);
    }

    @Test
    @DisplayName("BR-TRT-009 Blank card is an invalid action")
    void brTrt009_blankCardIsInvalid() {
        Outcome result = run(new FakeSqlSession(), "");

        assertThat(result.sysout()).contains(message("ERROR: TYPE NOT VALID"));
        assertThat(result.sysout().get(1)).isEqualTo("PROCESSING   " + " ".repeat(53));
        assertThat(result.returnCode()).isEqualTo(4);
    }

    @Test
    @DisplayName("BR-TRT-010 Add inserts a TRANSACTION_TYPE row")
    void brTrt010_addRows() {
        FakeSqlSession sql = new FakeSqlSession();
        Outcome result = run(sql, "A04Authorization", "A05Refund");

        assertThat(sql.updates).hasSize(2);
        assertThat(result.sysout()).containsExactly("OPEN FILE OK", processing("A04Authorization"), "ADDING RECORD",
                "RECORD INSERTED SUCCESSFULLY", processing("A05Refund"), "ADDING RECORD",
                "RECORD INSERTED SUCCESSFULLY");
        assertThat(result.returnCode()).isZero();
    }

    @Test
    @DisplayName("BR-TRT-012 No validation of type code or description")
    void brTrt012_acceptsUnvalidatedTypeAndDescription() {
        FakeSqlSession sql = new FakeSqlSession();
        Outcome result = run(sql, "A  NoNumber", "A9Xshort");

        assertThat(sql.updates.get(0).params()).containsExactly("  ", "NoNumber" + " ".repeat(42));
        assertThat(sql.updates.get(1).params()).containsExactly("9X", "short" + " ".repeat(45));
        assertThat(result.sysout().stream().filter(line -> line.equals("RECORD INSERTED SUCCESSFULLY")))
                .hasSize(2);
        assertThat(result.returnCode()).isZero();
    }

    @Test
    @DisplayName("BR-TRT-013 Duplicate add fails with negative SQLCODE (-403 under ocesql)")
    void brTrt013_duplicateAddFails() {
        FakeSqlSession sql = new FakeSqlSession();
        sql.updateCodes.add(-403);
        Outcome result = run(sql, "A01Purchase");

        assertThat(result.returnCode()).isEqualTo(4);
        assertThat(result.sysout()).contains(message("Error accessing: TRANSACTION_TYPE table. SQLCODE: -403"));
    }

    @Test
    @DisplayName("BR-TRT-014 SQL error message format")
    void brTrt014_sqlErrorMessageFormat() {
        FakeSqlSession sql = new FakeSqlSession();
        sql.updateCodes.add(-403);
        Outcome result = run(sql, "A01Purchase");

        assertThat(result.sysout().getLast())
                .isEqualTo("Error accessing: TRANSACTION_TYPE table. SQLCODE: -403" + " ".repeat(26));
        assertThat(SqlCodeTranslator.displaySqlca(-400)).isEqualTo("-0000000400");
        assertThat(SqlCodeTranslator.editMinus4(-403)).isEqualTo(" -403");
        assertThat(SqlCodeTranslator.editMinus4(0)).isEqualTo("    0");
        assertThat(SqlCodeTranslator.editMinus4(7)).isEqualTo("    7");
        assertThat(SqlCodeTranslator.editMinus4(-10_403)).isEqualTo(" -403");
    }

    @Test
    @DisplayName("BR-TRT-015 WS-RETURN-MSG is never cleared between messages")
    void brTrt015_returnMessageResidue() {
        FakeSqlSession sql = new FakeSqlSession();
        sql.updateCodes.add(-403);
        Outcome result = run(sql, "A01Dup", "X01bad");
        String first = message("Error accessing: TRANSACTION_TYPE table. SQLCODE: -403");

        assertThat(result.sysout()).containsExactly("OPEN FILE OK", processing("A01Dup"), "ADDING RECORD", first,
                processing("X01bad"), message("ERROR: TYPE NOT VALID" + first.substring(21)));
    }

    @Test
    @DisplayName("BR-TRT-016 Positive SQLCODE on insert is silently ignored")
    void brTrt016_positiveInsertCode() {
        FakeSqlSession sql = new FakeSqlSession();
        sql.updateCodes.add(100);
        Outcome result = run(sql, "A04Authorization");

        assertThat(result.sysout()).containsExactly("OPEN FILE OK", processing("A04Authorization"), "ADDING RECORD");
        assertThat(result.returnCode()).isZero();
    }

    @Test
    @DisplayName("BR-TRT-017 Update replaces the description of an existing type")
    void brTrt017_updateExisting() {
        FakeSqlSession sql = new FakeSqlSession();
        Outcome result = run(sql, "U02Payment - bank transfer");

        assertThat(sql.updates.getFirst().sql()).isEqualTo(TransactionTypeMaintenanceService.UPDATE_SQL);
        assertThat(sql.updates.getFirst().params())
                .containsExactly("Payment - bank transfer" + " ".repeat(27), "02");
        assertThat(result.sysout()).contains("RECORD UPDATED SUCCESSFULLY");
    }

    @Test
    @DisplayName("BR-TRT-018 Update of a missing type reports success under ocesql")
    void brTrt018_updateMissing() {
        Outcome result = run(new FakeSqlSession(), "U09Nothing");

        assertThat(result.sysout()).contains("UPDATING RECORD", "RECORD UPDATED SUCCESSFULLY");
        assertThat(result.returnCode()).isZero();
    }

    @Test
    @DisplayName("BR-TRT-019 Update SQL error reports and sets RC 4")
    void brTrt019_updateError() {
        FakeSqlSession sql = new FakeSqlSession();
        sql.updateCodes.add(-400);
        Outcome result = run(sql, "U01Purchase - updated");

        assertThat(result.sysout()).contains(message("Error accessing: TRANSACTION_TYPE table. SQLCODE: -400"));
        assertThat(result.returnCode()).isEqualTo(4);
    }

    @Test
    @DisplayName("BR-TRT-020 Delete removes a type by key")
    void brTrt020_deleteExisting() {
        FakeSqlSession sql = new FakeSqlSession();
        Outcome result = run(sql, "D03");

        assertThat(sql.updates.getFirst().sql()).isEqualTo(TransactionTypeMaintenanceService.DELETE_SQL);
        assertThat(sql.updates.getFirst().params()).containsExactly("03");
        assertThat(result.sysout()).contains("DELETING RECORD", "RECORD DELETED SUCCESSFULLY");
    }

    @Test
    @DisplayName("BR-TRT-021 Delete of a missing type reports success")
    void brTrt021_deleteMissing() {
        Outcome result = run(new FakeSqlSession(), "D09");

        assertThat(result.sysout()).contains("DELETING RECORD", "RECORD DELETED SUCCESSFULLY");
        assertThat(result.returnCode()).isZero();
    }

    @Test
    @DisplayName("BR-TRT-022 Delete SQL error reports and sets RC 4")
    void brTrt022_deleteError() {
        FakeSqlSession sql = new FakeSqlSession();
        sql.updateCodes.add(-400);
        Outcome result = run(sql, "D01");

        assertThat(result.sysout()).contains(message("Error accessing: TRANSACTION_TYPE table. SQLCODE: -400"));
        assertThat(result.returnCode()).isEqualTo(4);
    }

    @Test
    @DisplayName("BR-TRT-023 Errors never stop the batch")
    void brTrt023_errorsDoNotStopBatch() {
        FakeSqlSession sql = new FakeSqlSession();
        sql.updateCodes.addAll(List.of(0, -403, -400));
        Outcome result = run(sql, "A04Authorization", "A01Dup", "X01bad", "A05Refund");
        String duplicateError = message("Error accessing: TRANSACTION_TYPE table. SQLCODE: -403");

        assertThat(result.sysout().stream().filter(line -> line.startsWith("PROCESSING")).toList())
                .hasSize(4);
        assertThat(sql.updates).hasSize(3);
        assertThat(result.sysout()).contains("RECORD INSERTED SUCCESSFULLY",
                message("ERROR: TYPE NOT VALID" + duplicateError.substring(21)));
        assertThat(result.returnCode()).isEqualTo(4);
    }

    @Test
    @DisplayName("BR-TRT-024 RETURN-CODE 4 is reset by any later SQL statement")
    void brTrt024_laterSqlResetsReturnCode() {
        Outcome result = run(new FakeSqlSession(), "X01bad", "D99");

        assertThat(result.sysout()).contains(message("ERROR: TYPE NOT VALID"), "RECORD DELETED SUCCESSFULLY");
        assertThat(result.returnCode()).isZero();
    }

    @Test
    @DisplayName("BR-TRT-025 End of input closes the file and STOP RUN")
    void brTrt025_emptyFileStops() {
        FakeSqlSession sql = new FakeSqlSession();
        Outcome result = run(sql);

        assertThat(result.sysout()).containsExactly("OPEN FILE OK");
        assertThat(sql.updates).isEmpty();
        assertThat(result.returnCode()).isZero();
    }

    @Test
    @DisplayName("BR-TRT-027 DSN attach banner and connect failure (harness)")
    void brTrt027_dsnConnectFailure() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenThrow(new SQLException("connection refused", "08001"));
        AtomicBoolean called = new AtomicBoolean();
        List<String> sysout = new ArrayList<>();

        int rc = new DsnRunService().run(dataSource, "CBTRNDYN", "", sysout, sql -> {
            called.set(true);
            return 0;
        });

        assertThat(rc).isEqualTo(12);
        assertThat(called).isFalse();
        assertThat(sysout).containsExactly("DSNRUN: DATABASE CONNECT FAILED -0000000400");
    }

    @Test
    @DisplayName("BR-TRT-028 Commit at end of task (harness)")
    void brTrt028_commitAtEndOfTask() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        List<String> sysout = new ArrayList<>();

        int rc = new DsnRunService().run(dataSource, "COBTUPDT", "", sysout, sql -> 4);

        assertThat(rc).isEqualTo(4);
        assertThat(sysout).containsExactly("DSNRUN: DSN ATTACHED, RUNNING COBTUPDT",
                "DSNRUN: COMMIT AT END OF TASK");
        verify(connection).setAutoCommit(false);
        verify(connection).commit();
        verify(connection).close();
    }

    @Test
    @DisplayName("BR-TRT-028 Commit at end of task (harness)")
    void brTrt028_commitFailureDoesNotChangeBodyRc() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        doThrow(new SQLException("commit failed", "08006")).when(connection).commit();
        List<String> sysout = new ArrayList<>();

        int rc = new DsnRunService().run(dataSource, "COBTUPDT", "", sysout, sql -> 4);

        assertThat(rc).isEqualTo(4);
        assertThat(sysout).containsExactly("DSNRUN: DSN ATTACHED, RUNNING COBTUPDT",
                "DSNRUN: COMMIT FAILED -0000000400");
        verify(connection).close();
    }

    @Test
    @DisplayName("BR-TRT-030 MNTTRDYN step, DDs and PARMS")
    void brTrt030_dynamicJobSetup() throws Exception {
        ChainConfig config = new ChainConfig(EstateRoot.root());
        ChainConfig.Chain chain = config.get("trantype-dynamic");
        String jcl = Files.readString(EstateRoot.root().resolve("app/app-transaction-type-db2/jcl/MNTTRDYN.jcl"));

        assertThat(chain.inputs()).containsEntry("DYNSQL", 80).containsEntry("INPFILE", 53);
        assertThat(chain.jobs()).containsExactly("MNTTRDYN");
        assertThat(jcl).contains("PARMS('COBTUPDT')");
    }

    @Test
    @DisplayName("BR-TRT-031 DYNSQL card reading and comment cards")
    void brTrt031_dynamicCardsAndComments() {
        FakeSqlSession sql = new FakeSqlSession();
        List<String> sysout = new ArrayList<>();

        dynamic.run(dynamicCards("-- ignored", "UPDATE TRANSACTION_TYPE",
                        " SET TR_DESCRIPTION = 'Payment' WHERE TR_TYPE = '02'"),
                "", sql, maintenance, List.of(), sysout);

        assertThat(sql.preparedText).hasSize(146);
        assertThat(sql.preparedText).contains("UPDATE TRANSACTION_TYPE", "Payment");
        assertThat(sql.preparedText).doesNotContain("ignored");
    }

    @Test
    @DisplayName("BR-TRT-032 SQL lines read message")
    void brTrt032_sqlLinesRead() {
        List<String> sysout = new ArrayList<>();

        dynamic.run(dynamicCards("-- comment", "SELECT 1", "SELECT 2", "SELECT 3"),
                "", new FakeSqlSession(), maintenance, List.of(), sysout);

        assertThat(sysout).contains("CBTRNDYN: 03 SQL LINES READ");
    }

    @Test
    @DisplayName("BR-TRT-033 Blank cards and columns 73-80")
    void brTrt033_blankCardsAndSequenceColumns() {
        String first = "UPDATE TRANSACTION_TYPE";
        String firstCard = fixed(first, 72) + "SEQ00010";
        String third = " SET TR_DESCRIPTION = 'Seq' WHERE TR_TYPE = '03'";
        FakeSqlSession sql = new FakeSqlSession();
        List<String> sysout = new ArrayList<>();

        dynamic.run(dynamicCards(firstCard, "", third), "", sql, maintenance, List.of(), sysout);

        assertThat(sql.preparedText).hasSize(219).doesNotContain("SEQ00010");
        assertThat(sysout).contains("CBTRNDYN: 03 SQL LINES READ");
    }

    @Test
    @DisplayName("BR-TRT-035 Statement buffer limit 800 bytes; line count wraps at 100")
    void brTrt035_statementBufferAndLineCountLimits() {
        FakeSqlSession sql = new FakeSqlSession();
        List<String> sysout = new ArrayList<>();
        dynamic.run(Stream.generate(() -> dynamicCard("x")).limit(12).toList(),
                "", sql, maintenance, List.of(), sysout);
        assertThat(sql.preparedText).hasSize(800);
        assertThat(sysout).contains("CBTRNDYN: 12 SQL LINES READ");

        List<String> wrapped = new ArrayList<>();
        dynamic.run(Stream.generate(() -> dynamicCard(" ")).limit(100).toList(),
                "", new FakeSqlSession(), maintenance, List.of(), wrapped);
        assertThat(wrapped).contains("CBTRNDYN: 00 SQL LINES READ");
    }

    @Test
    @DisplayName("BR-TRT-036 PREPARE then EXECUTE, PREPARE failure path")
    void brTrt036_prepareThenExecute() {
        FakeSqlSession sql = new FakeSqlSession();
        List<String> sysout = new ArrayList<>();
        dynamic.run(dynamicCards("DELETE FROM TRANSACTION_TYPE WHERE TR_TYPE = '03'"),
                "", sql, maintenance, List.of(), sysout);
        assertThat(sql.events).containsExactly("prepare", "execute");
        assertThat(sysout).contains("CBTRNDYN: EXECUTE SQLCODE +0000000000");

        FakeSqlSession prepareFailure = new FakeSqlSession();
        prepareFailure.prepareCode = -400;
        List<String> failedOutput = new ArrayList<>();
        DynamicSqlService.Result failed = dynamic.run(dynamicCards("SELECT 1"), "", prepareFailure,
                maintenance, List.of(), failedOutput);
        assertThat(failed.returnCode()).isEqualTo(8);
        assertThat(prepareFailure.events).containsExactly("prepare");
        assertThat(failedOutput).contains("CBTRNDYN: PREPARE FAILED SQLCODE -0000000400");
    }

    @Test
    @DisplayName("BR-TRT-037 Statement errors surface at EXECUTE, not PREPARE, under ocesql")
    void brTrt037_errorsSurfaceAtExecute() {
        FakeSqlSession sql = new FakeSqlSession();
        sql.executeCode = -400;
        List<String> sysout = new ArrayList<>();
        DynamicSqlService.Result result = dynamic.run(dynamicCards("UPDATE TRANSACTION_TYPE"),
                "", sql, maintenance, List.of(), sysout);

        assertThat(result.returnCode()).isEqualTo(8);
        assertThat(sysout).contains("CBTRNDYN: EXECUTE SQLCODE -0000000400");
        assertThat(sysout).noneMatch(line -> line.startsWith("CBTRNDYN: PREPARE FAILED"));
    }

    @Test
    @DisplayName("BR-TRT-038 EXECUTE SQLCODE < 0 sets RC 8; non-negative does not")
    void brTrt038_executeReturnCodeAndCall() {
        FakeSqlSession negativeSql = new FakeSqlSession();
        negativeSql.executeCode = -400;
        DynamicSqlService.Result negative = dynamic.run(dynamicCards("SELECT 1"), "",
                negativeSql, maintenance, List.of(), new ArrayList<>());
        FakeSqlSession successfulSql = new FakeSqlSession();
        List<String> sysout = new ArrayList<>();
        DynamicSqlService.Result successful = dynamic.run(dynamicCards("UPDATE TRANSACTION_TYPE"),
                "COBTUPDT", successfulSql, maintenance, List.of(), sysout);

        assertThat(negative.returnCode()).isEqualTo(8);
        assertThat(successful.returnCode()).isZero();
        assertThat(successful.stopRun()).isTrue();
        assertThat(sysout).contains("CBTRNDYN: CALLING COBTUPDT", "OPEN FILE OK");
    }

    @Test
    @DisplayName("BR-TRT-039 CBTRNDYN's RC 8 is not the step RC once COBTUPDT runs")
    void brTrt039_dynamicRcReplacedByCobtupdt() {
        FakeSqlSession sql = new FakeSqlSession();
        sql.executeCode = -400;
        sql.updateCodes.add(-400);
        List<String> sysout = new ArrayList<>();

        DynamicSqlService.Result result = dynamic.run(dynamicCards("UPDATE NO_SUCH_COLUMN"),
                "COBTUPDT", sql, maintenance, cards("U01Purchase - updated"), sysout);

        assertThat(result.returnCode()).isEqualTo(4);
        assertThat(sysout).contains("CBTRNDYN: EXECUTE SQLCODE -0000000400",
                message("Error accessing: TRANSACTION_TYPE table. SQLCODE: -400"));
    }

    @Test
    @DisplayName("BR-TRT-040 Dynamic CALL of the program named in PARMS")
    void brTrt040_dynamicCallFromParms() {
        List<String> sysout = new ArrayList<>();
        dynamic.run(dynamicCards("INVALID SQL"), "COBTUPDT", new FakeSqlSession(),
                maintenance, List.of(), sysout);

        assertThat(sysout).containsSubsequence("CBTRNDYN: CALLING COBTUPDT", "OPEN FILE OK");
    }

    @Test
    @DisplayName("BR-TRT-041 COBTUPDT's STOP RUN ends the run unit")
    void brTrt041_cobtupdtStopRun() {
        List<String> sysout = new ArrayList<>();
        DynamicSqlService.Result result = dynamic.run(dynamicCards("SELECT 1"), "COBTUPDT",
                new FakeSqlSession(), maintenance, cards("A04Authorization"), sysout);

        assertThat(result.stopRun()).isTrue();
        assertThat(sysout).doesNotContain("CBTRNDYN: END");
        assertThat(sysout).endsWith("RECORD INSERTED SUCCESSFULLY");
    }

    @Test
    @DisplayName("BR-TRT-042 No PARMS: no CALL and normal end")
    void brTrt042_noParmsNormalEnd() {
        List<String> sysout = new ArrayList<>();
        DynamicSqlService.Result result = dynamic.run(dynamicCards("SELECT 1"), "",
                new FakeSqlSession(), maintenance, null, sysout);

        assertThat(result.stopRun()).isFalse();
        assertThat(result.returnCode()).isZero();
        assertThat(sysout).endsWith("CBTRNDYN: END");
        assertThat(sysout).noneMatch(line -> line.startsWith("OPEN FILE"));
    }

    @Test
    @DisplayName("BR-TRT-045 ocesql source normalisation (harness)")
    void brTrt045_sqlConstantsMatchCobolSource() throws Exception {
        String source = Files.readString(EstateRoot.root()
                .resolve("app/app-transaction-type-db2/cbl/COBTUPDT.cbl"));
        String columns = source.lines()
                .map(line -> line.substring(0, Math.min(72, line.length())))
                .collect(Collectors.joining("\n"));
        Matcher matcher = Pattern.compile("(?is)EXEC SQL(.*?)END-EXEC").matcher(columns);
        List<String> statements = new ArrayList<>();
        while (matcher.find()) {
            String statement = normalizeSql(matcher.group(1));
            if (statement.startsWith("INSERT INTO CARDDEMO.TRANSACTION_TYPE")
                    || statement.startsWith("UPDATE CARDDEMO.TRANSACTION_TYPE")
                    || statement.startsWith("DELETE FROM CARDDEMO.TRANSACTION_TYPE")) {
                statements.add(statement);
            }
        }

        assertThat(statements).containsExactlyInAnyOrder(
                normalizeSql(TransactionTypeMaintenanceService.INSERT_SQL),
                normalizeSql(TransactionTypeMaintenanceService.UPDATE_SQL),
                normalizeSql(TransactionTypeMaintenanceService.DELETE_SQL));
    }

    private Outcome run(FakeSqlSession sql, String... input) {
        List<String> sysout = new ArrayList<>();
        int rc = maintenance.run(cards(input), sql, sysout);
        return new Outcome(rc, sysout);
    }

    private static List<byte[]> cards(String... records) {
        return Arrays.stream(records).map(record -> fixedCard(record, 53)).toList();
    }

    private static List<byte[]> dynamicCards(String... records) {
        return Arrays.stream(records).map(record -> fixedCard(record, 80)).toList();
    }

    private static byte[] dynamicCard(String record) {
        return fixedCard(record, 80);
    }

    private static byte[] fixedCard(String value, int width) {
        return fixed(value, width).getBytes(StandardCharsets.ISO_8859_1);
    }

    private static String fixed(String value, int width) {
        String truncated = value.length() > width ? value.substring(0, width) : value;
        return truncated + " ".repeat(width - truncated.length());
    }

    private static String processing(String value) {
        return "PROCESSING   " + fixed(value, 53);
    }

    private static String message(String value) {
        return fixed(value, 80);
    }

    private static String normalizeSql(String sql) {
        return sql.replaceAll("(?i):INPUT-REC-NUMBER", "?")
                .replaceAll("(?i):INPUT-REC-DESC", "?")
                .replaceAll("\\s*([(),])\\s*", "$1")
                .replaceAll("\\s+", " ").trim().toUpperCase(Locale.ROOT);
    }

    private record Outcome(int returnCode, List<String> sysout) {
    }

    private record Call(String sql, List<String> params) {
    }

    private static final class FakeSqlSession implements SqlSession {
        private final List<Call> updates = new ArrayList<>();
        private final Queue<Integer> updateCodes = new ArrayDeque<>();
        private final List<String> events = new ArrayList<>();
        private String preparedText;
        private int prepareCode;
        private int executeCode;
        private int commitCode;

        @Override
        public int executeUpdate(String sql, String... params) {
            updates.add(new Call(sql, List.copyOf(Arrays.asList(params.clone()))));
            return updateCodes.isEmpty() ? 0 : updateCodes.remove();
        }

        @Override
        public int prepare(String text) {
            events.add("prepare");
            preparedText = text;
            return prepareCode;
        }

        @Override
        public int executePrepared() {
            events.add("execute");
            return executeCode;
        }

        @Override
        public int commit() {
            events.add("commit");
            return commitCode;
        }

        @Override
        public void close() {
            events.add("close");
        }
    }
}
