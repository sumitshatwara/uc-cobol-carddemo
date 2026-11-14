package com.carddemo.trantype;

import static com.carddemo.trantype.Harness.pad;
import static com.carddemo.trantype.Harness.processing;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.common.run.SysoutWriter;
import com.carddemo.trantype.db2.SqlCodes;
import com.carddemo.trantype.program.InputFile;
import com.carddemo.trantype.program.ParmArea;
import com.carddemo.trantype.program.RunUnit;
import com.carddemo.trantype.program.StopRun;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** COBTUPDT (job MNTTRDB2) rules BR-TT-001 .. BR-TT-026, on plain objects. */
class TransactionTypeMaintenanceTest {

    private static final String DSN_ATTACH = "DSNRUN: DSN ATTACHED, RUNNING COBTUPDT";
    private static final String COMMIT = "DSNRUN: COMMIT AT END OF TASK";

    @Test
    void BR_TT_001_recordLayoutActionTypeAndDescription() {
        RunUnit ru = Harness.runUnit(InMemoryDb2Session.seeded(), List.of(), null);
        TransactionTypeMaintenance.State ws = new TransactionTypeMaintenance.State();
        InputFile in = InputFile.of(List.of("A04Authorization"));
        in.open();
        new TransactionTypeMaintenance().readRecord(ru, in, ws);
        assertThat(ws.recType()).isEqualTo("A");
        assertThat(ws.recNumber()).isEqualTo("04");
        assertThat(ws.recDesc()).isEqualTo("Authorization" + " ".repeat(37)).hasSize(50);
        assertThat(ws.inputRec).hasSize(53);
    }

    @Test
    void BR_TT_002_openFileOkIsTheSecondLine() {
        Harness.Result r = Harness.update();
        assertThat(r.sysout()).containsExactly(DSN_ATTACH, "OPEN FILE OK", COMMIT);
    }

    @Test
    void BR_TT_003_openFailureDoesNotStopTheProgram() {
        InMemoryDb2Session db = InMemoryDb2Session.seeded();
        RunUnit ru = new RunUnit(new SysoutWriter(), db, Map.of(), Harness.programs()); // no INPFILE DD
        assertThatThrownBy(() -> new TransactionTypeMaintenance(3).call(ru, ParmArea.NONE))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("did not end");
        List<String> out = ru.sysout().lines();
        assertThat(out.get(0)).isEqualTo("OPEN FILE NOT OK");
        // status 47 on every READ: no AT END, WS-INPUT-REC stays at its VALUE SPACES
        assertThat(out.get(1)).isEqualTo("PROCESSING   " + " ".repeat(53));
        assertThat(out.get(2)).isEqualTo(pad("ERROR: TYPE NOT VALID", 80));
        assertThat(ru.returnCode()).isEqualTo(4);
        assertThat(db.executed).isEmpty();
    }

    @Test
    void BR_TT_004_everyRecordIsEchoedPaddedTo53() {
        Harness.Result r = Harness.update("U02Payment - bank transfer");
        assertThat(r.line(2)).isEqualTo(processing("U02Payment - bank transfer")).hasSize(13 + 53);
        assertThat(r.line(3)).isEqualTo("UPDATING RECORD");
    }

    @Test
    void BR_TT_005_emptyInputFileIsNotAnError() {
        Harness.Result r = Harness.update();
        assertThat(r.rc()).isZero();
        assertThat(r.db().executed).isEmpty();
        assertThat(r.db().committed).containsOnlyKeys("01", "02", "03");
    }

    @Test
    void BR_TT_006_actionAInsertsANewType() {
        Harness.Result r = Harness.update("A05Refund");
        assertThat(r.line(3)).isEqualTo("ADDING RECORD");
        assertThat(r.db().committed).containsEntry("05", pad("Refund", 50));
    }

