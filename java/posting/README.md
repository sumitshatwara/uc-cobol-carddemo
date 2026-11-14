# posting: POSTTRAN / TRANREPT (CBTRN02C, CBTRN03C) on Spring Batch

Chain `posting` of `tools/runjcl/chains.json`, implemented by `PostingChainHandler`
(`ChainHandler` bean). Fixture cases p01-p07, gate `make parity-java CHAIN=posting`.

## Design

| Legacy | Java |
|---|---|
| JCL job POSTTRAN | Spring Batch job `postTranJob` |
| STEP15 `PGM=CBTRN02C` | chunk-oriented step `STEP15`, chunk size 1: reader = DALYTRAN records (`ListItemReader` of `DailyTranRecord`), processor = `PostTranProgram.validate` (`TransactionValidator`), writer = `PostTranProgram.write` (`TransactionPoster` or a reject); step listener = start/end DISPLAYs, RC, DALYREJS |
| JCL job TRANREPT | Spring Batch job `tranReptJob` |
| PRC001 (REPROC, IDCAMS REPRO) | tasklet `PRC001`: `transact` in key order encoded as 350-byte CVTRA05Y records |
| STEP05R `PGM=SORT` | tasklet `STEP05R`: `TranReptSort` (INCLUDE on bytes 305-314, stable SORT on bytes 263-278, byte comparison as in the harness) |
| STEP10R `PGM=CBTRN03C` | tasklet `STEP10R`: `TransactionReport` |
| VSAM ACCTFILE / XREFFILE / TCATBALF / TRANFILE | `PostingStore` port, `JdbcPostingStore` (carddemo tables); `InMemoryPostingStore` in unit tests |
| CARDXREF / TRANTYPE / TRANCATG reads in CBTRN03C | `ReportLookups` port, `JdbcReportLookups` |
| `9999-ABEND-PROGRAM` / CEE3ABD | `CobolAbend`: abend DISPLAYs, then step RC 12; an abended POSTTRAN stops the chain |

The program logic (`post/`, `report/`) is plain Java with no Spring or database dependency, so every
rule is unit-tested against in-memory files. `GoldenFixtureTest` also runs both programs on all seven
fixture cases in memory and compares SYSOUT, rejects and the report with the golden files.

Outputs: `TRANSACT.VSAM.KSDS`, `ACCTDATA.VSAM.KSDS`, `TCATBALF.VSAM.KSDS` (table unloads in key
order), `DALYREJS.G0001V00` (layout `REJTRAN`, LRECL 430), `TRANREPT.G0001V00` (text, 133). SYSOUT
`POSTTRAN.STEP15`, `TRANREPT.STEP10R`. In `rc.json`, the setup-job steps the harness merges into POSTTRAN
(CLCIFIL, OPCIFIL, STEP05, STEP10, STEP20, STEP25, STEP30) are recorded as 0. No Flyway migration is needed.

Console: `ui/src/slices/posting/index.tsx` adds a run panel for chain `posting`: STEP15 RC, decoded
reject records (reason code and text) and the report listing. Journeys: `ui/e2e/posting.spec.ts`.

## Business-rule coverage (BR-POST-001 .. BR-POST-044)

