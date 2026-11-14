# CardDemo COBOL → Java 21: technical specification

Status: approved for build (Phase 3).  Inputs: the business rules in
`docs/analysis/business-rules/{posting,interest,trantype,online}.md` (204 rules, BR ids below),
the data dictionary `docs/analysis/data-dictionary.md`, the discovery graph `docs/chain-graph.md`,
and the foundation on the integration branch (`java/common`, `java/app`, `ui/`, `java/README.md`).

**Prime directive: match the legacy logic, do not improve it.**  Parity is decided by the estate's
own comparator `tools/parity/compare.py` on the 24 golden fixture cases, field by field through the
copybooks (zoned overpunch, COMP-3, EBCDIC), plus SYSOUT text, `rc.json` and Db2 table snapshots.
A documented quirk is a requirement.  If a rule looks wrong, write it up as a *finding* in the PR and
reproduce it anyway.  Never edit `app/**`, copybooks, `fixtures/*/expected`, `tools/parity/compare.py`;
never run `make record`; never loosen a comparison.

## 1. Target architecture

| Layer | Choice |
|---|---|
| Runtime | Java 21, Spring Boot 3.3.5, Spring Batch 5.1 (job repository in PostgreSQL) |
| Data | PostgreSQL 15 (estate `db` container), database `carddemo_java`, Flyway, JPA `ddl-auto: validate` |
| Batch | one Spring Batch `Job` per legacy JCL job, one `Step` per program step; IDCAMS/SORT/IEBGENER utility steps become steps or are covered by the foundation loader |
| API | Spring MVC REST under `/api` (`java/app`), slice controllers under `/api/<slice>/…` |
| Console | React 18 + Vite + TypeScript (`ui/`), served by the service on :8080 |
| Tests | JUnit 5 (unit), `make parity-java` (regression vs legacy), Playwright (functional) |

Datasets become tables (generated from the copybooks by `tools/javagen/gen_vsam.py`):
`carddemo.account` (CVACT01Y), `card_xref` (CVACT03Y), `tran_cat_balance` (CVTRA01Y),
`disclosure_group` (CVTRA02Y), `tran_type` (CVTRA03Y), `tran_category` (CVTRA04Y), `transact`
(CVTRA05Y, KSDS), `daily_tran` (CVTRA06Y, sequential, `rec_seq`), `fixture_record` (every raw input
line).  Db2: `carddemo.transaction_type`, `carddemo.transaction_type_categor` (Flyway V300).
Text keys use `COLLATE "C"` so `ORDER BY key` equals VSAM key order.

### Run lifecycle (foundation, do not re-implement)

`ChainRunService.run(chain, case, outDir)`: set `FrozenClock` from `chains.json` → truncate every
table in schema `carddemo` → load the case's `input/*.txt` (padded to LRECL) into the tables above →
run the Db2 seed (`input/db/seed.sql`) → call the slice's `ChainHandler.run(ChainRun)` → write
`rc.json`, `sysout/<JOB>.<STEP>.txt`, `db/<table>.csv` (PostgreSQL `COPY … CSV HEADER`, same bytes as
the harness).  The handler writes the output datasets with `run.datasets()`.

## 2. Shared conventions (all slices)

1. **Numbers.** `BigDecimal` at the PIC scale, never `double`.  `COMPUTE`/`MOVE` without `ROUNDED`
   truncates toward zero: `CobolNumeric.truncate(x, scale)` = `RoundingMode.DOWN`.  Keep full
   precision in intermediate results (divide with scale ≥ 10, `RoundingMode.DOWN`), truncate once
   on the store, exactly where COBOL stores.  `ROUNDED` = `HALF_UP`.  Storing into a narrower PIC drops
   high-order digits (`CobolNumeric.fit`), no `ON SIZE ERROR` exists in these programs.  The codec
   throws if asked to encode more decimals than the PIC holds: truncate explicitly.
