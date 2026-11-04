# CardDemo COBOL -> Java 21: technical specification

Status: design baseline for the Phase 3 conversion. Inputs are the four business-rule catalogues
(`docs/analysis/business-rules/{posting,interest,trantype,online}.md`, 194 rules, PRs #1-#4), the data
dictionary (`docs/analysis/data-dictionary.md`), the chain definitions (`tools/runjcl/chains.json`) and the
24 golden fixture cases under `fixtures/`. The Java code must reproduce the legacy behaviour **including
every rule marked "Finding"**: bugs are documented, not fixed. Each rule ID below is the contract for one or
more JUnit tests.

## 1. Goals and non-goals

* Goal: a Java 21 / Spring Boot 3.3 / Spring Batch implementation whose outputs are field-level identical to
  the legacy golden files for all 24 cases (`make parity-java` = 24/24), plus Java-only equivalents of the
  online (CICS) screens and the PL/I / HLASM routines, verified by unit and browser tests.
* Goal: the operations console (`ui/`) can run any chain/case on Java, show SYSOUT, decoded datasets,
  DB snapshots and RCs, and compare with the legacy golden files.
* Non-goals: changing `app/**`, copybooks, `fixtures/*/expected`, `tools/parity/compare.py`; fixing legacy
  defects; executing CICS, HLASM or PL/I (they cannot run off-host; their Java ports are proven by reading the
  source and by unit tests only).

## 2. Architecture

```
ui/ (React/Vite/TS console) --REST--> java/app (Spring Boot: RunController, CliRunner)
                                        |
                                        v
                         common.run.ChainRunner --> ChainHandler (one per chain, in slice modules)
                                        |                 |
             FixtureLoader (fixture input -> PostgreSQL)   |  Spring Batch job / plain service code
             OutputWriter (tables/records -> datasets, SYSOUT, rc.json, db/*.csv)
                                        v
                        work/parity-java/<chain>/<case>/actual  --> tools/parity/compare.py (unchanged)
```

| Module | Owns | Handler(s) |
|---|---|---|
| `common` | copybook codec (`CopybookParser`, `RecordCodec`), `ChainConfig`, `FrozenClock`, `DatasetCatalog`, `FixtureLoader`, `OutputWriter`, `ChainRunner`, Flyway V1-V99 | - |
| `posting` | CBTRN02C (POSTTRAN), TRANREPT (REPRO + SORT + CBTRN03C), Flyway V100-V199 | `posting` |
| `interest` | CBACT04C (INTCALC), Flyway V200-V299 | `interest` |
| `trantype` | COBTUPDT (MNTTRDB2), CBTRNDYN (MNTTRDYN), Flyway V300-V399 | `trantype-update`, `trantype-dynamic` |
| `online` | COSGN00C, COMEN01C, COACTVWC, COACTUPC, COBDATFT, MVSWAIT, CBFEECAL/CBFEEDRV, Flyway V400-V499 | none (REST services) |
| `app` | Spring Boot entry point, REST, CLI; depends on every module; component scan `com.carddemo` | - |

### 2.1 Run model (foundation contract)

* `ChainRunner.run(chain, case, outDir)` truncates every catalogued table plus `handler.extraTables()` and the
  chain's `database.tables`, loads `fixtures/<chain>/<case>/input/*.txt` through `DatasetCatalog` (inputs with
  no catalog entry, e.g. `DATEPARM`, `INPFILE`, `DYNSQL`, are kept raw: `ctx.fixtureLoader().rawRecords(dsn)`),
  executes the optional `db/seed.sql`, calls the handler and writes `rc.json`.
* The handler writes everything else through `ctx.outputWriter()`:
  * `exportTable(dsn, orderBy)` for KSDS outputs backed by a catalogued table, `writeDataset(dsn, layout,
    lrecl, records)` for record outputs (GDG generations are named with `gdgName(base, 1)` ->
    `...G0001V00`), `writeTextDataset` for print files (TRANREPT, 133 bytes);
  * `writeSysout(job, step, lines)` -> `sysout/<JOB>.<STEP>.txt` exactly as the legacy DISPLAY output;
  * `snapshotTable("carddemo.<table>", order)` -> `db/carddemo.<table>.csv` (COPY ... CSV HEADER, same as
    `record.py`).