    @Test
    void BR_TT_007_insertSuccessMessage() {
        Harness.Result r = Harness.update("A04Authorization");
        assertThat(r.sysout().subList(3, 5)).containsExactly("ADDING RECORD", "RECORD INSERTED SUCCESSFULLY");
        assertThat(r.rc()).isZero();
    }

    @Test
    void BR_TT_008_descriptionIsStoredSpacePaddedTo50() {
        Harness.Result r = Harness.update("A06Fee", "U01Purchase - card present");
        assertThat(r.db().committed.get("06")).isEqualTo("Fee" + " ".repeat(47));
        assertThat(r.db().committed.get("01")).hasSize(50).startsWith("Purchase - card present ");
        assertThat(r.db().committed.get("02")).isEqualTo("Payment"); // seed row: no padding
    }

    @Test
    void BR_TT_009_actionUUpdatesTheDescriptionByKey() {
        Harness.Result r = Harness.update("U02Payment - bank transfer");
        assertThat(r.db().committed.get("02")).isEqualTo(pad("Payment - bank transfer", 50));
        assertThat(r.db().committed.get("01")).isEqualTo("Purchase");
    }

    @Test
    void BR_TT_010_updateSuccessMessage() {
        Harness.Result r = Harness.update("U01Purchase - card present");
        assertThat(r.sysout().subList(3, 5)).containsExactly("UPDATING RECORD", "RECORD UPDATED SUCCESSFULLY");
    }

    @Test
    void BR_TT_011_updateOfMissingKeyOffHostReportsSuccess() {
        // Open COBOL ESQL reports a zero-row UPDATE as SQLCODE 0 (as for DELETE, t05)
        Harness.Result r = Harness.update("U09Anything");
        assertThat(r.line(4)).isEqualTo("RECORD UPDATED SUCCESSFULLY");
        assertThat(r.rc()).isZero();
        assertThat(r.db().committed).doesNotContainKey("09");
    }

    @Test
    void BR_TT_011_updateOfMissingKeyWithSqlcode100IsAnError() {
        // the +100 branch of the source, as Db2 on z/OS would take it
        InMemoryDb2Session db = InMemoryDb2Session.seeded();
        db.zeroRowSqlcode = SqlCodes.NOT_FOUND;
        Harness.Result r = Harness.run(Harness.MNTTRDB2, db, List.of("U09Anything"), null);
        assertThat(r.line(4)).isEqualTo(pad("No records found.", 80));
        assertThat(r.rc()).isEqualTo(4);
    }

    @Test
    void BR_TT_012_actionDDeletesByKeyAndIgnoresTheDescription() {
        Harness.Result r = Harness.update("D03", "D02whatever is here");
        assertThat(r.db().committed).containsOnlyKeys("01");
    }

    @Test
    void BR_TT_013_deleteSuccessMessage() {
        Harness.Result r = Harness.update("D03");
        assertThat(r.sysout().subList(3, 5)).containsExactly("DELETING RECORD", "RECORD DELETED SUCCESSFULLY");
        assertThat(r.rc()).isZero();
    }

    @Test
    void BR_TT_014_deleteOfMissingKeyReportsSuccess() {
        Harness.Result r = Harness.update("D09");
        assertThat(r.sysout()).containsExactly(DSN_ATTACH, "OPEN FILE OK", processing("D09"),
                "DELETING RECORD", "RECORD DELETED SUCCESSFULLY", COMMIT);
        assertThat(r.db().committed).containsOnlyKeys("01", "02", "03");
        assertThat(r.rc()).isZero();
    }

    @Test
    void BR_TT_014_deleteOfMissingKeyWithSqlcode100WouldBeAnError() {
        InMemoryDb2Session db = InMemoryDb2Session.seeded();
        db.zeroRowSqlcode = SqlCodes.NOT_FOUND;
        Harness.Result r = Harness.run(Harness.MNTTRDB2, db, List.of("D09"), null);
        assertThat(r.line(4)).isEqualTo(pad("No records found.", 80));
        assertThat(r.rc()).isEqualTo(4);
    }