| BR | Rule | Implementation | Unit test |
|---|---|---|---|
| BR-POST-001 | Posting job runs CBTRN02C against six datasets | `PostingChainHandler.postTranJob` | `PostTranRulesTest.BR_POST_001_postTranJclRunsCbtrn02cWithTheSixDatasets` |
| BR-POST-002 | Every daily record is read sequentially until end of file | `PostTranProgram.validate` (reader = DALYTRAN) | `PostTranRulesTest.BR_POST_002_everyRecordIsReadAndCounted` |
| BR-POST-003 | Validation order: card, then account, then limit and expiry | `TransactionValidator.validate` | `PostTranRulesTest.BR_POST_003_cardIsCheckedBeforeTheAccount` |
| BR-POST-004 | Reject 100: card number not in cross-reference | `TransactionValidator.validate` | `PostTranRulesTest.BR_POST_004_unknownCardRejects100` |
| BR-POST-005 | Reject 101: account in cross-reference but not on account master | `TransactionValidator.validate` | `PostTranRulesTest.BR_POST_005_missingAccountRejects101WithoutLimitOrExpiryChecks` |
| BR-POST-006 | Credit-limit check uses cycle amounts, not the current balance | `TransactionValidator.withinLimit` | `PostTranRulesTest.BR_POST_006_limitCheckUsesCycleAmountsNotTheBalance` |
| BR-POST-007 | Reject 102: over-limit transaction | `TransactionValidator.validate` | `PostTranRulesTest.BR_POST_007_overLimitRejects102` |
| BR-POST-008 | Limit check sees earlier postings in the same run | `JdbcPostingStore.findAccount` per record, chunk size 1 | `PostTranRulesTest.BR_POST_008_limitCheckSeesEarlierPostingsOfTheSameRun` |
| BR-POST-009 | Reject 103: transaction after account expiry | `TransactionValidator.notExpired` | `PostTranRulesTest.BR_POST_009_expiryBeforeOriginDateRejects103_onTheDayIsAccepted` |
| BR-POST-010 | Expiry reason overrides over-limit reason | `TransactionValidator.validate` | `PostTranRulesTest.BR_POST_010_expiryReasonOverridesOverLimit` |
| BR-POST-011 | Blank or zero expiry date rejects every transaction | `TransactionValidator.notExpired` | `PostTranRulesTest.BR_POST_011_blankOrZeroExpiryRejectsEveryTransaction` |
| BR-POST-012 | Reject record layout: input record plus 80-byte trailer | `PostTranProgram.rejectRecord`, `Validation.trailer` | `PostTranRulesTest.BR_POST_012_rejectRecordIsTheInputPlusAn80ByteTrailer` |
| BR-POST-013 | Rejects are counted and the posting continues | `PostTranProgram.write` | `PostTranRulesTest.BR_POST_013_rejectsAreCountedAndPostingContinues` |
| BR-POST-014 | Return code 4 when any transaction is rejected | `PostTranProgram.finish` | `PostTranRulesTest.BR_POST_014_returnCode4OnlyWhenSomethingWasRejected` |
| BR-POST-015 | Posting SYSOUT messages and counters | `PostTranProgram.start/finish` | `PostTranRulesTest.BR_POST_015_sysoutMessagesAndCounters` |
| BR-POST-016 | Posted transaction copies the daily record field by field | `TransactionPoster.toTransaction` | `PostTranRulesTest.BR_POST_016_postedTransactionCopiesTheDailyFields` |
| BR-POST-017 | Processing timestamp is the run clock, not the input value | `TransactionPoster.toTransaction` (`FrozenClock.db2Timestamp`) | `PostTranRulesTest.BR_POST_017_processingTimestampIsTheRunClock` |
| BR-POST-018 | Transaction-category balance key | `TcatKey`, `TransactionPoster.post` | `PostTranRulesTest.BR_POST_018_categoryBalanceKeyIsAccountTypeCategory` |
| BR-POST-019 | Create a category balance record when none exists | `TransactionPoster.updateTcatBal` | `PostTranRulesTest.BR_POST_019_missingCategoryBalanceIsCreatedWithTheAmount` |
| BR-POST-020 | Add to an existing category balance | `TransactionPoster.updateTcatBal` | `PostTranRulesTest.BR_POST_020_existingCategoryBalanceIsAddedTo` |
| BR-POST-021 | Category balance read error abends | `TransactionPoster.updateTcatBal` | `PostTranRulesTest.BR_POST_021_categoryBalanceWriteFailureAbends` |
| BR-POST-022 | Account balance and cycle totals updated by sign of amount | `TransactionPoster.updateAccount` | `PostTranRulesTest.BR_POST_022_balanceAndCycleTotalsUpdatedBySign` |
| BR-POST-023 | Payments raise the limit-check amount | `TransactionValidator.withinLimit` | `PostTranRulesTest.BR_POST_023_paymentsRaiseTheLimitCheckAmount` |
| BR-POST-024 | Account rewrite failure sets reason 109 but is not a reject | `TransactionPoster.post` | `PostTranRulesTest.BR_POST_024_accountRewriteFailureSets109ButStillPosts` |
| BR-POST-025 | Posting order: category balance, account, transaction | `TransactionPoster.post` | `PostTranRulesTest.BR_POST_025_postingOrderCategoryThenAccountThenTransaction` |
| BR-POST-026 | Transaction master is opened for output | `PostingStore.openTransactOutput` | `PostTranRulesTest.BR_POST_026_transactionMasterIsOpenedForOutput` |
| BR-POST-027 | Empty daily file posts nothing and ends RC 0 | `PostTranProgram.runAll/finish` | `PostTranRulesTest.BR_POST_027_emptyDailyFilePostsNothingRc0` |
| BR-POST-028 | File open, close and I/O errors abend with a status line | `Cobol.abend`, `CobolAbend`, `PostingChainHandler.launch` | `PostTranRulesTest.BR_POST_028_ioErrorsDisplayStatusAndAbendRc12` |
| BR-POST-029 | No rounding; amounts carry exactly two decimals | `CobolNumeric.fit` in validator/poster | `PostTranRulesTest.BR_POST_029_noRoundingAndHighOrderTruncation` |
| BR-POST-030 | Report step 1: unload the transaction master | `PostingChainHandler.unloadTransact` (step PRC001) | `TranReptRulesTest.BR_POST_030_reproUnloadsTheTransactionMasterInKeyOrder` |
| BR-POST-031 | Report step 2: sort by card number and filter by processing date | `TranReptSort.sort` (step STEP05R) | `TranReptRulesTest.BR_POST_031_sortFiltersOnProcessingDateAndSortsStablyByCard` |
| BR-POST-032 | Report date range comes from the DATEPARM record | `TransactionReport.readDateParm` | `TranReptRulesTest.BR_POST_032_dateRangeComesFromDateparm` |
| BR-POST-033 | Out-of-range processing date ends the report loop | `TransactionReport.run` / `inRange` | `TranReptRulesTest.BR_POST_033_outOfRangeDateEndsTheLoopWithoutTotals` |
| BR-POST-034 | Each reported transaction is echoed to SYSOUT | `TransactionReport.reportTransaction` | `TranReptRulesTest.BR_POST_034_eachReportedRecordIsEchoedToSysout` |
| BR-POST-035 | Report headers on the first detail and on each new page | `TransactionReport.writeHeaders` | `TranReptRulesTest.BR_POST_035_headersBeforeTheFirstDetail` |
| BR-POST-036 | Detail line format | `TransactionReport.detailLine`, `NumericEdit.edit` | `TranReptRulesTest.BR_POST_036_detailLineFormat` |
| BR-POST-037 | Zero amounts print as blanks | `NumericEdit.edit` | `TranReptRulesTest.BR_POST_037_zeroAmountsPrintAsBlanks` |
| BR-POST-038 | Lookups for report descriptions abend when missing | `TransactionReport.reportTransaction` | `TranReptRulesTest.BR_POST_038_missingLookupsAbend` |
| BR-POST-039 | Account totals break on card number | `TransactionReport.writeAccountTotals` | `TranReptRulesTest.BR_POST_039_accountTotalsBreakOnCardNumber` |
| BR-POST-040 | Page break every 20 lines by exact multiple | `TransactionReport.writeTransactionReport` | `TranReptRulesTest.BR_POST_040_pageBreakWhenTheLineCounterIsAMultipleOf20` |
| BR-POST-041 | Page totals roll into the grand total | `TransactionReport.writePageTotals/writeGrandTotals` | `TranReptRulesTest.BR_POST_041_pageTotalsRollIntoTheGrandTotal` |
| BR-POST-042 | End of file adds the last amount again | `TransactionReport.endOfFile` | `TranReptRulesTest.BR_POST_042_endOfFileAddsTheLastAmountAgain` |
| BR-POST-043 | Last account's total is never printed | `TransactionReport.endOfFile` | `TranReptRulesTest.BR_POST_043_lastAccountTotalIsNeverPrinted` |
| BR-POST-044 | Report SYSOUT start and end messages | `TransactionReport.run` (STEP10R RC 0) | `TranReptRulesTest.BR_POST_044_startAndEndMessagesAndNoReturnCode` |
## Legacy behaviour reproduced on purpose (findings)