* `RunResult` carries the job -> step RC map. The first job's map also lists the setup-job steps (all RC 0)
  exactly as the golden `rc.json` (e.g. POSTTRAN: `CLCIFIL, OPCIFIL, STEP05..STEP30`; TRANREPT: `PRC001,
  STEP05R, STEP10R`; INTCALC: `STEP05..STEP30`; MNTTRDB2 / MNTTRDYN: `STEP1`).
* Clock: `ctx.clock()` is frozen at `2022-07-05 10:00:00.00`; `cobolCurrentDate()` returns
  `2022070510000000+0000`, DB2-format timestamps are built from it.
* Abends: an off-host abend is `RC 12` on the failing step, later steps of that job are not run and not listed
  (copy the golden `rc.json` of the corresponding case when in doubt). U-codes appear only in SYSOUT text.

### 2.2 Data mapping and arithmetic

* Tables, columns and Java types follow `docs/analysis/data-dictionary.md` (column = copybook name in
  snake_case; `numeric(digits,scale)`; text `varchar(n)`; text right-trimmed on decode, space-padded on encode).
* All money is `BigDecimal`. COBOL `COMPUTE`/`ADD` without `ROUNDED` -> `setScale(targetScale,
  RoundingMode.DOWN)` on the receiving field; high-order truncation on overflow is reproduced with
  `remainder(10^integerDigits)` where a rule says so (BR-POST-008, BR-INT-027). Never use `double`.
* Zoned signs: the codec already handles overpunch; never format signed numbers by hand for datasets.
  SYSOUT numbers are formatted to the COBOL edit pictures quoted in each rule (e.g. `PIC -(10)9.99`), with a
  shared helper per slice.
* Strings compared as COBOL does: alphanumeric comparisons are byte-wise on space-padded values (e.g.
  BR-POST-009 compares `ACCT-EXPIRAION-DATE` with `DALYTRAN-ORIG-TS(1:10)` as text).

## 3. Slice designs

### 3.1 Posting (`posting` chain: POSTTRAN + TRANREPT) - BR-POST-001..047

* `PostingHandler` (`chain() = "posting"`, `extraTables()` = its reject table if one is created).
* POSTTRAN/STEP15 `DailyPostingService` (CBTRN02C): read `daily_transaction` in file order; per record
  validate in the legacy order (xref 100 -> account 101 -> limit 102 -> expiry 103, last failure wins:
  BR-POST-003..011); rejects build the 430-byte `REJTRAN` record (350-byte input + 4-digit reason + 76-byte
  text) into `DALYREJS.G0001V00`; accepted records update TCATBAL (create or add, BR-POST-018/019), then the
  account (balance + cycle credit/debit by sign, BR-POST-020; the 109 rewrite reason is ignored,
  BR-POST-021), then write `transaction` (BR-POST-015/016, PROC-TS from the clock). STEP15 RC 4 when any
  reject (BR-POST-014). SYSOUT counters and messages verbatim (BR-POST-001, 025-028).
* TRANREPT: `TransactionReportService` reproduces REPRO + SORT (stable sort by card number within the
  hard-coded JCL window, BR-POST-030..032) and CBTRN03C's report (headers, detail line layout, card-break
  account totals, page break every 20 lines with the never-reset counter, page/grand totals, EOF
  double-count and missing last account total: BR-POST-033..047). Output `TRANREPT.G0001V00`, text,
  LRECL 133; SYSOUT `TRANREPT.STEP10R`.
* Outputs: `TRANSACT.VSAM.KSDS` (order `tran_id`), `ACCTDATA.VSAM.KSDS` (order `acct_id`),
  `TCATBALF.VSAM.KSDS` (key order), `DALYREJS.G0001V00`, `TRANREPT.G0001V00`. The catalog maps the
  input DSNs (`.PS`) to the tables; export with the output DSN names from `chains.json`.
* Proof: 7 golden cases `p01`..`p07` (`p04-overlimit-reject` is the demo case).

