package com.carddemo.trantype;

import static com.carddemo.trantype.Harness.pad;
import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.common.TestRepo;
import com.carddemo.trantype.program.InputFile;
import com.carddemo.trantype.program.ParmArea;
import com.carddemo.trantype.program.ProgramNotFoundException;
import com.carddemo.trantype.program.ProgramRegistry;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** CBTRNDYN (job MNTTRDYN) rules BR-TT-027 .. BR-TT-038, on plain objects. */
class DynamicSqlDriverTest {

    private static final List<String> D01_SQL = List.of(
            "-- ad-hoc fix requested by operations",
            "UPDATE TRANSACTION_TYPE",
            "   SET TR_DESCRIPTION = 'Payment - SEPA'",
            " WHERE TR_TYPE = '02'");
    private static final List<String> D03_SQL = List.of("UPDATE TRANSACTION_TYPE SET NO_SUCH_COLUMN = 'x' WHERE TR_TYPE = '01'");
    private static final List<String> D02_SQL = List.of("DELETE FROM TRANSACTION_TYPE WHERE TR_TYPE = '03'");

    @Test
    void BR_TT_027_statementIsAssembledFromColumns1To72PlusOneSpace() {
        DynamicSqlDriver.Statement s = DynamicSqlDriver.readStatement(InputFile.of(D01_SQL));
        assertThat(s.text()).isEqualTo(pad("UPDATE TRANSACTION_TYPE", 72) + " "
                + pad("   SET TR_DESCRIPTION = 'Payment - SEPA'", 72) + " "
                + pad(" WHERE TR_TYPE = '02'", 72) + " ");
        DynamicSqlDriver.Statement cols = DynamicSqlDriver.readStatement(InputFile.of(List.of(pad("SELECT 1", 72) + "SEQ00010")));
        assertThat(cols.text()).doesNotContain("SEQ00010");
        Harness.Result r = Harness.dynamic(D01_SQL);
        assertThat(r.db().committed.get("02")).isEqualTo("Payment - SEPA"); // not padded
    }

    @Test
    void BR_TT_028_onlyDashDashInColumns1And2IsAComment() {
        DynamicSqlDriver.Statement s = DynamicSqlDriver.readStatement(InputFile.of(List.of(
                "-- comment", "SELECT 1", "  -- indented, passed through", "-- another")));
        assertThat(s.lineCount()).isEqualTo(2);
        assertThat(s.text()).contains("-- indented").doesNotContain("comment");
        assertThat(Harness.dynamic(D01_SQL).line(1)).isEqualTo("CBTRNDYN: 03 SQL LINES READ");
    }

    @Test
    void BR_TT_029_lineCountMessageHasTwoDigits() {
        Harness.Result r = Harness.dynamic(D02_SQL);
        assertThat(r.line(1)).isEqualTo("CBTRNDYN: 01 SQL LINES READ");
    }

