# trantype: transaction type maintenance (MNTTRDB2 / MNTTRDYN) on Spring Batch

This module converts the Db2 sub-application `app/app-transaction-type-db2` batch programs:

| Legacy | Java |
|---|---|
| JCL `MNTTRDB2` STEP1 `IKJEFT01` → `DSN RUN PROGRAM(COBTUPDT)` | Spring Batch job `mntTrDb2Job`, tasklet step `STEP1`, chain `trantype-update` |
| JCL `MNTTRDYN` STEP1 `IKJEFT01` → `DSN RUN PROGRAM(CBTRNDYN) PARMS('COBTUPDT')` | Spring Batch job `mntTrDynJob`, tasklet step `STEP1`, chain `trantype-dynamic` |
| `COBTUPDT.cbl` | `TransactionTypeMaintenance` (`@Service`, program id `COBTUPDT`) |
| `CBTRNDYN.cbl` | `DynamicSqlDriver` (`@Service`, program id `CBTRNDYN`) |
| `CALL WS-PGM-NAME` (dynamic CALL) | `ProgramRegistry`: legacy program name → `CardDemoProgram` bean |
| DSN attach + end-of-task commit (`tools/stubs/DSNRUN.cbl`, `DSNEXIT.cbl`) | `DsnRunner` + `DsnStepTasklet` |
| SQLCA / Open COBOL ESQL on PostgreSQL | `Db2Session`, `JdbcDb2Session`, `SqlCodes` |
| — | `GET /api/trantype/types` (`TrantypeController`), console run panel `ui/src/slices/trantype` |

## Design

* **Job and step.** `TrantypeJobsConfig` builds one job per JCL job with one tasklet step `STEP1`.
  `TrantypeUpdateChain` / `TrantypeDynamicChain` are the `ChainHandler` beans. Each one launches its
  job with a `runKey` job parameter that refers to the foundation's `ChainRun`
  (`TrantypeRunContexts`), and then reports the step RC with `run.stepRc(job, "STEP1", rc)`.
* **DSN RUN subcommand.** `DsnStepTasklet` reads `RUN PROGRAM(...) PARMS('...')` from the job's real JCL
  member (`DsnCommand`, which uses the same pattern as `tools/runjcl`). The program name and PARMS are
  therefore not hard-coded.
* **Unit of work.** The tasklet takes one JDBC connection straight from the pool. Autocommit is off
  and there are no savepoints. The connection sits outside the step's transaction, because the step
  uses a `ResourcelessTransactionManager`. `SET LOCAL search_path TO carddemo, public` plays the role
  of the plan qualifier, so unqualified dynamic SQL (`UPDATE TRANSACTION_TYPE ...`) resolves the same
  way it does for the legacy run. `DsnRunner` commits at end of task (after `STOP RUN` or `GOBACK`)
  and displays `DSNRUN: COMMIT AT END OF TASK`.
* **Run unit.** `RunUnit` holds the shared `RETURN-CODE`, SYSOUT, input DDs, the Db2 session and the
  program registry. `STOP RUN` is the `StopRun` exception, which only `DsnRunner` catches. Because of
  this, a program called from CBTRNDYN ends the whole run unit, as it does in COBOL.
* **SQLCODEs.** `SqlCodes` copies the SQLSTATE → SQLCODE table of Open COBOL ESQL 1.4
  (`dblib/ocpgsql.c`): 23505 → -403, a few connection/transaction states have their own codes, and
  everything else → -400. A zero-row UPDATE or DELETE succeeds with 0.
* **Programs are ported paragraph by paragraph.** Working storage is kept as COBOL holds it: an
  80-byte `WS-RETURN-MSG` that is overwritten but never cleared, an 800-byte statement buffer filled
  with `STRING ... WITH POINTER`, and a `PIC 9(02)` line counter.

## Tests

| Layer | Where | Result (this PR) |
|---|---|---|
| Unit (no database) | `src/test/java/com/carddemo/trantype` | 53 tests, 0 failures: 30 COBTUPDT, 13 CBTRNDYN, 10 golden-case replays on an in-memory Db2 fake |
| Regression | `make parity-java CHAIN=trantype-update` / `CHAIN=trantype-dynamic` | 7/7 and 3/3 PASS (SYSOUT, rc.json, both table snapshots) |
| Functional | `ui/e2e/trantype.spec.ts` | 3 journeys passed |

The unit tests use `InMemoryDb2Session`, a fake that follows PostgreSQL's abort-after-error
rule and the ESQL SQLCODE values. `GoldenReplayTest` runs all 10 golden cases through it.

