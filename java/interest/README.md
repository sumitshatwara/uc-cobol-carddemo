# Interest slice: INTCALC (CBACT04C) on Spring Batch

Java 21 / Spring Batch port of the monthly interest job `app/jcl/INTCALC.jcl`
(`//STEP15 EXEC PGM=CBACT04C,PARM='2022071800'`).  Chain `interest` in `tools/runjcl/chains.json`,
listed in `parity-chains`.  Prime directive: match the legacy logic, do not improve it.

## Design

| Piece | Role |
|---|---|
| `InterestChainHandler` | `ChainHandler` for chain `interest`: launches `intCalcJob`, records rc.json (the setup jobs' STEP05..STEP30 merged into INTCALC, STEP15 = program RC), writes `AWS.M2.CARDDEMO.SYSTRAN.G0001V00` (CVTRA05Y, write order) and unloads `AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS` (CVACT01Y, key order). |
| `IntCalcJobConfig` | Spring Batch job `intCalcJob` with one step `STEP15` (a tasklet). |
| `IntCalcStep15Tasklet` | Runs the program once inside the step.  A user abend does not throw: the step ends with exit code `ABEND` so rewrites already done stay committed (BR-INT-048) and the handler records RC 12. |
| `Cbact04c` | Line-by-line port of the PROCEDURE DIVISION.  Paragraphs are methods with the same names; working-storage fields keep their PIC limits (`CobolNumeric.fit`).  SYSOUT goes through one `display` call per COBOL `DISPLAY`. |
| `IntCalcFiles` | Port for the five DDs (TCATBALF, XREFFILE, DISCGRP, ACCTFILE, TRANSACT).  Every operation returns the two-character file status the COBOL tests, so each status branch is reproduced literally. |
| `JdbcIntCalcFiles` | The port over PostgreSQL (`carddemo.tran_cat_balance` read `ORDER BY` key, `account`, `card_xref` by account id, `disclosure_group`).  One UPDATE per REWRITE. |
| `ui/src/slices/interest` | Run panel for chain `interest`: per account and category the balance, group and rate (DEFAULT fallback shown), exact `bal × rate / 1200`, the truncated interest with sub-cent remainders highlighted, the Java SYSTRAN amount, the balance before and after, and whether the account was updated (the last account is flagged "not updated - legacy end-of-file behaviour"). |

The loop is the COBOL loop: `while (!endOfFile) { if (!endOfFile) { read; ... } else { updateAccount(); } }`.
The condition is tested before each iteration, so the `else` branch is unreachable, as in CBACT04C.

Interest: `monthlyInterest = fit(balance × rate / 1200, 9, 2)`, i.e. the exact quotient truncated toward
zero to cents (`RoundingMode.DOWN`), high-order digits beyond PIC S9(09) dropped.  No ROUNDED, no ON SIZE ERROR.

## Tests

| Layer | Where | Count |
|---|---|---|
| Unit (no database) | `src/test/java/com/carddemo/interest/Cbact04cRulesTest` | 57 (every BR-INT id has at least one; method names start `BR_INT_NNN_`) |
| Unit, golden files in memory | `GoldenFixtureTest` | 7 (i01..i07: SYSOUT, SYSTRAN and ACCTDATA line for line) |
| Regression | `make parity-java CHAIN=interest` | 7/7 cases PASS with `tools/parity/compare.py` |
| Functional | `ui/e2e/interest.spec.ts` | 3 Playwright journeys |

The unit tests use `FakeFiles`, an in-memory fake of the five datasets that can force any file status
(open/read/rewrite/write/close errors) and logs every I/O call so ordering rules can be asserted.

## Business-rule coverage

| Rule | Implementation | Test(s) |
|---|---|---|
| BR-INT-001 | `Cbact04c.procedureDivision` | `BR_INT_001_startAndEndBannersFrameTheRun` |
| BR-INT-002 | `Cbact04c.JCL_PARM, transactionId` | `BR_INT_002_parmDateIsTheTranIdPrefixNotTheTimestamp` |
| BR-INT-003 | `procedureDivision (open order), IntCalcFiles.Dd` | `BR_INT_003_fiveFilesAreOpenedInOrderAndClosedAtTheEnd` |
| BR-INT-004 | `Cbact04c.openOrAbend (tcatbalfOpen..tranfileOpen)` | `BR_INT_004_openFailureShowsFileSpecificMessageAndAbends` |
| BR-INT-005 | `JdbcIntCalcFiles.open(TCATBALF) ORDER BY key, tcatbalfGetNext` | `BR_INT_005_balancesAreProcessedInAscendingKeyOrder` |
| BR-INT-006 | `Cbact04c.tcatbalfGetNext` | `BR_INT_006_endOfFileStatus10StopsTheLoop` |
| BR-INT-007 | `Cbact04c.tcatbalfGetNext` | `BR_INT_007_balanceReadErrorAbends` |
| BR-INT-008 | `Cbact04c.balanceRecordImage` | `BR_INT_008_eachBalanceRecordIsEchoedAsItsRaw50ByteImage` |
| BR-INT-009 | `Cbact04c.procedureDivision (account break)` | `BR_INT_009_accountBreakRewritesPreviousAccountThenReadsTheNewOne` |
| BR-INT-010 | `Cbact04c.procedureDivision (WS-FIRST-TIME)` | `BR_INT_010_firstRecordDoesNotTriggerAnUpdate` |
| BR-INT-011 | `Cbact04c.procedureDivision (WS-TOTAL-INT reset)` | `BR_INT_011_interestAccumulatorIsResetAtEveryAccountBreak` |
| BR-INT-012 | `Cbact04c.getAcctData, JdbcIntCalcFiles.readAccount` | `BR_INT_012_accountIsReadByTheBalanceAccountIdAndItsGroupDrivesTheRate` |
| BR-INT-013 | `Cbact04c.getAcctData` | `BR_INT_013_missingAccountAbends` |
| BR-INT-014 | `Cbact04c.getXrefData, JdbcIntCalcFiles.readXrefByAcct` | `BR_INT_014_cardIsLookedUpByAccountIdOnTheAlternateKey` |
| BR-INT-015 | `Cbact04c.getXrefData` | `BR_INT_015_missingCardCrossReferenceAbends` |
| BR-INT-016 | `JdbcIntCalcFiles.rewriteAccount (only accounts on TCATBALF)` | `BR_INT_016_accountsWithoutBalanceRowsAreUntouched` |
| BR-INT-017 | `Cbact04c.getInterestRate, JdbcIntCalcFiles.readRate` | `BR_INT_017_rateKeyIsGroupTypeAndCategory` |
| BR-INT-018 | `Cbact04c.getInterestRate, getDefaultIntRate` | `BR_INT_018_missingGroupFallsBackToDefaultKeepingTypeAndCategory` |
| BR-INT-019 | `Cbact04c.getInterestRate` | `BR_INT_019_accountInDefaultGroupUsesItWithoutFallbackMessages` |
| BR-INT-020 | `Cbact04c.getInterestRate` | `BR_INT_020_disclosureGroupReadErrorAbends` |
| BR-INT-021 | `Cbact04c.getDefaultIntRate` | `BR_INT_021_missingDefaultRowAbendsWithoutFurtherFallback` |
| BR-INT-022 | `Cbact04c.procedureDivision (DIS-INT-RATE = 0)` | `BR_INT_022_zeroRateCategoriesAreSkipped` |
| BR-INT-023 | `Cbact04c.monthlyInterest` | `BR_INT_023_monthlyInterestIsBalanceTimesRateOver1200` |
| BR-INT-024 | `Cbact04c.monthlyInterest (RoundingMode.DOWN)` | `BR_INT_024_interestIsTruncatedNotRounded`, `BR_INT_024_categoryBreakdownKeepsTheExactQuotientForTheConsole` |
| BR-INT-025 | `Cbact04c.monthlyInterest` | `BR_INT_025_nonTieSubCentRemaindersAreDroppedAboveAndBelowHalf` |
| BR-INT-026 | `Cbact04c.monthlyInterest` | `BR_INT_026_negativeBalancesProduceNegativeInterestTruncatedTowardZero` |
| BR-INT-027 | `Cbact04c.computeInterest` | `BR_INT_027_interestIsAccumulatedPerAccountAndAppliedAtTheBreak` |
| BR-INT-028 | `Cbact04c.procedureDivision (only rate tested)` | `BR_INT_028_zeroBalanceWithNonZeroRateStillPostsAZeroTransaction` |
| BR-INT-029 | `Cbact04c.monthlyInterest / updateAccount (CobolNumeric.fit)` | `BR_INT_029_overflowSilentlyDropsHighOrderDigits` |
| BR-INT-030 | `Cbact04c.computeFees (empty)` | `BR_INT_030_feesStepIsAStubThatChangesNothing` |
| BR-INT-031 | `Cbact04c.computeInterest / writeTx` | `BR_INT_031_oneTransactionPerNonZeroRateBalance` |
| BR-INT-032 | `Cbact04c.transactionId` | `BR_INT_032_tranIdIsParmPlusRunWideSixDigitSequence` |
| BR-INT-033 | `Cbact04c.writeTx` | `BR_INT_033_interestTransactionsAreType01Category0005SourceSystem` |
| BR-INT-034 | `Cbact04c.writeTx` | `BR_INT_034_descriptionIsIntForAcPlusElevenDigitAccountId` |
| BR-INT-035 | `Cbact04c.writeTx, InterestTran.toFields` | `BR_INT_035_amountMerchantAndCardFields` |
| BR-INT-036 | `Cbact04c.writeTx (FrozenClock.db2Timestamp)` | `BR_INT_036_bothTimestampsComeFromTheFrozenClockInDb2Format` |
| BR-INT-037 | `Cbact04c.writeTx` | `BR_INT_037_transactionWriteErrorAbends` |
| BR-INT-038 | `InterestTran.toFields + RecordCodec (CVTRA05Y)` | `BR_INT_038_amountsAreZonedWithEbcdicOverpunchSign` |
| BR-INT-039 | `Cbact04c.updateAccount` | `BR_INT_039_accountBreakAddsInterestAndResetsCycleAmounts` |
| BR-INT-040 | `Cbact04c.updateAccount` | `BR_INT_040_accountWithOnlyZeroRateCategoriesIsStillReset` |
| BR-INT-041 | `Cbact04c.updateAccount` | `BR_INT_041_accountRewriteErrorAbends` |
| BR-INT-042 | `Cbact04c.procedureDivision (loop test before ELSE)` | `BR_INT_042_lastAccountOnTheBalanceFileIsNeverUpdated` |
| BR-INT-043 | `Cbact04c.procedureDivision` | `BR_INT_043_emptyBalanceFileWritesOnlyTheBannersAndEndsRc0` |
| BR-INT-044 | `Cbact04c.closeOrAbend` | `BR_INT_044_closeFailureShowsFileSpecificMessageAndAbends` |
| BR-INT-045 | `Cbact04c.formatIoStatus` | `BR_INT_045_fileStatusDisplayFormat` |
| BR-INT-046 | `Cbact04c.run, InterestChainHandler.RC_STEPS` | `BR_INT_046_normalCompletionReturnsRc0AndRcJsonListsTheMergedSetupSteps` |
| BR-INT-047 | `Cbact04c.abendProgram, IntCalcStep15Tasklet` | `BR_INT_047_abendRoutineDisplaysAndEndsWithRc12` |
| BR-INT-048 | `JdbcIntCalcFiles.rewriteAccount (immediate), IntCalcStep15Tasklet (no rethrow)` | `BR_INT_048_rewritesBeforeAnAbendAreNotRolledBack` |

## Legacy behaviour reproduced on purpose (findings)

These are reproduced exactly, not fixed, and are raised for a business decision:

1. **Last account never updated (BR-INT-042).** `PERFORM UNTIL END-OF-FILE = 'Y'` tests the switch before
   the `ELSE PERFORM 1050-UPDATE-ACCOUNT` branch can run, so the final account on TCATBALF keeps its old
   balance and cycle amounts although its interest transactions are written.  Every fixture shows it.
2. **Interest truncated, not rounded (BR-INT-024/025).** 12.355 → 12.35 (i02), 12.357 → 12.35.
   `make parity-naive CASE=i02-tie-half-cent` (half-even) fails on `TRAN-AMT` and `ACCT-CURR-BAL` of
   account 2003 where this port passes.
3. **Negative balances earn negative interest (BR-INT-026)**, truncated toward zero (-12.355 → -12.35).
4. **Misleading DISCGRP open message (BR-INT-004):** `ERROR OPENING DALY REJECTS FILE` kept verbatim;
   the XREF open message is followed directly by the status (`ERROR OPENING CROSS REF FILE35`).
5. **Same `ACCOUNT NOT FOUND:` text for account and cross-reference misses (BR-INT-013/015).**
6. **Alternate key without WITH DUPLICATES (BR-INT-014):** for an account with several cards this port
   uses the first card in card-number order (AIX base-key order after REPRO).  No fixture has more than
   one card per account, so this is not proven against the legacy runtime.
7. **Fixed classification and clock timestamps (BR-INT-033/036):** type 01 / category 0005 / source
   `System`; timestamps from the frozen clock, not the PARM date.
8. **Silent overflow (BR-INT-029)** via `CobolNumeric.fit`: WS-MONTHLY-INT / WS-TOTAL-INT keep 9 integer
   digits, ACCT-CURR-BAL 10.  **No rollback (BR-INT-048):** rewrites before an abend stay; on abend the
   SYSTRAN generation is not written (`DISP=(NEW,CATLG,DELETE)`).  No fixture covers abends.
9. **Dead code:** WS-RECORD-COUNT is counted but never shown; 1400-COMPUTE-FEES is an empty stub (BR-INT-030).
10. **Abend line of the off-host stand-in (BR-INT-047):** after `ABENDING PROGRAM` the harness stub
    `tools/stubs/CEE3ABD.cbl` displays `CEE3ABD: USER ABEND U+0000000999` (checked by compiling the stub
    in the estate container) and ends the step with RC 12; the port prints the same line.
11. **Fixture README wording:** the READMEs of i01, i05 and i06 describe balance updates that the golden
    files (correctly) do not show, because of finding 1.  The expected files are the target; nothing changed.