### 3.2 Interest (`interest` chain: INTCALC) - BR-INT-001..047

* `InterestHandler` + `InterestCalculationService` (CBACT04C, PARM `2022071800`): iterate
  `tran_cat_balance` in key order; on account break read the account (missing -> abend, BR-INT-011) and
  the xref by account id (BR-INT-012/013); rate lookup by (group, type, category) with `DEFAULT` fallback on
  "not found" (BR-INT-014..019); rate 0 -> skip (BR-INT-020);
  `interest = (bal * rate / 1200).setScale(2, DOWN)` (BR-INT-021..024; `12.355 -> 12.35`, negatives
  truncate toward zero); write one SYSTRAN record per category (TRAN-ID = PARM date + 6-digit run-wide
  sequence, type `01`, category `05`, source `System`, desc `Int. for a/c <11 digits>`, BR-INT-036..040).
  On account break, the previous account gets `curr_bal += total interest` and both cycle buckets reset to 0
  (BR-INT-028..031). **EOF quirk:** the last account is never updated (BR-INT-034/035). Fee computation is an
  empty stub (BR-INT-042).
* Outputs: `SYSTRAN.G0001V00` (CVTRA05Y, 350) and `ACCTDATA.VSAM.KSDS`; SYSOUT `INTCALC.STEP15`; RC 0
  (abend RC 12 paths per BR-INT-005/019/033/046).
* Proof: 7 golden cases `i01`..`i07`; `i02-tie-half-cent` is the demo case and the contrast with
  `make parity-naive` (half-even rounding gives 12.36 and fails).

### 3.3 Transaction-type maintenance (`trantype-update`, `trantype-dynamic`) - BR-TRT-001..045

* Data: `carddemo.transaction_type` / `transaction_type_categor` (V300, identical DDL to
  `db/postgres/01-transaction-type.sql`); descriptions stored space-padded to 50 (BR-TRT-011).
* `TransactionTypeMaintenanceService` (COBTUPDT, MNTTRDB2/STEP1): read the raw `INPFILE` cards (53 bytes,
  BR-TRT-001/002); `A` insert, `U` update, `D` delete, `*` comment, anything else invalid (RC 4); echo
  `PROCESSING ...` per card; messages and the never-cleared `WS-RETURN-MSG` behaviour verbatim
  (BR-TRT-014/015). Error ladder tests only `SQLCODE < 0`: 0-row UPDATE/DELETE report success
  (BR-TRT-018/021).
* SQLCODE emulation: a small `SqlCodeTranslator` maps JDBC/PostgreSQL outcomes to the codes the golden
  files recorded under ocesql: unique violation (SQLSTATE 23505) -> `-403`, statement after a failure in the
  same unit of work (25P02) -> `-400`, success -> 0, no row -> `+100` only where the legacy actually sees it.
  One JDBC transaction for the whole step (no savepoints) so a failure aborts the rest (BR-TRT-029);
  commit at end (BR-TRT-028). Step RC is the last-assigned `RETURN-CODE` (BR-TRT-024).
* `DynamicSqlService` (CBTRNDYN, MNTTRDYN/STEP1): read `DYNSQL` cards (80 bytes; column-1 `--` comments,
  inline `--` swallows the rest, 800-byte buffer, BR-TRT-031..035), PREPARE/EXECUTE each statement in the
  same transaction (errors surface at EXECUTE, RC 8, BR-TRT-036..038), then "dynamic CALL" of the program
  named in PARMS -> invoke the COBTUPDT service in the same unit of work; its final RC replaces CBTRNDYN's
  (BR-TRT-039..043).
* Outputs: SYSOUT `MNTTRDB2.STEP1` / `MNTTRDYN.STEP1`, `db/carddemo.transaction_type.csv`,
  `db/carddemo.transaction_type_categor.csv` (ordering from `chains.json`). Proof: `t01`..`t07`, `d01`..`d03`.

### 3.4 Online and system routines (no batch chain) - BR-ONL-001..055

CICS, HLASM and PL/I are **analysis-only**: nothing here is compared with an executed legacy run.