2. **Signed zoned output.** Signed DISPLAY fields carry an EBCDIC overpunch on the last digit
   (`{ABCDEFGHI` / `}JKLMNOPQR`).  `RecordCodec` does this; in SYSOUT, a `DISPLAY` of a record or zoned
   field prints its raw storage (`CobolNumeric.zonedStorage`), e.g. `0000012355{`.
3. **SYSOUT.** One `run.sysout(job, step).display(...)` per COBOL `DISPLAY`, operands concatenated with
   no separator, exact case and spacing.  A DISPLAYed record prints its full length (trailing spaces
   included, the comparator compares lines exactly).  Db2 chains also print the DSN command
   processor lines of `tools/stubs/DSNRUN.cbl` (`DSNRUN: DSN ATTACHED, RUNNING <PGM>`,
   `DSNRUN: COMMIT AT END OF TASK`).  Copy literal texts from the COBOL source, including misspellings.
4. **Return codes.** `run.stepRc(job, step, rc)` for every step listed in the golden `rc.json`.  The
   harness merges the *setup* jobs' step RCs into the first job's map (INTCALC: STEP05–STEP30; POSTTRAN:
   CLCIFIL, OPCIFIL, STEP05–STEP30); record them as 0.  `RETURN-CODE` set by the program → step RC;
   a user abend (`CEE3ABD`, `9999-ABEND-PROGRAM`) → RC 12 after the program's abend DISPLAYs, and later
   steps do not run.  `maxrc` = highest step RC of the job.
5. **Clock.** Only `FrozenClock` (`db2Timestamp()` = `2022-07-05-10.00.00.000000`, `currentDate16()`).
6. **GDG.** Generations restart per case: `X(+1)` written in a run is `X.G0001V00`.
7. **Ordering.** KSDS output = `ORDER BY` primary key; sequential output = write order.
8. **Transactions.** Batch steps run in one transaction per step unless the rules say otherwise;
   Db2 chains reproduce PostgreSQL's abort-after-error semantics (BR-TT-024) because the goldens do.
9. **Naming.** camelCase Java, snake_case columns = copybook name lower-cased (`ACCT-CURR-BAL` →
   `acct_curr_bal` → `acctCurrBal`).  Packages `com.carddemo.<slice>`.
10. **Traceability.** Every BR id has ≥ 1 JUnit test whose **method name starts with the id in
    snake form**, e.g. `BR_INT_024_interestIsTruncatedNotRounded()`; reference the id in a Javadoc
    `@see` on the implementing method too.  Playwright titles start with `[<slice>]`.
11. **Ownership.** A slice edits only `java/<slice>/**`, `ui/src/slices/<slice>/**`,
    `ui/e2e/<slice>.spec.ts`, its Flyway range and `java/<slice>/README.md`.  It lists its chains in
    `java/<slice>/parity-chains`.  It does not edit the Makefile, CI, `java/common`, `java/app` or the
    console shell; if it needs a foundation change, it says so in its PR.

## 3. Slices

### 3.1 Posting: POSTTRAN (CBTRN02C) + TRANREPT (CBTRN03C) → `java/posting`

*Chain:* `posting` (7 cases p01–p07).  *Flyway:* V100–V199 (likely none needed).
*Rules:* BR-POST-001 … BR-POST-044.

Spring Batch job `postTranJob`, step `STEP15` (CBTRN02C), chunk-oriented over `daily_tran ORDER BY
rec_seq`:
* Reader: daily records.  Processor: validation in the legacy order (BR-POST-003…011: card in
  `card_xref` → 100; account → 101; limit `cyc_credit − cyc_debit + amt > credit_limit` → 102, using
  cycle amounts and seeing earlier postings of the same run (BR-POST-006/008); expiry → 103, overriding
  102 (BR-POST-010); blank expiry rejects).  Writer: for valid records update/insert
  `tran_cat_balance` (BR-POST-018…020), update the account balance and cycle credit/debit by sign
  (BR-POST-022), insert the transaction with `TRAN-PROC-TS` = run clock (BR-POST-016/017), in that order
  (BR-POST-025).  Rejects: the 350-byte input + 80-byte trailer (`REJTRAN` layout: reason code 4 digits +
  text) to `AWS.M2.CARDDEMO.DALYREJS.G0001V00`.  RC 4 if any reject (BR-POST-014).  SYSOUT per BR-POST-015.
