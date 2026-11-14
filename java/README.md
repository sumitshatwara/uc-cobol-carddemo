# CardDemo Java 21 target

Java 21 · Spring Boot 3.3 · Spring Batch 5 · PostgreSQL 15 (the estate's `db` container,
database `carddemo_java`) · Flyway · JPA with `ddl-auto: validate`.

```
java/
  pom.xml          parent: modules common, posting, interest, trantype, online, app
  common/          foundation (owned by the platform team; slices do not edit it)
  posting/         slice: POSTTRAN (CBTRN02C) + TRANREPT (CBTRN03C)         Flyway V100-V199
  interest/        slice: INTCALC (CBACT04C)                                Flyway V200-V299
  trantype/        slice: MNTTRDB2 (COBTUPDT) + MNTTRDYN (CBTRNDYN)         Flyway V300-V399
  online/          slice: CICS flow as REST, HLASM + PL/I routine ports     Flyway V400-V499
  app/             Spring Boot service (REST for the console) + parity CLI
```

## Running

```sh
make up build parity            # legacy estate green first (the oracle)
make test-unit                  # JUnit, all modules
make parity-java [CHAIN=interest] [CASE=i02-tie-half-cent]
make serve                      # API + console on http://localhost:8080
make test-e2e                   # Playwright journeys (ui/e2e)
```

`make parity-java` builds the jar, runs each Java chain on every fixture case
(`work/java/<chain>/<case>/actual`) and compares it with the legacy golden files using the
estate's own comparator, `tools/parity/compare.py ... --label java`
(report: `work/parity/report-java.md`).  Any difference is a failure.

## What the foundation gives a slice

| Need | Use |
|---|---|
| Inputs already in PostgreSQL | `carddemo.account`, `card_xref`, `tran_cat_balance`, `disclosure_group`, `tran_type`, `tran_category`, `transact` (KSDS), `daily_tran` (sequential, `rec_seq`), every raw record in `carddemo.fixture_record`; Db2 tables `carddemo.transaction_type(_categor)` seeded from `input/db/seed.sql`. Entities in `com.carddemo.common.entity`. |
| Plug in a chain | a `@Component` implementing `ChainHandler` (`chain()` = chains.json name); list it in `java/<module>/parity-chains` |
| Return codes | `run.stepRc(job, step, rc)`; include the setup-job steps the harness merges into the first job (see the golden `rc.json`) |
| SYSOUT | `run.sysout(job, step).display(...)` - one call per COBOL `DISPLAY` |
| Output datasets | `run.datasets().exportTable(dsn, DatasetTables.ACCOUNT)`, `exportQuery(...)`, `writeFieldMaps(dsn, copybook, rows)`, `writeRecords(dsn, layout, lrecl, lines)` |
| Copybook records | `CopybookParser` + `RecordCodec` (same layout rules as `tools/parity/copybook.py`; encoding refuses silent rounding) |
| Time | `FrozenClock` (`db2Timestamp()`, `currentDate16()`), never `LocalDateTime.now()` |
| COBOL arithmetic | `CobolNumeric.truncate` (no ROUNDED = `RoundingMode.DOWN`), `rounded` (HALF_UP), `fit` (high-order truncation) |
| GDG | generations restart at `G0001V00` per case: `DALYREJS(0)` is `AWS.M2.CARDDEMO.DALYREJS.G0001V00` |
| Unit-test helpers | `com.carddemo.common.TestRepo` (test-jar): fixture paths, golden files, copybooks |
| REST / console | slice `@RestController`s under `com.carddemo.<slice>`; screens in `ui/src/slices/<slice>/index.tsx` |

Generated code: `tools/javagen/gen_vsam.py` writes the VSAM-table migration (V001) and the
entities from the copybooks; CI fails when they are stale (`make java-gen-check`).