These look wrong, but the golden files show them, so the Java code reproduces them. They are listed here
for a business decision, not fixed.

1. **Limit check uses cycle amounts** (BR-POST-006/023): `cyc_credit - cyc_debit + amount` against the
   limit. Cycle debit holds negative amounts, so payments *raise* the tested amount. The current balance is ignored.
2. **Expiry overrides over-limit** (BR-POST-010): both checks run; the reject only carries reason 103.
3. **Blank or zero expiry date rejects everything** (BR-POST-011): the comparison is alphanumeric.
4. **Account rewrite failure sets reason 109 but posts anyway** (BR-POST-024): no reject, no RC change.
5. **TRANSACT is opened OUTPUT** (BR-POST-026): only the run's postings remain in the transaction master.
6. **TRANREPT end of file adds the last amount a second time** (BR-POST-042) to the page (and grand)
   total, after DISPLAYing `TRAN-AMT` and `WS-PAGE-TOTAL` (signed DISPLAY form: 11 digits and a
   trailing sign, e.g. `00000001575-`). p01: grand total -26.25 instead of -10.50.
7. **No account total for the last card** (BR-POST-043), and account totals break on card number, not account id.
8. **Out-of-range processing date ends the report** (BR-POST-033): `NEXT SENTENCE` leaves the whole
   read loop with no totals. With an empty transaction file the WORKING-STORAGE record (spaces) is out
   of range, so p07 prints no headers and no totals.
9. **Page break only on an exact multiple of 20** (BR-POST-040). Account-total lines add 2 to the
   counter, so it can step over a multiple and pages can run longer than 20 lines.
10. **SORT date range is hard-coded in TRANREPT.jcl** (2022-01-01 .. 2022-07-06), separate from DATEPARM (BR-POST-031/032).
11. **TRANREPT ends RC 0** even when POSTTRAN had rejects (BR-POST-044).

Not covered by a fixture (unit-tested only): abend paths (RC 12), page breaks, out-of-range dates and
blank expiry dates. The fixture cases in the regression layer cover reasons 100-103, category creation,
negative amounts and the empty day.