* The transaction master is opened OUTPUT (BR-POST-026): `transact` starts with the INIT records loaded
  by the foundation only if the golden output shows them; check `p01` and reproduce exactly.
Job `tranReptJob`: `STEP05R` unload + sort (DFSORT `SORT FIELDS` on card number, `INCLUDE` on processing
date from the JCL SYMNAMES, BR-POST-030/031); `STEP10R` CBTRN03C report: DATEPARM range (BR-POST-032),
133-byte lines (CVTRA07Y), headers, page breaks every 20 lines by exact multiple (BR-POST-040), account
totals on card-number change (BR-POST-039), page and grand totals, **the last amount added twice at EOF
(BR-POST-042)** and **no total for the last account (BR-POST-043)**, zero amounts as blanks
(BR-POST-037), each detail echoed to SYSOUT (BR-POST-034).  `PRC001` is the REPRO step (RC 0).
*Outputs:* `TRANSACT.VSAM.KSDS` (CVTRA05Y), `ACCTDATA.VSAM.KSDS` (CVACT01Y), `TCATBALF.VSAM.KSDS`
(CVTRA01Y), `DALYREJS.G0001V00` (REJTRAN), `TRANREPT.G0001V00` (text, 133).  SYSOUT `POSTTRAN.STEP15`,
`TRANREPT.STEP10R`.
*Console:* run panel `posting` with the decoded reject list (reason code + text per card) and the
report listing.  *E2E:* run `p04-overlimit-reject` → RC 4 on STEP15, one reject with reason 102,
parity PASS.  *Acceptance:* `make parity-java CHAIN=posting` 7/7.

### 3.2 Interest: INTCALC (CBACT04C) → `java/interest`

*Chain:* `interest` (7 cases i01–i07).  *Flyway:* V200–V299.  *Rules:* BR-INT-001 … BR-INT-048.

Job `intCalcJob`, step `STEP15`, a tasklet (control-break logic does not chunk cleanly) that reads
`tran_cat_balance ORDER BY` key and reproduces the PERFORM loop literally:
* PARM date `2022071800` (from `INTCALC.jcl`) is the transaction-id prefix (BR-INT-002); id =
  prefix + 6-digit sequence (BR-INT-032).
* Echo every balance record (BR-INT-008).  Account break (BR-INT-009/010): on a new account id, update
  the *previous* account (add accumulated interest to `ACCT-CURR-BAL`, zero cycle credit/debit,
  BR-INT-039), then read the account (`account`), its card by account id (`card_xref`), reset the
  accumulator.
* Rate: `disclosure_group` by (group, type, category); not found → `DEFAULT` group (BR-INT-018); zero
  rate → no interest and no transaction (BR-INT-022).
* `monthlyInt = truncate(bal × rate / 1200, 2)` (BR-INT-023/024/025, `i02`: 12.355 → 12.35; negative
  balances give negative interest, BR-INT-026).  Write one CVTRA05Y transaction to
  `AWS.M2.CARDDEMO.SYSTRAN.G0001V00` per non-zero-rate balance, fixed type 01 / category 0005, source
  `System`, description `Int. for a/c <acct>`, timestamps from the clock (BR-INT-031…036).
* **End-of-file quirk (BR-INT-042):** at EOF the loop ends before the last account is updated: its
  interest transactions are written, its balance and cycle amounts are not.  Reproduce it.
* Fees stub (BR-INT-030): no effect.  Error paths (BR-INT-004/007/013/015/020/021/037/041/047): exact
  DISPLAY texts, RC 12.