## Business-rule coverage

| BR | Implementation | Test(s) |
|---|---|---|
| BR-TT-001 | `TransactionTypeMaintenance.readRecord`, `State` | `BR_TT_001_recordLayoutActionTypeAndDescription` |
| BR-TT-002 | `TransactionTypeMaintenance.call` (0001-OPEN-FILES) | `BR_TT_002_openFileOkIsTheSecondLine` |
| BR-TT-003 | `TransactionTypeMaintenance.call`, `InputFile` status 35/47 | `BR_TT_003_openFailureDoesNotStopTheProgram` |
| BR-TT-004 | `TransactionTypeMaintenance.readRecord` | `BR_TT_004_everyRecordIsEchoedPaddedTo53` |
| BR-TT-005 | `TransactionTypeMaintenance.call` | `BR_TT_005_emptyInputFileIsNotAnError` |
| BR-TT-006 | `TransactionTypeMaintenance.insertDb` | `BR_TT_006_actionAInsertsANewType` |
| BR-TT-007 | `TransactionTypeMaintenance.insertDb` | `BR_TT_007_insertSuccessMessage` |
| BR-TT-008 | `insertDb` / `updateDb` bind `State.recDesc()` (X(50)) | `BR_TT_008_descriptionIsStoredSpacePaddedTo50` |
| BR-TT-009 | `TransactionTypeMaintenance.updateDb` | `BR_TT_009_actionUUpdatesTheDescriptionByKey` |
| BR-TT-010 | `TransactionTypeMaintenance.updateDb` | `BR_TT_010_updateSuccessMessage` |
| BR-TT-011 | `TransactionTypeMaintenance.updateDb` (+100 branch kept), `SqlCodes` | `BR_TT_011_updateOfMissingKeyOffHostReportsSuccess`, `BR_TT_011_updateOfMissingKeyWithSqlcode100IsAnError` |
| BR-TT-012 | `TransactionTypeMaintenance.deleteDb` | `BR_TT_012_actionDDeletesByKeyAndIgnoresTheDescription` |
| BR-TT-013 | `TransactionTypeMaintenance.deleteDb` | `BR_TT_013_deleteSuccessMessage` |
| BR-TT-014 | `TransactionTypeMaintenance.deleteDb`, `JdbcDb2Session.execute` | `BR_TT_014_deleteOfMissingKeyReportsSuccess`, `BR_TT_014_deleteOfMissingKeyWithSqlcode100WouldBeAnError` |
| BR-TT-015 | `TransactionTypeMaintenance.treatRecord` | `BR_TT_015_commentRecordsAreSkipped` |
| BR-TT-016 | `TransactionTypeMaintenance.treatRecord` | `BR_TT_016_unknownActionCodeIsRejectedAndProcessingContinues` |
| BR-TT-017 | `TransactionTypeMaintenance.sqlError`, `CobolText.editMinus4` | `BR_TT_017_negativeSqlcodeIsAnErrorWithEditedCode`, `BR_TT_017_positiveSqlcodeOtherThan100FallsThroughSilently` |
| BR-TT-018 | `SqlCodes` (23505 → -403) | `BR_TT_018_duplicateAddIsRejectedWithMinus403` |
| BR-TT-019 | `TransactionTypeMaintenance.abend` | `BR_TT_019_errorsDoNotStopTheBatchAndRc4IsSticky` |
| BR-TT-020 | `State.stringIntoReturnMsg` | `BR_TT_020_errorBufferIs80BytesAndNeverCleared` |
| BR-TT-021 | `TransactionTypeMaintenance.treatRecord` (no validation) | `BR_TT_021_typeCodeIsNotValidated` |
| BR-TT-022 | `StopRun`, `DsnRunner.run` | `BR_TT_022_endOfFileClosesAndStopsTheRunUnit` |
| BR-TT-023 | `DsnRunner.run`, `JdbcDb2Session.commitAtEndOfTask` | `BR_TT_023_dsnAttachLinesAndCommitAtEndOfTask`, `BR_TT_023_failedCommitIsReported` |
| BR-TT-024 | `JdbcDb2Session` (no savepoints; COMMIT → ROLLBACK reported as 0) | `BR_TT_024_failedStatementAbortsTheUnitOfWork`, golden replay d03 |
| BR-TT-025 | `SqlCodes` (23503 → -400), `deleteDb` | `BR_TT_025_deleteOfATypeWithCategoriesIsRefused` |
| BR-TT-026 | Flyway `V300__db2_transaction_type_tables.sql`, `SQL_*` constants | `BR_TT_026_tableKeysAndColumnsMatchTheDb2Ddl` |
| BR-TT-027 | `DynamicSqlDriver.readStatement` | `BR_TT_027_statementIsAssembledFromColumns1To72PlusOneSpace` |
| BR-TT-028 | `DynamicSqlDriver.readStatement` | `BR_TT_028_onlyDashDashInColumns1And2IsAComment` |
| BR-TT-029 | `DynamicSqlDriver.call` | `BR_TT_029_lineCountMessageHasTwoDigits` |
| BR-TT-030 | `DynamicSqlDriver.stringWithPointer`, `CobolText.addPic99` | `BR_TT_030_statementIsCutAt800CharactersAndTheCountWraps` |
| BR-TT-031 | `DynamicSqlDriver.runDynamicSql` | `BR_TT_031_prepareFailureSetsRc8AndSkipsExecute` |
| BR-TT-032 | `DynamicSqlDriver.runDynamicSql`, `CobolText.signed10` | `BR_TT_032_executeSqlcodeIsAlwaysShownAndOnlyNegativeSetsRc8` |
| BR-TT-033 | `DynamicSqlDriver.call` | `BR_TT_033_badStatementDoesNotStopTheJob` |
| BR-TT-034 | `DynamicSqlDriver.call`, `ProgramRegistry`, `DsnCommand`, `ParmArea` | `BR_TT_034_dynamicCallOfTheProgramNamedInParms`, `BR_TT_034_unknownProgramEndsTheRunUnitWithRc1` |
| BR-TT-035 | `DynamicSqlDriver.call` | `BR_TT_035_noParmsMeansNoCall` |
| BR-TT-036 | `StopRun` from COBTUPDT | `BR_TT_036_endIsNeverShownAfterCallingCobtupdt` |
| BR-TT-037 | `RunUnit.returnCode` (shared) | `BR_TT_037_stepRcIsTheLastValueMovedToReturnCode` |
| BR-TT-038 | one `Db2Session` per run unit | `BR_TT_038_dynamicSqlAndFileUpdatesShareOneUnitOfWork` |

