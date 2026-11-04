# CardDemo Java foundation

This is the additive Java 21/Spring Boot conversion workspace. The reactor
modules are:

| Module | Purpose |
| --- | --- |
| `common` | Copybook codec, fixture loading, PostgreSQL mappings, run/export APIs |
| `posting` | Posting and transaction report chain |
| `interest` | Interest calculation chain |
| `trantype` | Transaction type maintenance chains |
| `online` | Online/CICS-facing conversion components |
| `app` | Spring Boot API, CLI and module composition |

Slice modules start as extension points. Add a `@Component` implementing
`ChainHandler`, declare any slice-only tables through `extraTables()`, and
return `RunResult` with the same job/step return-code union as the legacy run.
For the first chain job, golden `rc.json` also lists every setup-job step with
RC 0; include those setup steps in the handler's first-job step map.

## Conventions

Java, TypeScript and JSON identifiers use camelCase; PostgreSQL columns use
snake_case. Copybook text maps to `String`; numeric scale greater than zero
maps to `BigDecimal`; scale zero with up to 9 digits maps to `int`/`Integer`,
10–18 digits to `long`, and wider values to `BigDecimal`. Preserve COBOL
truncation explicitly with `BigDecimal` and `RoundingMode.DOWN` wherever the
source calculation omits `ROUNDED`.

Flyway locations are recursively scanned from `classpath:db/migration`.
Version ownership is common V1–V99, posting V100–V199, interest V200–V299,
trantype V300–V399 and online V400–V499. The shared schema is `carddemo`,
and the Java app uses the separate `carddemo_java` database.

## Adding a slice

Implement the handler and tests in that slice's Maven module, add migrations
only within its version range, and add a `ui/src/slices/<slice>/index.tsx`
module when the slice needs a screen. The console discovers each exported
`slice: SliceModule` automatically. Add browser coverage as
`ui/e2e/<slice>.spec.ts`; slice agents do not need to change the shared app
pom, Makefile, CI workflow or shell.

## Verification layers

1. Unit and codec golden tests: `make test-unit`.
2. Fixture regression: `make parity-java` compares each registered Java chain
   with the legacy field-level golden files; legacy estate regression remains
   `make parity`.
3. Browser functional tests: `make test-functional`; use
   `make test-functional SLICE=<slice>` for one slice. Build the UI with
   `make ui-build`.

`@DbTest` tags PostgreSQL tests and enables them only when
`CARDDEMO_DB_TESTS=true`. Keep JUnit methods named for business-rule IDs, for
example `@DisplayName("BR-INT-007 truncates interest")` on
`brInt007_truncatesInterest()`. Surefire XML stays in the default
`target/surefire-reports` location for later per-rule reporting.