*Outputs:* `SYSTRAN.G0001V00` (CVTRA05Y), `ACCTDATA.VSAM.KSDS` (CVACT01Y).  SYSOUT `INTCALC.STEP15`.
*Console:* run panel `interest`: per account and category, balance, rate, raw `bal × rate / 1200`,
truncated interest (highlight sub-cent remainders), and whether the account balance was updated
(last account flagged "not updated - legacy EOF behaviour").  *E2E:* run `i02-tie-half-cent` → RC 0,
SYSTRAN amount 12.35 for account 2003, parity PASS.  *Acceptance:* 7/7.

### 3.3 Transaction type maintenance: MNTTRDB2 (COBTUPDT) + MNTTRDYN (CBTRNDYN) → `java/trantype`

*Chains:* `trantype-update` (7 cases t01–t07) and `trantype-dynamic` (3 cases d01–d03).
*Flyway:* V300 (tables, already present) – V399.  *Rules:* BR-TT-001 … BR-TT-038.

* `COBTUPDT` as a Spring service `TransactionTypeMaintenance` used by both jobs: read the 53-byte
  `INPFILE` records (raw lines from `run.inputRecords("INPFILE")`), echo `PROCESSING   <record>`,
  dispatch on the action code A/U/D/`*`/other (BR-TT-006…016) with JDBC against
  `carddemo.transaction_type`; description bound **space-padded to 50** (BR-TT-008, as ocesql sends
  it).  SQLCODE mapping reproduced from the goldens: success and zero-row DELETE → 0 (BR-TT-014,
  `t05`), unique violation → −403 (`t06`), statement in an aborted transaction → −400 (`d03`);
  only negative is an error (BR-TT-017); RC 4 sticky (BR-TT-019); the 80-byte error buffer is never
  cleared (BR-TT-020).  STOP RUN ends the run unit (BR-TT-022/036).
* Unit of work: one JDBC transaction per job, committed at end of task (`DSNRUN: COMMIT AT END OF
  TASK`, BR-TT-023).  After a failed statement PostgreSQL aborts the transaction; later statements
  fail with −400 and the commit keeps nothing (BR-TT-024).  Do not add savepoints.
* `CBTRNDYN`: concatenate up to 10 DYNSQL cards, skip `--` comments (BR-TT-027…030), PREPARE/EXECUTE
  in the same transaction (BR-TT-038), message with the SQLCODE as `-0000000400` style
  (BR-TT-032), then **dynamic CALL**: a `ProgramRegistry` (`Map<String, CardDemoProgram>` of Spring
  beans by legacy name) resolves the name from the JCL `PARMS('COBTUPDT')` (BR-TT-034/035).
  The step RC is the last value moved to RETURN-CODE (BR-TT-037: d03 ends 4, not 8).
* Jobs `mntTrDb2Job` / `mntTrDynJob`, one tasklet step `STEP1` each.  No dataset outputs; parity is
  SYSOUT `MNTTRDB2.STEP1` / `MNTTRDYN.STEP1`, `rc.json` and the two table snapshots.
*REST (read-only):* `GET /api/trantype/types` (current table rows).  *Console:* run panel
showing seed vs after-run `transaction_type` rows with added/changed/deleted highlighted.
*E2E:* run `t07-mixed-batch` and `d03-bad-statement`.  *Acceptance:* `make parity-java
CHAIN=trantype-update` 7/7 and `CHAIN=trantype-dynamic` 3/3.

### 3.4 Online and system routines → `java/online` (analysis-only legacy: no regression layer)

CICS, the BMS assembler, HLASM and PL/I **cannot be executed off-host**; nothing in this slice was or
will be executed in legacy form.  The slice is a specification-driven port verified by JUnit tests
derived from the rules and the documented worked examples.  *Flyway:* V400–V499, tables in schema
**`carddemo_online`** (not `carddemo`, which every batch run truncates): `user_security` (CSUSR01Y),
`customer` (CVCUS01Y), `account`, `card_xref`, seeded with synthetic data only.
*Rules:* BR-ONL-001 … BR-ONL-074.