## Legacy behaviour reproduced on purpose (findings)

These behaviours come from the legacy source or the golden output. They are kept as they are, not fixed.

1. **A zero-row DELETE (and UPDATE) succeeds (F1).** The source has a `+100` → `No records found.` /
   RC 4 branch, and Db2 on z/OS would take it. Open COBOL ESQL reports SQLCODE 0 instead (golden `t05`),
   so the Java port does too. The +100 branch is still ported and unit-tested.
2. **Off-host SQLCODEs (F9).** A duplicate key gives -403 and an unknown column gives -400, as the
   ESQL driver reports them. Db2 would give -803 and -206.
3. **PostgreSQL aborts the whole unit of work after one failed statement (F5, BR-TT-024).** Every
   later statement fails with -400. `DSNRUN: COMMIT AT END OF TASK` is still printed, but nothing is
   kept. In MNTTRDB2, valid records that follow a failed one are lost as well. Db2 would roll back only
   the failing statement. There are no savepoints, as the spec requires. Changing this is a business
   decision.
4. **RC 4 is sticky, and RC 8 can be lowered to 4 (F4, BR-TT-037).** In d03, CBTRNDYN's 8 is
   overwritten by COBTUPDT's 4.
5. **The error buffer is never cleared (F3, BR-TT-020).** A short message after a longer one keeps the
   tail of the earlier text.
6. **STOP RUN in the called program (F6).** `CBTRNDYN: END` is never displayed after COBTUPDT is called.
7. **DYNSQL limits are silent (F7).** The statement is cut at 800 characters (10 full cards + 70),
   the line counter wraps at 100, and only `--` in columns 1-2 is a comment.
8. **No input validation (F8).** Type codes are not checked, lowercase actions are rejected, and
   COBTUPDT stores descriptions padded to 50 characters, while seed and dynamic-SQL rows are not padded.
9. **Open failure (F2, derived from the source, not executed).** After `OPEN FILE NOT OK` the read
   loop never ends. Java reproduces the loop output and cancels the step after 100,000 iterations, in
   place of a mainframe TIME limit.
10. **Unchecked CALL (F10, derived from the source, not executed).** If PARMS names an unknown program,
    libcob ends the run unit with status 1 after running the exit procedure. Java logs the error,
    commits at end of task and ends with RC 1. A PARMS value longer than 8 characters is cut to 8.

## Foundation notes

No foundation change was needed. The module adds `spring-web` (managed version) to its own pom for
`TrantypeController`. `java/app` already depends on `carddemo-trantype`.
