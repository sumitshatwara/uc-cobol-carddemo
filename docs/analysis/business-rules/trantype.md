# Business rules: Transaction type maintenance (MNTTRDB2 / MNTTRDYN)

Slice `trantype`: batch Db2 maintenance of `CARDDEMO.TRANSACTION_TYPE` by `COBTUPDT` (job MNTTRDB2, chain `trantype-update`) and the ad-hoc driver `CBTRNDYN` (job MNTTRDYN, chain `trantype-dynamic`), which runs one dynamic SQL statement and then dynamically CALLs `COBTUPDT`. Off-host the DSN attach is emulated by `tools/stubs/DSNRUN.cbl` and `DSNEXIT.cbl` against PostgreSQL through Open COBOL ESQL; the golden fixtures record that behaviour.

38 rules. By category: control-flow 6, data-access 5, edge-case 8, linkage 1, return-code 4, state-change 4, sysout 8, validation 2.

Rules marked "no fixture" or "derived from the source" were worked out by reading the source; no run proves them yet. The machine-readable mirror is [`trantype.json`](trantype.json).

| ID | Title | Category | Fixtures |
|---|---|---|---|
| [BR-TT-001](#br-tt-001) | Maintenance input record layout | data-access | t01-add |
| [BR-TT-002](#br-tt-002) | Open-file status message | sysout | t01-add, d02-delete-statement |
| [BR-TT-003](#br-tt-003) | Open failure does not stop the program | edge-case | - |
| [BR-TT-004](#br-tt-004) | Read loop and record echo | sysout | t02-update, t07-mixed-batch |
| [BR-TT-005](#br-tt-005) | Empty input file | edge-case | d02-delete-statement |
| [BR-TT-006](#br-tt-006) | Action A inserts a new transaction type | state-change | t01-add, t07-mixed-batch |
| [BR-TT-007](#br-tt-007) | Insert success message | sysout | t01-add, t04-comments, d01-update-then-apply |
| [BR-TT-008](#br-tt-008) | Description stored space-padded to 50 characters | data-access | t01-add, t02-update, t04-comments, d01-update-then-apply |
| [BR-TT-009](#br-tt-009) | Action U updates a description by key | state-change | t02-update, t07-mixed-batch |
| [BR-TT-010](#br-tt-010) | Update success message | sysout | t02-update, t07-mixed-batch |
| [BR-TT-011](#br-tt-011) | Update of a missing key | edge-case | - |
| [BR-TT-012](#br-tt-012) | Action D deletes by key, description ignored | state-change | t03-delete, t07-mixed-batch |
| [BR-TT-013](#br-tt-013) | Delete success message | sysout | t03-delete, t07-mixed-batch |
| [BR-TT-014](#br-tt-014) | Delete of a missing key reports success (off-host baseline) | edge-case | t05-delete-missing |
| [BR-TT-015](#br-tt-015) | Comment records are skipped | control-flow | t04-comments, t07-mixed-batch |
| [BR-TT-016](#br-tt-016) | Unknown action code | validation | - |
| [BR-TT-017](#br-tt-017) | Negative SQLCODE is an error | return-code | t06-duplicate-add, d03-bad-statement |
| [BR-TT-018](#br-tt-018) | Duplicate add is rejected | edge-case | t06-duplicate-add |
| [BR-TT-019](#br-tt-019) | Errors do not stop the batch; RC 4 is sticky | control-flow | t06-duplicate-add |
| [BR-TT-020](#br-tt-020) | Error message buffer is 80 characters and never cleared | sysout | t06-duplicate-add |
| [BR-TT-021](#br-tt-021) | Type code is not validated | validation | - |
| [BR-TT-022](#br-tt-022) | End of run: close and STOP RUN | control-flow | d01-update-then-apply, d02-delete-statement |
| [BR-TT-023](#br-tt-023) | Commit at end of task | data-access | t01-add, d01-update-then-apply |
| [BR-TT-024](#br-tt-024) | PostgreSQL aborts the unit of work after a failed statement | edge-case | d03-bad-statement |
| [BR-TT-025](#br-tt-025) | Delete of a type that has categories is refused | edge-case | - |
| [BR-TT-026](#br-tt-026) | Transaction type table keys and columns | data-access | t01-add |
| [BR-TT-027](#br-tt-027) | Dynamic SQL statement assembled from DYNSQL | data-access | d01-update-then-apply |
| [BR-TT-028](#br-tt-028) | SQL comment lines in DYNSQL | control-flow | d01-update-then-apply |
| [BR-TT-029](#br-tt-029) | SQL line count message | sysout | d01-update-then-apply, d02-delete-statement, d03-bad-statement |
| [BR-TT-030](#br-tt-030) | Statement size limits | edge-case | - |
| [BR-TT-031](#br-tt-031) | PREPARE failure | return-code | - |
| [BR-TT-032](#br-tt-032) | EXECUTE result message and return code | return-code | d01-update-then-apply, d02-delete-statement, d03-bad-statement |
| [BR-TT-033](#br-tt-033) | Bad dynamic statement does not stop the job | control-flow | d03-bad-statement |
| [BR-TT-034](#br-tt-034) | Dynamic CALL of the program named in PARMS | linkage | d01-update-then-apply |
| [BR-TT-035](#br-tt-035) | No PARMS: no call | control-flow | - |
| [BR-TT-036](#br-tt-036) | `CBTRNDYN: END` is never shown after the call | sysout | d01-update-then-apply, d02-delete-statement, d03-bad-statement |
| [BR-TT-037](#br-tt-037) | Step return code is the last value moved to RETURN-CODE | return-code | d03-bad-statement |
| [BR-TT-038](#br-tt-038) | Dynamic SQL and file updates share one unit of work | state-change | d01-update-then-apply |

## BR-TT-001

**Maintenance input record layout**

Each INPFILE record is 53 bytes: column 1 is the action code, columns 2-3 the two-character transaction type code and columns 4-53 the 50-character description. Short records are padded with spaces.

* **Category:** data-access
* **Source:** `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:39-46`; `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:71-77`; `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:100-103`; `app/app-transaction-type-db2/jcl/MNTTRDB2.jcl:8-19`
* **Fields:** INPUT-TYPE, INPUT-TR-NUMBER, INPUT-TR-DESC (FD TR-RECORD, COBTUPDT); INPUT-REC-TYPE, INPUT-REC-NUMBER, INPUT-REC-DESC (WS-INPUT-REC, COBTUPDT working storage)
* **Fixtures:** `fixtures/trantype-update/t01-add`
* **Test:**
  * *Given* an INPFILE record `A04Authorization`
  * *When* COBTUPDT reads it INTO WS-INPUT-REC
  * *Then* INPUT-REC-TYPE = `A`, INPUT-REC-NUMBER = `04`, INPUT-REC-DESC = `Authorization` followed by 37 spaces

## BR-TT-002

**Open-file status message**

On start the program opens INPFILE and displays `OPEN FILE OK` when the file status is `00`, otherwise `OPEN FILE NOT OK`.

* **Category:** sysout
* **Source:** `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:82-89`
* **Fields:** WS-INF-STATUS (COBTUPDT working storage)
* **Fixtures:** `fixtures/trantype-update/t01-add`, `fixtures/trantype-dynamic/d02-delete-statement`
* **Test:**
  * *Given* an existing INPFILE (even an empty one)
  * *When* MNTTRDB2 STEP1 starts COBTUPDT
  * *Then* the second SYSOUT line is `OPEN FILE OK`

## BR-TT-003

**Open failure does not stop the program**

If the open fails the program still falls through into the read loop; there is no stop, return code or abend on `OPEN FILE NOT OK`. With no end-of-file condition ever raised the loop would keep reporting invalid records (see Findings F2; derived from the source, not executed).

* **Category:** edge-case
* **Source:** `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:82-99`; `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:100-107`
* **Fields:** WS-INF-STATUS, LASTREC (COBTUPDT working storage)
* **Fixtures:** none yet
* **Test:**
  * *Given* INPFILE cannot be opened (file status not `00`)
  * *When* COBTUPDT runs
  * *Then* `OPEN FILE NOT OK` is displayed and control continues into 1001-READ-NEXT-RECORDS anyway

## BR-TT-004

**Read loop and record echo**

Records are read sequentially until end of file. Every record read (not the end-of-file read) is echoed as `PROCESSING   ` (three spaces) followed by the full 53-byte record, before it is acted on.

* **Category:** sysout
* **Source:** `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:91-107`
* **Fields:** LASTREC (FLAGS, COBTUPDT); WS-INPUT-REC (COBTUPDT)
* **Fixtures:** `fixtures/trantype-update/t02-update`, `fixtures/trantype-update/t07-mixed-batch`
* **Test:**
  * *Given* INPFILE holds `U02Payment - bank transfer`
  * *When* the record is read
  * *Then* SYSOUT shows `PROCESSING   U02Payment - bank transfer` padded with trailing spaces to 53 record bytes, then `UPDATING RECORD`

## BR-TT-005

**Empty input file**

An empty INPFILE is not an error: after `OPEN FILE OK` nothing else is displayed by COBTUPDT, no SQL is issued and the step ends with return code 0.

* **Category:** edge-case
* **Source:** `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:91-107`
* **Fields:** LASTREC (FLAGS, COBTUPDT)
* **Fixtures:** `fixtures/trantype-dynamic/d02-delete-statement`
* **Test:**
  * *Given* an empty INPFILE
  * *When* COBTUPDT runs (here called from CBTRNDYN)
  * *Then* SYSOUT from COBTUPDT is only `OPEN FILE OK`; table rows are unchanged by COBTUPDT; RC 0

## BR-TT-006

**Action A inserts a new transaction type**

Action code `A` displays `ADDING RECORD` and inserts a row into CARDDEMO.TRANSACTION_TYPE with TR_TYPE = columns 2-3 and TR_DESCRIPTION = columns 4-53.

* **Category:** state-change
* **Source:** `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:111-113`; `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:132-148`
* **Fields:** INPUT-REC-TYPE, INPUT-REC-NUMBER, INPUT-REC-DESC (WS-INPUT-REC, COBTUPDT working storage); TR_TYPE, TR_DESCRIPTION (DCLTRTYP / CARDDEMO.TRANSACTION_TYPE)
* **Fixtures:** `fixtures/trantype-update/t01-add`, `fixtures/trantype-update/t07-mixed-batch`
* **Test:**
  * *Given* TRANSACTION_TYPE holds 01, 02, 03
  * *When* record `A05Refund` is processed
  * *Then* `ADDING RECORD` is displayed and a row 05 / `Refund` (space-padded to 50) exists

## BR-TT-007

**Insert success message**

When the INSERT returns SQLCODE 0 the program displays `RECORD INSERTED SUCCESSFULLY`.

* **Category:** sysout
* **Source:** `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:149-153`
* **Fields:** SQLCODE (SQLCA)
* **Fixtures:** `fixtures/trantype-update/t01-add`, `fixtures/trantype-update/t04-comments`, `fixtures/trantype-dynamic/d01-update-then-apply`
* **Test:**
  * *Given* type 04 does not exist
  * *When* record `A04Authorization` is processed
  * *Then* SYSOUT shows `ADDING RECORD` then `RECORD INSERTED SUCCESSFULLY`; RC stays 0

## BR-TT-008

**Description stored space-padded to 50 characters**

INSERT and UPDATE bind the fixed PIC X(50) field INPUT-REC-DESC, not the DCLGEN VARCHAR structure, so TR_DESCRIPTION is stored with trailing spaces to its full 50 characters. Rows written by other means (seed data, dynamic SQL) are not padded.

* **Category:** data-access
* **Source:** `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:76-77`; `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:137-148`; `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:171-175`; `app/app-transaction-type-db2/dcl/DCLTRTYP.dcl:40-46`
* **Fields:** INPUT-REC-DESC (COBTUPDT); DCL-TR-DESCRIPTION-LEN, DCL-TR-DESCRIPTION-TEXT (DCLTRTYP, unused); TR_DESCRIPTION VARCHAR(50)
* **Fixtures:** `fixtures/trantype-update/t01-add`, `fixtures/trantype-update/t02-update`, `fixtures/trantype-update/t04-comments`, `fixtures/trantype-dynamic/d01-update-then-apply`
* **Test:**
  * *Given* record `A06Fee`
  * *When* it is inserted
  * *Then* TR_DESCRIPTION = `Fee` + 47 spaces (50 characters), whereas a seed row such as `Purchase` has no padding

## BR-TT-009

**Action U updates a description by key**

Action code `U` displays `UPDATING RECORD` and sets TR_DESCRIPTION to columns 4-53 for the row whose TR_TYPE equals columns 2-3.

* **Category:** state-change
* **Source:** `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:114-116`; `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:166-175`
* **Fields:** INPUT-REC-TYPE, INPUT-REC-NUMBER, INPUT-REC-DESC (WS-INPUT-REC, COBTUPDT working storage); TR_TYPE, TR_DESCRIPTION (DCLTRTYP / CARDDEMO.TRANSACTION_TYPE)
* **Fixtures:** `fixtures/trantype-update/t02-update`, `fixtures/trantype-update/t07-mixed-batch`
* **Test:**
  * *Given* row 02 / `Payment`
  * *When* record `U02Payment - bank transfer` is processed
  * *Then* row 02 has TR_DESCRIPTION `Payment - bank transfer` padded to 50

## BR-TT-010

**Update success message**

When the UPDATE returns SQLCODE 0 the program displays `RECORD UPDATED SUCCESSFULLY`.

* **Category:** sysout
* **Source:** `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:176-179`
* **Fields:** SQLCODE (SQLCA)
* **Fixtures:** `fixtures/trantype-update/t02-update`, `fixtures/trantype-update/t07-mixed-batch`
* **Test:**
  * *Given* row 01 exists
  * *When* record `U01Purchase - card present` is processed
  * *Then* SYSOUT shows `UPDATING RECORD` then `RECORD UPDATED SUCCESSFULLY`

## BR-TT-011

**Update of a missing key**

The source treats UPDATE SQLCODE +100 (no row) as an error: it displays `No records found.` and sets return code 4. Off-host, Open COBOL ESQL is expected to report SQLCODE 0 for a zero-row UPDATE as it does for DELETE (BR-TT-014), which would print `RECORD UPDATED SUCCESSFULLY` with RC 0; no fixture proves either outcome yet.

* **Category:** edge-case
* **Source:** `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:180-184`; `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:230-233`
* **Fields:** SQLCODE (SQLCA); WS-RETURN-MSG (COBTUPDT); RETURN-CODE
* **Fixtures:** none yet
* **Test:**
  * *Given* type 09 does not exist
  * *When* record `U09Anything` is processed
  * *Then* on Db2: `No records found.` (padded to 80) and RC 4; off-host: to be confirmed by a new fixture before the Java port relies on it

## BR-TT-012

**Action D deletes by key, description ignored**

Action code `D` displays `DELETING RECORD` and deletes the row whose TR_TYPE equals columns 2-3. Columns 4-53 are not used.

* **Category:** state-change
* **Source:** `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:117-119`; `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:196-204`
* **Fields:** INPUT-REC-TYPE, INPUT-REC-NUMBER, INPUT-REC-DESC (WS-INPUT-REC, COBTUPDT working storage); TR_TYPE (DCLTRTYP / CARDDEMO.TRANSACTION_TYPE)
* **Fixtures:** `fixtures/trantype-update/t03-delete`, `fixtures/trantype-update/t07-mixed-batch`
* **Test:**
  * *Given* rows 01, 02, 03
  * *When* record `D03` is processed
  * *Then* rows 01 and 02 remain

## BR-TT-013

**Delete success message**

When the DELETE returns SQLCODE 0 the program displays `RECORD DELETED SUCCESSFULLY`.

* **Category:** sysout
* **Source:** `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:205-209`
* **Fields:** SQLCODE (SQLCA)
* **Fixtures:** `fixtures/trantype-update/t03-delete`, `fixtures/trantype-update/t07-mixed-batch`
* **Test:**
  * *Given* row 03 exists
  * *When* record `D03` is processed
  * *Then* SYSOUT shows `DELETING RECORD` then `RECORD DELETED SUCCESSFULLY`; RC 0

## BR-TT-014

**Delete of a missing key reports success (off-host baseline)**

Deleting a type that does not exist leaves the table unchanged, still displays `RECORD DELETED SUCCESSFULLY` and ends with RC 0. The golden output shows the SQLCODE seen by COBTUPDT was 0: the source does have a +100 branch (`No records found.`, RC 4) that Db2 on z/OS would take. The migration reproduces the golden behaviour (Findings F1).

* **Category:** edge-case
* **Source:** `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:205-225`
* **Fields:** SQLCODE (SQLCA); INPUT-REC-NUMBER (COBTUPDT)
* **Fixtures:** `fixtures/trantype-update/t05-delete-missing`
* **Test:**
  * *Given* rows 01, 02, 03
  * *When* record `D09` is processed
  * *Then* SYSOUT `DELETING RECORD`, `RECORD DELETED SUCCESSFULLY`; table unchanged; RC 0

## BR-TT-015

**Comment records are skipped**

A record whose action code is `*` is displayed (`PROCESSING   ...`), then `IGNORING COMMENTED LINE`; no SQL is issued.

* **Category:** control-flow
* **Source:** `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:120-121`
* **Fields:** INPUT-REC-TYPE (COBTUPDT)
* **Fixtures:** `fixtures/trantype-update/t04-comments`, `fixtures/trantype-update/t07-mixed-batch`
* **Test:**
  * *Given* records `*  Nightly maintenance batch`, `A06Fee`, `*  end`
  * *When* the file is processed
  * *Then* both comment lines print `IGNORING COMMENTED LINE`; only type 06 is added; RC 0

## BR-TT-016

**Unknown action code**

Any action code other than `A`, `U`, `D` or `*` (including lowercase letters and a blank column 1) displays `ERROR: TYPE NOT VALID`, sets return code 4 and continues with the next record.

* **Category:** validation
* **Source:** `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:122-129`; `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:230-233`
* **Fields:** INPUT-REC-TYPE, WS-RETURN-MSG (COBTUPDT); RETURN-CODE
* **Fixtures:** none yet
* **Test:**
  * *Given* a record `X05Something`
  * *When* it is processed
  * *Then* SYSOUT `ERROR: TYPE NOT VALID` (padded to 80); no SQL issued; step RC 4; following records still processed

## BR-TT-017

**Negative SQLCODE is an error**

For INSERT, UPDATE and DELETE only a negative SQLCODE is treated as a database error: the program displays `Error accessing: TRANSACTION_TYPE table. SQLCODE:` followed by the code edited as `----9` (e.g. ` -403`) and sets return code 4. Positive SQLCODEs other than +100 fall through silently.

* **Category:** return-code
* **Source:** `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:64-65`; `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:154-162`; `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:185-193`; `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:216-224`; `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:230-233`
* **Fields:** SQLCODE (SQLCA); WS-VAR-SQLCODE, WS-RETURN-MSG (COBTUPDT); RETURN-CODE
* **Fixtures:** `fixtures/trantype-update/t06-duplicate-add`, `fixtures/trantype-dynamic/d03-bad-statement`
* **Test:**
  * *Given* row 01 exists
  * *When* record `A01Purchase` is processed
  * *Then* SYSOUT `Error accessing: TRANSACTION_TYPE table. SQLCODE: -403` padded to 80; RC 4

## BR-TT-018

**Duplicate add is rejected**

Adding a type code that already exists violates the primary key. Through Open COBOL ESQL the SQLCODE is -403 (Db2 would return -803); the error message is displayed, the existing row is unchanged and the step ends with RC 4.

* **Category:** edge-case
* **Source:** `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:137-162`; `app/app-transaction-type-db2/ddl/TRNTYPE.ddl:1-4`; `db/postgres/01-transaction-type.sql:6-9`
* **Fields:** TR_TYPE (primary key, CARDDEMO.TRANSACTION_TYPE); SQLCODE (SQLCA)
* **Fixtures:** `fixtures/trantype-update/t06-duplicate-add`
* **Test:**
  * *Given* row 01 / `Purchase`
  * *When* record `A01Purchase` is processed
  * *Then* `ADDING RECORD`, `Error accessing: TRANSACTION_TYPE table. SQLCODE: -403`; row 01 still `Purchase`; RC 4

## BR-TT-019

**Errors do not stop the batch; RC 4 is sticky**

The error routine 9999-ABEND only displays the message and moves 4 to RETURN-CODE; it does not abend or stop. Processing continues with the next record and nothing resets RETURN-CODE, so one error anywhere gives the step RC 4.

* **Category:** control-flow
* **Source:** `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:91-99`; `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:230-233`
* **Fields:** RETURN-CODE; WS-RETURN-MSG (COBTUPDT)
* **Fixtures:** `fixtures/trantype-update/t06-duplicate-add`
* **Test:**
  * *Given* records `X01bad` then `*  note`
  * *When* the file is processed
  * *Then* `ERROR: TYPE NOT VALID`, then `IGNORING COMMENTED LINE`; step RC 4

## BR-TT-020

**Error message buffer is 80 characters and never cleared**

Error texts are built with STRING into the 80-byte WS-RETURN-MSG and displayed in full, so SYSOUT lines carry trailing spaces to 80 characters. The buffer is never cleared, so a shorter message after a longer one keeps the tail of the earlier text (Findings F3).

* **Category:** sysout
* **Source:** `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:60-62`; `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:123-127`; `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:155-161`; `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:181-183`; `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:211-213`; `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:230-231`
* **Fields:** WS-RETURN-MSG (COBTUPDT)
* **Fixtures:** `fixtures/trantype-update/t06-duplicate-add`
* **Test:**
  * *Given* a run whose first error was `Error accessing: TRANSACTION_TYPE table. SQLCODE: -403`
  * *When* a later record has action code `X`
  * *Then* SYSOUT shows `ERROR: TYPE NOT VALIDSACTION_TYPE table. SQLCODE: -403` (derived from the source, no fixture)

## BR-TT-021

**Type code is not validated**

The JCL documents columns 2-3 as numeric, but the program does not check them: any two characters (letters, spaces) are passed to the database as TR_TYPE.

* **Category:** validation
* **Source:** `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:109-129`; `app/app-transaction-type-db2/jcl/MNTTRDB2.jcl:16-16`
* **Fields:** INPUT-REC-NUMBER (COBTUPDT); TR_TYPE CHAR(2)
* **Fixtures:** none yet
* **Test:**
  * *Given* no row `AB` exists
  * *When* record `AB` + `Letters` is processed as `AABLetters`
  * *Then* a row with TR_TYPE `AB` is inserted and `RECORD INSERTED SUCCESSFULLY` is displayed

## BR-TT-022

**End of run: close and STOP RUN**

At end of file the program closes INPFILE and executes STOP RUN, which ends the whole run unit (including any program that CALLed it) with the current RETURN-CODE.

* **Category:** control-flow
* **Source:** `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:91-99`; `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:234-236`
* **Fields:** RETURN-CODE
* **Fixtures:** `fixtures/trantype-dynamic/d01-update-then-apply`, `fixtures/trantype-dynamic/d02-delete-statement`
* **Test:**
  * *Given* COBTUPDT called from CBTRNDYN
  * *When* COBTUPDT reaches end of file
  * *Then* control never returns to CBTRNDYN; the next SYSOUT line is `DSNRUN: COMMIT AT END OF TASK`

## BR-TT-023

**Commit at end of task**

Neither program commits. The DSN RUN step (emulated by DSNRUN/DSNEXIT) displays `DSNRUN: DSN ATTACHED, RUNNING <program>` before the program and commits the whole unit of work at end of task, displaying `DSNRUN: COMMIT AT END OF TASK` (or `DSNRUN: COMMIT FAILED` + SQLCODE).

* **Category:** data-access
* **Source:** `app/app-transaction-type-db2/jcl/MNTTRDB2.jcl:28-30`; `app/app-transaction-type-db2/jcl/MNTTRDYN.jcl:31-34`; `tools/stubs/DSNRUN.cbl:39-52`; `tools/stubs/DSNEXIT.cbl:13-21`
* **Fields:** SQLCODE (SQLCA)
* **Fixtures:** `fixtures/trantype-update/t01-add`, `fixtures/trantype-dynamic/d01-update-then-apply`
* **Test:**
  * *Given* record `A04Authorization`
  * *When* MNTTRDB2 runs
  * *Then* SYSOUT starts with `DSNRUN: DSN ATTACHED, RUNNING COBTUPDT` and ends with `DSNRUN: COMMIT AT END OF TASK`; row 04 is committed

## BR-TT-024

**PostgreSQL aborts the unit of work after a failed statement**

On PostgreSQL a failed SQL statement aborts the transaction: every later statement in the same run fails with SQLCODE -400, and the end-of-task COMMIT keeps none of that unit of work although `DSNRUN: COMMIT AT END OF TASK` is still displayed. Db2 would roll back only the failing statement (Findings F5).

* **Category:** edge-case
* **Source:** `app/app-transaction-type-db2/cbl/CBTRNDYN.cbl:87-102`; `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:171-193`; `tools/stubs/DSNEXIT.cbl:13-19`
* **Fields:** SQLCODE (SQLCA); RETURN-CODE
* **Fixtures:** `fixtures/trantype-dynamic/d03-bad-statement`
* **Test:**
  * *Given* a DYNSQL statement naming a non-existent column, and INPFILE `U01Purchase - updated`
  * *When* MNTTRDYN runs
  * *Then* `CBTRNDYN: EXECUTE SQLCODE -0000000400`, then COBTUPDT's update fails with `SQLCODE: -400`; table unchanged; RC 4

## BR-TT-025

**Delete of a type that has categories is refused**

TRANSACTION_TYPE_CATEGORY references TRANSACTION_TYPE with ON DELETE RESTRICT, so deleting a type that still has categories fails with a negative SQLCODE and goes through the error path of BR-TT-017 (RC 4).

* **Category:** edge-case
* **Source:** `app/app-transaction-type-db2/ddl/TRNTYCAT.ddl:1-7`; `db/postgres/01-transaction-type.sql:11-17`; `app/app-transaction-type-db2/dcl/DCLTRCAT.dcl:36-51`; `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:201-224`
* **Fields:** TRC_TYPE_CODE, TRC_TYPE_CATEGORY, TRC_CAT_DATA (DCLTRCAT); TR_TYPE (DCLTRTYP)
* **Fixtures:** none yet
* **Test:**
  * *Given* type 01 with a category row 01/0001
  * *When* record `D01` is processed
  * *Then* `Error accessing: TRANSACTION_TYPE table. SQLCODE:` with a negative code; both rows remain; RC 4 (no fixture yet: all category tables are empty)

## BR-TT-026

**Transaction type table keys and columns**

CARDDEMO.TRANSACTION_TYPE has TR_TYPE CHAR(2) as primary key and TR_DESCRIPTION VARCHAR(50) NOT NULL; the PostgreSQL translation keeps the same types. The DCLGEN host structure DCLTRANSACTION-TYPE is included by COBTUPDT but none of its fields is used.

* **Category:** data-access
* **Source:** `app/app-transaction-type-db2/ddl/TRNTYPE.ddl:1-4`; `db/postgres/01-transaction-type.sql:6-9`; `app/app-transaction-type-db2/dcl/DCLTRTYP.dcl:28-46`; `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:54-54`
* **Fields:** DCL-TR-TYPE, DCL-TR-DESCRIPTION-LEN, DCL-TR-DESCRIPTION-TEXT (DCLTRTYP)
* **Fixtures:** `fixtures/trantype-update/t01-add`
* **Test:**
  * *Given* a seeded table with 01, 02, 03
  * *When* the database is snapshotted after a run
  * *Then* rows are ordered by tr_type and compared on tr_type, tr_description (descriptions compared with their padding)

## BR-TT-027

**Dynamic SQL statement assembled from DYNSQL**

CBTRNDYN reads every DYNSQL record (80 bytes) to end of file and concatenates columns 1-72 of each non-comment record, each followed by one space, into one statement. Columns 73-80 are ignored.

* **Category:** data-access
* **Source:** `app/app-transaction-type-db2/cbl/CBTRNDYN.cbl:34-40`; `app/app-transaction-type-db2/cbl/CBTRNDYN.cbl:47-51`; `app/app-transaction-type-db2/cbl/CBTRNDYN.cbl:67-85`
* **Fields:** DYNSQL-REC, WS-SQL-STMT-ARR, WS-SQL-STMT-LEN, WS-SQL-POS (CBTRNDYN)
* **Fixtures:** `fixtures/trantype-dynamic/d01-update-then-apply`
* **Test:**
  * *Given* DYNSQL lines `UPDATE TRANSACTION_TYPE`, `   SET TR_DESCRIPTION = 'Payment - SEPA'`, ` WHERE TR_TYPE = '02'`
  * *When* CBTRNDYN runs
  * *Then* the three lines form one UPDATE statement; row 02 becomes `Payment - SEPA` (not padded)

## BR-TT-028

**SQL comment lines in DYNSQL**

A DYNSQL record whose first two columns are `--` is skipped and not counted. A `--` anywhere else (indented, or after SQL on the same line) is passed through to the statement.

* **Category:** control-flow
* **Source:** `app/app-transaction-type-db2/cbl/CBTRNDYN.cbl:73-79`
* **Fields:** DYNSQL-REC (CBTRNDYN)
* **Fixtures:** `fixtures/trantype-dynamic/d01-update-then-apply`
* **Test:**
  * *Given* DYNSQL `-- ad-hoc fix requested by operations` followed by a three-line UPDATE
  * *When* CBTRNDYN reads it
  * *Then* the comment is excluded and `CBTRNDYN: 03 SQL LINES READ` is displayed

## BR-TT-029

**SQL line count message**

After reading DYNSQL the program displays `CBTRNDYN: ` + the number of non-comment lines as two digits + ` SQL LINES READ`.

* **Category:** sysout
* **Source:** `app/app-transaction-type-db2/cbl/CBTRNDYN.cbl:45-45`; `app/app-transaction-type-db2/cbl/CBTRNDYN.cbl:78-78`; `app/app-transaction-type-db2/cbl/CBTRNDYN.cbl:84-84`
* **Fields:** WS-LINE-COUNT PIC 9(02) (CBTRNDYN)
* **Fixtures:** `fixtures/trantype-dynamic/d01-update-then-apply`, `fixtures/trantype-dynamic/d02-delete-statement`, `fixtures/trantype-dynamic/d03-bad-statement`
* **Test:**
  * *Given* DYNSQL with a single `DELETE FROM TRANSACTION_TYPE WHERE TR_TYPE = '03'`
  * *When* CBTRNDYN runs
  * *Then* SYSOUT `CBTRNDYN: 01 SQL LINES READ`

## BR-TT-030

**Statement size limits**

The statement buffer holds 800 characters and each line takes 73, so only the first 10 full lines fit; later text is silently dropped (STRING without ON OVERFLOW). The two-digit line count wraps after 99. Statement length = pointer - 1.

* **Category:** edge-case
* **Source:** `app/app-transaction-type-db2/cbl/CBTRNDYN.cbl:45-51`; `app/app-transaction-type-db2/cbl/CBTRNDYN.cbl:74-78`; `app/app-transaction-type-db2/cbl/CBTRNDYN.cbl:83-83`
* **Fields:** WS-SQL-STMT-LEN, WS-SQL-STMT-ARR, WS-SQL-POS, WS-LINE-COUNT (CBTRNDYN)
* **Fixtures:** none yet
* **Test:**
  * *Given* DYNSQL with 11 non-comment lines
  * *When* CBTRNDYN builds the statement
  * *Then* only 800 characters (10 lines + 70 characters of line 11) are kept; `CBTRNDYN: 11 SQL LINES READ` is still displayed (derived from the source, no fixture)

## BR-TT-031

**PREPARE failure**

If PREPARE returns any non-zero SQLCODE the program displays `CBTRNDYN: PREPARE FAILED SQLCODE ` + SQLCODE, sets return code 8 and does not EXECUTE.

* **Category:** return-code
* **Source:** `app/app-transaction-type-db2/cbl/CBTRNDYN.cbl:87-93`
* **Fields:** WS-SQL-STMT (CBTRNDYN); SQLCODE (SQLCA); RETURN-CODE
* **Fixtures:** none yet
* **Test:**
  * *Given* a statement the database rejects at PREPARE time
  * *When* CBTRNDYN runs
  * *Then* `CBTRNDYN: PREPARE FAILED SQLCODE ` + code; RC 8 set; the dynamic CALL still follows (no fixture: Open COBOL ESQL defers errors to EXECUTE, see d03)

## BR-TT-032

**EXECUTE result message and return code**

After a successful PREPARE the statement is executed and `CBTRNDYN: EXECUTE SQLCODE ` + SQLCODE (sign and 10 digits, e.g. `+0000000000`) is always displayed. Only a negative SQLCODE sets return code 8; zero or positive (e.g. +100) leaves it unchanged.

* **Category:** return-code
* **Source:** `app/app-transaction-type-db2/cbl/CBTRNDYN.cbl:94-102`
* **Fields:** SQLCODE (SQLCA); RETURN-CODE
* **Fixtures:** `fixtures/trantype-dynamic/d01-update-then-apply`, `fixtures/trantype-dynamic/d02-delete-statement`, `fixtures/trantype-dynamic/d03-bad-statement`
* **Test:**
  * *Given* DYNSQL `DELETE FROM TRANSACTION_TYPE WHERE TR_TYPE = '03'` and row 03 exists
  * *When* CBTRNDYN runs
  * *Then* `CBTRNDYN: EXECUTE SQLCODE +0000000000`; row 03 deleted; RC 0

## BR-TT-033

**Bad dynamic statement does not stop the job**

A failed PREPARE or EXECUTE only sets return code 8; CBTRNDYN still goes on to the dynamic CALL.

* **Category:** control-flow
* **Source:** `app/app-transaction-type-db2/cbl/CBTRNDYN.cbl:56-65`; `app/app-transaction-type-db2/cbl/CBTRNDYN.cbl:87-102`
* **Fields:** RETURN-CODE; PARM-LENGTH (CBTRNDYN)
* **Fixtures:** `fixtures/trantype-dynamic/d03-bad-statement`
* **Test:**
  * *Given* DYNSQL `UPDATE TRANSACTION_TYPE SET NO_SUCH_COLUMN = 'x' WHERE TR_TYPE = '01'`
  * *When* MNTTRDYN runs
  * *Then* `CBTRNDYN: EXECUTE SQLCODE -0000000400` is followed by `CBTRNDYN: CALLING COBTUPDT`

## BR-TT-034

**Dynamic CALL of the program named in PARMS**

When the run parameter is non-empty, the first PARM-LENGTH characters (at most 8) become the program name; the program displays `CBTRNDYN: CALLING ` + name and CALLs it by name at run time. MNTTRDYN passes PARMS('COBTUPDT').

* **Category:** linkage
* **Source:** `app/app-transaction-type-db2/cbl/CBTRNDYN.cbl:52-63`; `app/app-transaction-type-db2/jcl/MNTTRDYN.jcl:33-33`; `tools/stubs/DSNRUN.cbl:26-34`; `tools/stubs/DSNRUN.cbl:51-51`
* **Fields:** PARM-LENGTH, PARM-VALUE (LINKAGE PARM-AREA, CBTRNDYN); WS-PGM-NAME (CBTRNDYN); WS-PARM-LENGTH, WS-PARM-VALUE (DSNRUN)
* **Fixtures:** `fixtures/trantype-dynamic/d01-update-then-apply`
* **Test:**
  * *Given* PARMS('COBTUPDT') and INPFILE `A04Authorization`
  * *When* MNTTRDYN runs
  * *Then* `CBTRNDYN: CALLING COBTUPDT`, then COBTUPDT's `OPEN FILE OK` ... `RECORD INSERTED SUCCESSFULLY`

## BR-TT-035

**No PARMS: no call**

With an empty run parameter nothing is called; the program displays `CBTRNDYN: END` and returns (GOBACK) with the return code from the dynamic SQL.

* **Category:** control-flow
* **Source:** `app/app-transaction-type-db2/cbl/CBTRNDYN.cbl:59-65`
* **Fields:** PARM-LENGTH (CBTRNDYN); RETURN-CODE
* **Fixtures:** none yet
* **Test:**
  * *Given* `RUN PROGRAM(CBTRNDYN)` without PARMS and a valid DYNSQL statement
  * *When* the step runs
  * *Then* SYSOUT `CBTRNDYN: nn SQL LINES READ`, `CBTRNDYN: EXECUTE SQLCODE +0000000000`, `CBTRNDYN: END`; RC 0 (no fixture)

## BR-TT-036

**`CBTRNDYN: END` is never shown after the call**

Because the called COBTUPDT ends with STOP RUN, control never returns to CBTRNDYN, so `CBTRNDYN: END` does not appear whenever PARMS names COBTUPDT.

* **Category:** sysout
* **Source:** `app/app-transaction-type-db2/cbl/CBTRNDYN.cbl:62-65`; `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:97-99`
* **Fields:** -
* **Fixtures:** `fixtures/trantype-dynamic/d01-update-then-apply`, `fixtures/trantype-dynamic/d02-delete-statement`, `fixtures/trantype-dynamic/d03-bad-statement`
* **Test:**
  * *Given* PARMS('COBTUPDT')
  * *When* MNTTRDYN runs
  * *Then* the last SYSOUT lines are COBTUPDT's followed by `DSNRUN: COMMIT AT END OF TASK`; no `CBTRNDYN: END`

## BR-TT-037

**Step return code is the last value moved to RETURN-CODE**

Both programs share one RETURN-CODE. The step ends with whatever value it holds at STOP RUN: CBTRNDYN's 8 for a failed dynamic statement is overwritten by COBTUPDT's 4 when COBTUPDT then hits an error, so the job reports RC 4.

* **Category:** return-code
* **Source:** `app/app-transaction-type-db2/cbl/CBTRNDYN.cbl:93-93`; `app/app-transaction-type-db2/cbl/CBTRNDYN.cbl:100-100`; `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:232-232`; `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:97-99`
* **Fields:** RETURN-CODE
* **Fixtures:** `fixtures/trantype-dynamic/d03-bad-statement`
* **Test:**
  * *Given* a failing DYNSQL statement and a failing COBTUPDT update
  * *When* MNTTRDYN runs
  * *Then* rc.json shows MNTTRDYN STEP1 = 4 (not 8)

## BR-TT-038

**Dynamic SQL and file updates share one unit of work**

The dynamic statement and every change made by the called program are in the same unit of work and are committed together at end of task.

* **Category:** state-change
* **Source:** `app/app-transaction-type-db2/cbl/CBTRNDYN.cbl:5-12`; `app/app-transaction-type-db2/cbl/CBTRNDYN.cbl:57-63`; `app/app-transaction-type-db2/jcl/MNTTRDYN.jcl:19-21`; `tools/stubs/DSNEXIT.cbl:13-18`
* **Fields:** TR_TYPE, TR_DESCRIPTION (CARDDEMO.TRANSACTION_TYPE)
* **Fixtures:** `fixtures/trantype-dynamic/d01-update-then-apply`
* **Test:**
  * *Given* rows 01, 02, 03; DYNSQL updates 02 to `Payment - SEPA`; INPFILE `A04Authorization`
  * *When* MNTTRDYN runs
  * *Then* after commit the table holds 01 `Purchase`, 02 `Payment - SEPA`, 03 `Credit`, 04 `Authorization` (padded); RC 0

## Findings

These look like legacy defects or migration gaps. They are reproduced as they are, not fixed.

* **F1. The +100 handling contradicts the docs (BR-TT-011, BR-TT-014).** `COBTUPDT.cbl:180-184`
  and `:210-214` *do* test `SQLCODE = +100` and would display `No records found.` with RC 4.
  The golden output for `t05-delete-missing` (`RECORD DELETED SUCCESSFULLY`, RC 0) can only come from
  SQLCODE 0, so Open COBOL ESQL/PostgreSQL reports a zero-row DELETE as 0. The case README and
  `docs/BASELINE.md` say "COBTUPDT only tests for negative SQLCODE", which does not match the source.
  On Db2 z/OS the same input would give `No records found.` and RC 4. The Java port must match
  the golden output. The zero-row UPDATE path has no fixture yet.
* **F2. Open failure is not fatal (BR-TT-003).** After `OPEN FILE NOT OK` the program keeps reading
  a file that is not open. End of file never comes, so this reading of the source predicts an endless
  `ERROR: TYPE NOT VALID` loop. This was not executed.
* **F3. Stale error text (BR-TT-020).** `WS-RETURN-MSG` is never cleared before `STRING`, so a short
  message after a long one keeps the earlier tail (e.g. `No records found.TRANSACTION_TYPE table. SQLCODE: -403`).
* **F4. Return code is sticky and can be downgraded (BR-TT-019, BR-TT-037).** RC 4 is never reset
  after one bad record. In MNTTRDYN, CBTRNDYN's RC 8 for a failed dynamic statement is overwritten
  with 4 when COBTUPDT also fails (d03). Whether RC 8 survives a clean COBTUPDT run is not proven by any fixture.
* **F5. Transaction semantics differ between PostgreSQL and Db2 (BR-TT-024).** After any failed
  statement PostgreSQL rejects the rest of the unit of work (SQLCODE -400). The end-of-task COMMIT
  still prints `DSNRUN: COMMIT AT END OF TASK` but keeps nothing from that unit of work. Db2 rolls back only the
  failing statement. This also applies to MNTTRDB2: a duplicate add followed by valid records would
  lose those records off-host (no fixture covers a success after a failure). Decide on a savepoint per
  statement or failing the job. Keep the golden behaviour for parity.
* **F6. STOP RUN in a called program (BR-TT-022, BR-TT-036).** COBTUPDT ends the whole run unit,
  so CBTRNDYN's `CBTRNDYN: END` and GOBACK are never reached after a dynamic CALL.
* **F7. DYNSQL limits are silent (BR-TT-028, BR-TT-030).** Statements longer than 10 lines are
  truncated without a message. The line counter wraps at 100. Only `--` in columns 1-2 counts as a comment.
* **F8. No input validation (BR-TT-016, BR-TT-021).** The type code is documented as numeric but is not
  checked. Lowercase action codes are rejected. Descriptions are stored padded to 50 characters
  (BR-TT-008), so a row written by COBTUPDT differs from one written by dynamic SQL or the seed.
* **F9. SQLCODE values are environment-specific (BR-TT-018, BR-TT-024).** A duplicate key is -403 and a
  bad column is -400 through Open COBOL ESQL. Db2 would return -803 and -206. The golden SYSOUT records
  the off-host values.
* **F10. Unchecked CALL (BR-TT-034).** `CALL WS-PGM-NAME` has no `ON EXCEPTION`, so a misspelled PARMS
  name makes the step fail at run time. A PARMS value longer than 8 characters is cut to 8 by CBTRNDYN's
  PIC X(08) `PARM-VALUE` / `WS-PGM-NAME` (derived from the source, not executed).