* Data (V400-V499): `carddemo.user_security` (CSUSR01Y), `carddemo.card` (CVACT02Y), `carddemo.customer`
  (CVCUS01Y), seeded with synthetic rows (admin + regular user, a few accounts/cards/customers). Accounts and
  xrefs reuse the common tables.
* Services and REST (`/api/online/...`, JSON camelCase):
  * `SignOnService` (COSGN00C, BR-ONL-001..007): `POST /api/online/signon` -> upper-cases id/password,
    blank checks, lookup, wrong password, routes `A` -> admin menu, else main menu; messages verbatim.
  * `MenuService` (COMEN01C, BR-ONL-008..014): `GET /api/online/menu?userType=` options table, option
    validation, admin-only and "coming soon"/not-installed messages.
  * `AccountViewService` (COACTVWC, BR-ONL-015..022): `GET /api/online/accounts/{acctId}`; xref ->
    account -> customer chain, validation and NOTFND messages (75-char truncation).
  * `AccountUpdateService` (COACTUPC, BR-ONL-023..043): `PUT /api/online/accounts/{acctId}` with the edit
    ladder (first error wins), change detection, confirmation step and optimistic concurrency check
    (compare the fetched snapshot with the current row before update, inside one transaction).
  * `DateFormatter` (COBDATFT, BR-ONL-044..047), `Waiter` (MVSWAIT, BR-ONL-048), `LateFeeCalculator`
    (CBFEECAL/CBFEEDRV, BR-ONL-049..055: `bal * 2.5 / 100` truncated, floor 5.00, cap 35.00, GOLD below
    100.00 waived RC 4, zero cycle days RC 8, late days <= 0 fee 0) with `POST /api/online/fees`.
* UI: `ui/src/slices/online/index.tsx` exporting `slice` with Sign-on, Account view and Fee calculator pages
  (stable `data-testid`s), plus `ui/e2e/online.spec.ts`.

## 4. Test strategy

| Layer | Command | Content | Pass criterion |
|---|---|---|---|
| Legacy regression | `make parity` | legacy estate vs golden | 24/24 (unchanged) |
| Unit | `make test-unit` | JUnit 5 per rule: method `br<Slice><NNN>_<what>()` with `@DisplayName("BR-XXX-NNN ...")`; the Given/When/Then of the rule catalogue is the minimum case; `@DbTest` for PostgreSQL tests | all green, every rule ID of the slice has >= 1 test (online included) |
| Regression | `make parity-java [CHAIN=...]` | Java run of each golden case, compared by the unchanged `compare.py` | 24/24 (posting 7, interest 7, trantype-update 7, trantype-dynamic 3) |
| Functional | `make test-functional [SLICE=...]` | Playwright: run the slice's demo case from the Jobs page, check RC table, SYSOUT, decoded datasets and the PASS verdict; online: sign-on, account view, fee calc | all green |
| Contrast | `make parity-naive CASE=i02-tie-half-cent` | half-even reference | must FAIL (shows why truncation matters) |

## 5. Traceability

Each slice adds `docs/analysis/traceability/<slice>.json`:
`{"BR-INT-022": {"java": ["java/interest/src/main/java/.../InterestCalculator.java"], "tests":
["InterestCalculatorTest#brInt022_truncatesHalfCent"]}}` covering every rule of its catalogue.
`docs/analysis/build_explorer.py` merges these into the Business Rule Explorer; the final test report
(`docs/test-report/index.html`) is built from Surefire XML, the parity reports and Playwright results.

## 6. Work split and conventions for the conversion

* One branch and PR per slice, `devin/<timestamp>-convert-<slice>`, into the integration branch; never
  merged by an agent. A slice changes only `java/<slice>/**`, `ui/src/slices/<slice>/**`,
  `ui/e2e/<slice>.spec.ts`, `docs/analysis/traceability/<slice>.json` and its own Flyway range. Changes to
  `common`, `app`, the Makefile or CI are out of scope; if one is unavoidable, it is reported as a blocker.
* Synthetic data only; no secrets; claims in PRs only for commands that ran.