    @Test
    void BR_TT_015_commentRecordsAreSkipped() {
        Harness.Result r = Harness.update("*  Nightly maintenance batch", "A06Fee", "*  end");
        assertThat(r.sysout()).filteredOn("IGNORING COMMENTED LINE"::equals).hasSize(2);
        assertThat(r.db().executed).hasSize(1);
        assertThat(r.db().committed).containsOnlyKeys("01", "02", "03", "06");
        assertThat(r.rc()).isZero();
    }

    @Test
    void BR_TT_016_unknownActionCodeIsRejectedAndProcessingContinues() {
        Harness.Result r = Harness.update("X05Something", "a07lowercase", " 08blank", "A06Fee");
        assertThat(r.sysout()).filteredOn(pad("ERROR: TYPE NOT VALID", 80)::equals).hasSize(3);
        assertThat(r.db().executed).hasSize(1);
        assertThat(r.db().committed).containsKey("06");
        assertThat(r.rc()).isEqualTo(4);
    }

    @Test
    void BR_TT_017_negativeSqlcodeIsAnErrorWithEditedCode() {
        Harness.Result r = Harness.update("A01Purchase");
        assertThat(r.line(4)).isEqualTo(pad("Error accessing: TRANSACTION_TYPE table. SQLCODE: -403", 80));
        assertThat(r.rc()).isEqualTo(4);
        assertThat(com.carddemo.trantype.program.CobolText.editMinus4(-400)).isEqualTo(" -400");
        assertThat(com.carddemo.trantype.program.CobolText.editMinus4(0)).isEqualTo("    0");
    }

    @Test
    void BR_TT_017_positiveSqlcodeOtherThan100FallsThroughSilently() {
        InMemoryDb2Session db = InMemoryDb2Session.seeded();
        db.zeroRowSqlcode = 1;
        Harness.Result r = Harness.run(Harness.MNTTRDB2, db, List.of("D09"), null);
        assertThat(r.sysout()).containsExactly(DSN_ATTACH, "OPEN FILE OK", processing("D09"), "DELETING RECORD", COMMIT);
        assertThat(r.rc()).isZero();
    }

    @Test
    void BR_TT_018_duplicateAddIsRejectedWithMinus403() {
        Harness.Result r = Harness.update("A01Purchase");
        assertThat(SqlCodes.fromSqlState("23505")).isEqualTo(-403);
        assertThat(r.sysout()).containsExactly(DSN_ATTACH, "OPEN FILE OK", processing("A01Purchase"), "ADDING RECORD",
                pad("Error accessing: TRANSACTION_TYPE table. SQLCODE: -403", 80), COMMIT);
        assertThat(r.db().committed.get("01")).isEqualTo("Purchase");
        assertThat(r.rc()).isEqualTo(4);
    }

    @Test
    void BR_TT_019_errorsDoNotStopTheBatchAndRc4IsSticky() {
        Harness.Result r = Harness.update("X01bad", "*  note");
        assertThat(r.line(3)).isEqualTo(pad("ERROR: TYPE NOT VALID", 80));
        assertThat(r.line(5)).isEqualTo("IGNORING COMMENTED LINE");
        assertThat(r.rc()).isEqualTo(4);
    }

    @Test
    void BR_TT_020_errorBufferIs80BytesAndNeverCleared() {
        Harness.Result r = Harness.update("A01Purchase", "X05Something");
        assertThat(r.line(6)).isEqualTo(pad("ERROR: TYPE NOT VALIDSACTION_TYPE table. SQLCODE: -403", 80)).hasSize(80);
    }

    @Test
    void BR_TT_021_typeCodeIsNotValidated() {
        Harness.Result r = Harness.update("AABLetters", "A  Blank code");
        assertThat(r.db().committed).containsKeys("AB", "  ");
        assertThat(r.sysout()).filteredOn("RECORD INSERTED SUCCESSFULLY"::equals).hasSize(2);
    }