| Legacy | Java | REST |
|---|---|---|
| COSGN00C + COSGN00 map (BR-ONL-001…010) | `SignOnService` | `POST /api/online/signon {userId,password}` → `{userType, next, message}` |
| COMEN01C + COMEN01, COMEN02Y (BR-ONL-011…019) | `MenuService` | `GET /api/online/menu?userType=`; `POST /api/online/menu/select {option,userType}` |
| COACTVWC + COACTVW (BR-ONL-020…027) | `AccountViewService` | `GET /api/online/accounts/{acctId}` (xref → account → customer, legacy messages) |
| COACTUPC + COACTUP (BR-ONL-028…059) | `AccountUpdateService` | `POST /api/online/accounts/{acctId}/changes` (edits, first error wins, BR-ONL-037), `PUT /api/online/accounts/{acctId}` (confirm, optimistic check BR-ONL-055) |
| HLASM COBDATFT (BR-ONL-060…063) | `DateFormatRoutine` (incl. the 20-byte CLC finding) | `POST /api/online/dates/convert` |
| HLASM MVSWAIT (BR-ONL-064) | `WaitRoutine` (sleeps hundredths; injectable no-op in tests) | – |
| PL/I CBFEECAL + CBFEEDRV (BR-ONL-065…074) | `LateFeeCalculator` (DECIMAL FIXED → `BigDecimal`, truncating assignment = `DOWN`, ZERODIVIDE → RC 8) | `POST /api/online/fees` |

PF keys map to an `action` field (`ENTER`, `PF3`, `PF5`, `PF12`); screen messages are returned
verbatim (including the double space in "must  be", F-03).  *Console:* `ui/src/slices/online`:
Sign-on, Menu, Account view, Account update, Late-fee calculator, Date converter.
*Tests:* JUnit per BR-ONL id; the six CBFEECAL worked examples of `docs/LEGACY-LANGUAGES.md` each as a
test (30.86 not 30.87; 5.00 floor; 35.00 cap; GOLD waiver RC 4; ZERODIVIDE RC 8; nothing overdue).
*E2E:* sign on as a synthetic admin → menu → account view of a seeded account; fee calculator example 1.
*Acceptance:* all BR-ONL unit tests green; PR states that no legacy execution exists for this slice.

## 4. Test layers and acceptance

| Layer | Command | Pass criterion |
|---|---|---|
| Unit | `make test-unit` | all green; ≥ 1 test per BR id (method name starts `BR_<SLICE>_NNN_`) |
| Regression | `make parity` then `make parity-java [CHAIN=…]` | legacy 24/24 and Java 24/24; any field difference fails |
| Functional | `make test-e2e` | Playwright journeys per slice green (job run → RC, SYSOUT, outputs, parity PASS) |

"Regression passed" may be claimed only when the legacy chain and the Java job were both executed on
the same fixture input and compared with `tools/parity/compare.py`.

## 5. Out of scope

* **CBEXPORT / CBIMPORT**: do not compile upstream (`EXPORT-SEQUENCE-NUM` is not defined in any shipped
  copybook; the same failure occurs on z/OS without the missing member).  Not converted.
* Other batch programs not in a chain (CBACT01C–03C, CBCUS01C, CBSTM03A/B, CBTRN01C, CSUTLDTC) and
  the remaining CICS screens (cards, bills, reports, users, transaction screens, Db2 online
  COTRTLIC/COTRTUPC).
* Fixing any legacy finding.  Findings are listed in the rules files and in each slice PR for a
  business decision (e.g. the interest EOF quirk, the TRANREPT double-counted last amount, the
  PostgreSQL vs Db2 unit-of-work difference).
* Production concerns (security, scheduling, HA); the console is an operations demo.