    @Test
    void BR_TT_030_statementIsCutAt800CharactersAndTheCountWraps() {
        List<String> eleven = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            eleven.add(pad("LINE" + i, 72));
        }
        DynamicSqlDriver.Statement s = DynamicSqlDriver.readStatement(InputFile.of(eleven));
        assertThat(s.text()).hasSize(800).endsWith(pad("LINE10", 70));
        assertThat(s.lineCount()).isEqualTo(11);
        List<String> hundred = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            hundred.add("X");
        }
        assertThat(DynamicSqlDriver.readStatement(InputFile.of(hundred)).lineCount()).isZero();
    }

    @Test
    void BR_TT_031_prepareFailureSetsRc8AndSkipsExecute() {
        InMemoryDb2Session db = InMemoryDb2Session.seeded();
        db.prepareSqlcode = -400;
        Harness.Result r = Harness.run(new DsnCommand("CBTRNDYN", null), db, List.of(), D02_SQL);
        assertThat(r.line(2)).isEqualTo("CBTRNDYN: PREPARE FAILED SQLCODE -0000000400");
        assertThat(db.executed).isEmpty();
        assertThat(r.line(3)).isEqualTo("CBTRNDYN: END");
        assertThat(r.rc()).isEqualTo(8);
    }

    @Test
    void BR_TT_032_executeSqlcodeIsAlwaysShownAndOnlyNegativeSetsRc8() {
        Harness.Result r = Harness.dynamic(D02_SQL);
        assertThat(r.line(2)).isEqualTo("CBTRNDYN: EXECUTE SQLCODE +0000000000");
        assertThat(r.db().committed).doesNotContainKey("03");
        assertThat(r.rc()).isZero();

        InMemoryDb2Session db = InMemoryDb2Session.seeded();
        db.scripted.put("SELECT 1", t -> 100);
        Harness.Result plus100 = Harness.run(new DsnCommand("CBTRNDYN", null), db, List.of(), List.of("SELECT 1"));
        assertThat(plus100.line(2)).isEqualTo("CBTRNDYN: EXECUTE SQLCODE +0000000100");
        assertThat(plus100.rc()).isZero();
    }

    @Test
    void BR_TT_033_badStatementDoesNotStopTheJob() {
        Harness.Result r = Harness.dynamic(D03_SQL, "U01Purchase - updated");
        assertThat(r.line(2)).isEqualTo("CBTRNDYN: EXECUTE SQLCODE -0000000400");
        assertThat(r.line(3)).isEqualTo("CBTRNDYN: CALLING COBTUPDT");
    }

    @Test
    void BR_TT_034_dynamicCallOfTheProgramNamedInParms() {
        DsnCommand cmd = DsnCommand.fromJcl(TestRepo.root().resolve("app/app-transaction-type-db2/jcl/MNTTRDYN.jcl"));
        assertThat(cmd).isEqualTo(new DsnCommand("CBTRNDYN", "COBTUPDT"));
        assertThat(ParmArea.of("COBTUPDT")).isEqualTo(new ParmArea(8, "COBTUPDT"));
        Harness.Result r = Harness.run(cmd, InMemoryDb2Session.seeded(), List.of("A04Authorization"), D01_SQL);
        assertThat(r.sysout().subList(3, 8)).containsExactly("CBTRNDYN: CALLING COBTUPDT", "OPEN FILE OK",
                Harness.processing("A04Authorization"), "ADDING RECORD", "RECORD INSERTED SUCCESSFULLY");
        // a PARMS value longer than 8 is cut to WS-PGM-NAME PIC X(08)
        Harness.Result longName = Harness.run(new DsnCommand("CBTRNDYN", "COBTUPDTX"), InMemoryDb2Session.seeded(), List.of(), D02_SQL);
        assertThat(longName.line(3)).isEqualTo("CBTRNDYN: CALLING COBTUPDT");
        assertThat(Harness.programs().resolve("COBTUPDT")).isInstanceOf(TransactionTypeMaintenance.class);
    }

    @Test
    void BR_TT_034_unknownProgramEndsTheRunUnitWithRc1() {
        Harness.Result r = Harness.run(new DsnCommand("CBTRNDYN", "NOSUCHPG"), InMemoryDb2Session.seeded(), List.of(), D02_SQL);
        assertThat(r.line(3)).isEqualTo("CBTRNDYN: CALLING NOSUCHPG");
        assertThat(r.sysout().get(r.sysout().size() - 1)).isEqualTo("DSNRUN: COMMIT AT END OF TASK");
        assertThat(r.rc()).isEqualTo(DsnRunner.RC_CALL_FAILED);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ProgramRegistry.of().resolve("X"))
                .isInstanceOf(ProgramNotFoundException.class);
    }

    @Test
    void BR_TT_035_noParmsMeansNoCall() {
        Harness.Result r = Harness.run(new DsnCommand("CBTRNDYN", null), InMemoryDb2Session.seeded(), List.of("A04X"), D02_SQL);
        assertThat(r.sysout()).containsExactly("DSNRUN: DSN ATTACHED, RUNNING CBTRNDYN", "CBTRNDYN: 01 SQL LINES READ",
                "CBTRNDYN: EXECUTE SQLCODE +0000000000", "CBTRNDYN: END", "DSNRUN: COMMIT AT END OF TASK");
        assertThat(r.rc()).isZero();
        assertThat(DsnCommand.parse("  DSN SYSTEM(DAZ1)\n  RUN PROGRAM(CBTRNDYN) PLAN(CARDDEMO)\n  END\n").parms()).isNull();
    }

    @Test
    void BR_TT_036_endIsNeverShownAfterCallingCobtupdt() {
        for (List<String> sql : List.of(D01_SQL, D02_SQL, D03_SQL)) {
            Harness.Result r = Harness.dynamic(sql, "A04Authorization");
            assertThat(r.sysout()).doesNotContain("CBTRNDYN: END");
            assertThat(r.sysout().get(r.sysout().size() - 1)).isEqualTo("DSNRUN: COMMIT AT END OF TASK");
        }
    }

    @Test
    void BR_TT_037_stepRcIsTheLastValueMovedToReturnCode() {
        Harness.Result d03 = Harness.dynamic(D03_SQL, "U01Purchase - updated");
        assertThat(d03.rc()).isEqualTo(4); // CBTRNDYN's 8 overwritten by COBTUPDT's 4
        Harness.Result cleanCall = Harness.dynamic(D03_SQL, "*  nothing to do");
        assertThat(cleanCall.rc()).isEqualTo(8); // derived from the source: COBTUPDT never resets RETURN-CODE
    }

    @Test
    void BR_TT_038_dynamicSqlAndFileUpdatesShareOneUnitOfWork() {
        Harness.Result r = Harness.dynamic(D01_SQL, "A04Authorization");
        assertThat(r.db().committed).containsExactly(
                java.util.Map.entry("01", "Purchase"), java.util.Map.entry("02", "Payment - SEPA"),
                java.util.Map.entry("03", "Credit"), java.util.Map.entry("04", pad("Authorization", 50)));
        assertThat(r.rc()).isZero();
        // a later failure in COBTUPDT loses the dynamic change too
        Harness.Result lost = Harness.dynamic(D01_SQL, "A01Purchase");
        assertThat(lost.db().committed.get("02")).isEqualTo("Payment");
    }
}