    @Test
    void BR_TT_022_endOfFileClosesAndStopsTheRunUnit() {
        RunUnit ru = Harness.runUnit(InMemoryDb2Session.seeded(), List.of("D03"), null);
        assertThatThrownBy(() -> new TransactionTypeMaintenance().call(ru, ParmArea.NONE)).isInstanceOf(StopRun.class);
        Harness.Result r = Harness.dynamic(List.of("DELETE FROM TRANSACTION_TYPE WHERE TR_TYPE = '03'"), "D02");
        assertThat(r.sysout().get(r.sysout().size() - 1)).isEqualTo(COMMIT);
        assertThat(r.sysout().get(r.sysout().size() - 2)).isEqualTo("RECORD DELETED SUCCESSFULLY");
    }

    @Test
    void BR_TT_023_dsnAttachLinesAndCommitAtEndOfTask() {
        Harness.Result r = Harness.update("A04Authorization");
        assertThat(r.line(0)).isEqualTo(DSN_ATTACH);
        assertThat(r.sysout().get(r.sysout().size() - 1)).isEqualTo(COMMIT);
        assertThat(r.db().commitCalled).isTrue();
        assertThat(r.db().committed).containsEntry("04", pad("Authorization", 50));
    }

    @Test
    void BR_TT_023_failedCommitIsReported() {
        InMemoryDb2Session db = InMemoryDb2Session.seeded();
        db.commitSqlcode = -400;
        Harness.Result r = Harness.run(Harness.MNTTRDB2, db, List.of(), null);
        assertThat(r.sysout().get(r.sysout().size() - 1)).isEqualTo("DSNRUN: COMMIT FAILED -0000000400");
    }

    @Test
    void BR_TT_024_failedStatementAbortsTheUnitOfWork() {
        // MNTTRDB2: a duplicate add followed by a valid add: the second fails with -400, nothing is kept
        Harness.Result r = Harness.update("A01Purchase", "A08Valid", "D03");
        assertThat(r.sysout()).contains(pad("Error accessing: TRANSACTION_TYPE table. SQLCODE: -400", 80));
        assertThat(r.sysout().get(r.sysout().size() - 1)).isEqualTo(COMMIT);
        assertThat(r.db().committed).containsOnlyKeys("01", "02", "03");
        assertThat(SqlCodes.fromSqlState("25P02")).isEqualTo(-400);
        assertThat(r.rc()).isEqualTo(4);
    }

    @Test
    void BR_TT_025_deleteOfATypeWithCategoriesIsRefused() {
        InMemoryDb2Session db = InMemoryDb2Session.seeded();
        db.typesWithCategories.add("01");
        Harness.Result r = Harness.run(Harness.MNTTRDB2, db, List.of("D01"), null);
        assertThat(SqlCodes.fromSqlState("23503")).isEqualTo(-400);
        assertThat(r.line(4)).isEqualTo(pad("Error accessing: TRANSACTION_TYPE table. SQLCODE: -400", 80));
        assertThat(r.db().committed).containsKey("01");
        assertThat(r.rc()).isEqualTo(4);
    }

    @Test
    void BR_TT_026_tableKeysAndColumnsMatchTheDb2Ddl() throws IOException {
        String ddl;
        try (InputStream in = getClass().getResourceAsStream("/db/migration/trantype/V300__db2_transaction_type_tables.sql")) {
            ddl = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(ddl).contains("tr_type                        CHAR(2) NOT NULL")
                .contains("tr_description                 VARCHAR(50) NOT NULL")
                .contains("PRIMARY KEY (tr_type)")
                .contains("ON DELETE RESTRICT");
        assertThat(TransactionTypeMaintenance.SQL_INSERT).contains("TR_TYPE, TR_DESCRIPTION");
    }
}
