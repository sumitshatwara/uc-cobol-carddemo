# Business rules: trantype slice (BR-TRT-*)

**Scope.** Db2 transaction-type maintenance in `app/app-transaction-type-db2`: the batch update program `COBTUPDT` (job `MNTTRDB2`, chain `trantype-update`) and the ad-hoc driver `CBTRNDYN` (job `MNTTRDYN`, chain `trantype-dynamic`), which runs one dynamic SQL statement (PREPARE/EXECUTE) and then dynamically CALLs the program named in `PARMS('COBTUPDT')`. Data structures: the DCLGEN members `DCLTRTYP`/`DCLTRCAT`, the Db2 copybooks `CSDB2RWY`/`CSDB2RPY` (included by neither batch program), and the PostgreSQL translation `db/postgres/01-transaction-type.sql` of `CARDDEMO.TRANSACTION_TYPE`. Harness pieces that emulate z/OS, which this document labels as harness behaviour: `tools/stubs/DSNRUN.cbl` and `tools/stubs/DSNEXIT.cbl` (IKJEFT01 / DSN RUN with commit at end of task), `tools/esql_prep.py` (ocesql source normalisation), `tools/runjcl/runjcl.py` and `tools/parity/record.py`.

**Method.** The COBOL, JCL, DCLGEN and stub sources were read paragraph by paragraph. Each observable behaviour (SQL effects, SYSOUT lines, return codes, edge cases) was cross-checked against the golden files under `fixtures/trantype-update/t01..t07` and `fixtures/trantype-dynamic/d01..d03`; where a README disagrees with `expected/`, the expected files were used. Line numbers refer to the integration branch.

**Run status.** Both chains are executable in the Docker estate (GnuCOBOL 3 + Open COBOL ESQL + PostgreSQL 15). The golden cases cited under *Proven by* come from that estate. Rules marked `source-only` have no golden case; several were also checked by off-host scratch runs of the unmodified legacy programs (fixtures and tracked files untouched), and the rule text says so. Nothing ran on Db2 or z/OS: statements about Db2 SQLCODEs (-803, +100) come from the source and the documentation, not from a run.

**Summary by category.**
| Category | Rules |
|---|---|
| control-flow | 8 |
| data-mapping | 6 |
| edge-case | 10 |
| return-code | 7 |
| state-change | 6 |
| sysout | 5 |
| validation | 3 |
| **total** | **45** |

## Rules

### BR-TRT-001 INPFILE record layout

**Rule**: Each INPFILE record is a fixed 53-byte card read INTO WS-INPUT-REC: column 1 is the action code (INPUT-REC-TYPE, X(1)), columns 2-3 the transaction type code (INPUT-REC-NUMBER, X(2)) and columns 4-53 the description (INPUT-REC-DESC, X(50)). The JCL header documents A=add, U=update, D=delete, *=comment.

**Source**: `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:39-46`; `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:71-77`; `app/app-transaction-type-db2/jcl/MNTTRDB2.jcl:8-18`; `tools/runjcl/chains.json:73-75`

**Fields**: `INPUT-TYPE`, `INPUT-TR-NUMBER`, `INPUT-TR-DESC`, `INPUT-REC-TYPE`, `INPUT-REC-NUMBER`, `INPUT-REC-DESC`

**Category**: data-mapping

**Test**:
- Given INPFILE card `A04Authorization`
- When COBTUPDT reads the card
- Then INPUT-REC-TYPE='A', INPUT-REC-NUMBER='04', INPUT-REC-DESC='Authorization' followed by 37 spaces

**Proven by**: fixtures/trantype-update/t01-add

### BR-TRT-002 Short cards are space-padded to LRECL 53

**Rule**: Harness behaviour: the fixture loader writes INPFILE as fixed 53-byte records, right-padding shorter lines with spaces and truncating longer ones, which matches a RECFM=FB LRECL=53 dataset on z/OS. A card shorter than 53 bytes therefore yields a space-filled description, and a one-character type code leaves the second digit blank.

**Source**: `tools/parity/record.py:46-51`; `tools/runjcl/chains.json:73-75`

**Fields**: `INPUT-REC-DESC`, `INPUT-REC-NUMBER`

**Category**: data-mapping

**Test**:
- Given INPFILE line `D03` (3 bytes)
- When the case is materialised and COBTUPDT reads it
- Then the record is `D03` + 50 spaces and SYSOUT shows `PROCESSING   D03` padded to 66 characters

**Proven by**: fixtures/trantype-update/t03-delete

### BR-TRT-003 Sequential read loop until end of file

**Rule**: COBTUPDT performs a priming read, then repeats treat-record / read-next until the AT END condition sets LASTREC='Y'. Records are processed strictly in file order, one at a time; there is no sorting, de-duplication or look-ahead.

**Source**: `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:91-96`; `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:100-103`

**Fields**: `LASTREC`, `WS-INPUT-REC`

**Category**: control-flow

**Test**:
- Given INPFILE `A07Chargeback`, `U01Purchase - card present`, `*  skip`, `D02`
- When MNTTRDB2 runs
- Then the four records are processed in that order: insert 07, update 01, comment, delete 02

**Proven by**: fixtures/trantype-update/t07-mixed-batch

### BR-TRT-004 PROCESSING echo for every record

**Rule**: Every record read (including comments and invalid actions) is echoed to SYSOUT as the literal `PROCESSING   ` (PROCESSING + 3 spaces) followed by the full 53-byte WS-INPUT-REC, trailing spaces included. Nothing is echoed for the end-of-file read.

**Source**: `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:104-106`

**Fields**: `WS-INPUT-REC`

**Category**: sysout

**Test**:
- Given INPFILE `*  Nightly maintenance batch`
- When the record is read
- Then SYSOUT line `PROCESSING   *  Nightly maintenance batch` padded with spaces to 66 characters

**Proven by**: fixtures/trantype-update/t04-comments

### BR-TRT-005 Open status message, no stop on open failure

**Rule**: After OPEN INPUT the program displays `OPEN FILE OK` when WS-INF-STATUS='00', otherwise `OPEN FILE NOT OK`. In both cases processing continues into the read loop; no return code is set and the run is not stopped.

**Source**: `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:82-89`; `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:31-34`; `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:67-69`

**Fields**: `WS-INF-STATUS`, `WS-INF-STAT1`, `WS-INF-STAT2`

**Category**: sysout

**Test**:
- Given a valid INPFILE dataset
- When COBTUPDT opens INPFILE
- Then SYSOUT line 2 is `OPEN FILE OK`

**Proven by**: fixtures/trantype-update/t01-add

**Finding**: On an open failure the program only prints `OPEN FILE NOT OK` and carries on reading an unopened file with no RC and no stop. Looks wrong; it must be reproduced (source-only path).

### BR-TRT-006 Action dispatch on column 1 (case-sensitive)

**Rule**: The action code is evaluated exactly: 'A' -> insert, 'U' -> update, 'D' -> delete, '*' -> comment, anything else (including lower-case letters and space) -> invalid. Each valid action first displays `ADDING RECORD`, `UPDATING RECORD`, `DELETING RECORD` or `IGNORING COMMENTED LINE`. Confirmed by an off-host scratch run of the legacy estate (not a golden case).

**Source**: `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:109-129`

**Fields**: `INPUT-REC-TYPE`

**Category**: validation

**Test**:
- Given INPFILE `a08lower`
- When COBTUPDT evaluates the action code
- Then lower-case 'a' is not an add: SYSOUT shows `ERROR: TYPE NOT VALID` and no row 08 is inserted

**Proven by**: source-only

### BR-TRT-007 Comment cards are skipped

**Rule**: A card with '*' in column 1 displays `IGNORING COMMENTED LINE` and performs no SQL; it does not change the return code.

**Source**: `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:120-121`

**Fields**: `INPUT-REC-TYPE`

**Category**: control-flow

**Test**:
- Given INPFILE `*  Nightly maintenance batch`, `A06Fee`, `*  end`
- When MNTTRDB2 runs
- Then both comments print `IGNORING COMMENTED LINE`, only type 06 is inserted, step RC 0

**Proven by**: fixtures/trantype-update/t04-comments

### BR-TRT-008 Invalid action code reports an error and sets RC 4

**Rule**: Any action code other than A/U/D/* builds `ERROR: TYPE NOT VALID` into WS-RETURN-MSG, performs 9999-ABEND which DISPLAYs the 80-byte WS-RETURN-MSG and moves 4 to RETURN-CODE, then continues with the next record. Confirmed by an off-host scratch run of the legacy estate (not a golden case).

**Source**: `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:122-128`; `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:230-233`

**Fields**: `INPUT-REC-TYPE`, `WS-RETURN-MSG`, `RETURN-CODE`

**Category**: validation

**Test**:
- Given seeded table 01/02/03 and INPFILE `X01bad`, `*  c`
- When MNTTRDB2 runs
- Then SYSOUT shows `ERROR: TYPE NOT VALID` padded to 80 characters, then the comment is ignored; step RC 4; table unchanged

**Proven by**: source-only

### BR-TRT-009 Blank card is an invalid action

**Rule**: A blank (all-space) card is not treated as a comment or skipped: it is echoed as `PROCESSING` plus spaces and rejected with `ERROR: TYPE NOT VALID` (RC 4 unless a later SQL statement resets it, see BR-TRT-024). Confirmed by an off-host scratch run of the legacy estate (not a golden case).

**Source**: `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:104-106`; `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:122-128`

**Fields**: `INPUT-REC-TYPE`

**Category**: edge-case

**Test**:
- Given INPFILE containing one empty line
- When COBTUPDT reads it
- Then SYSOUT shows `PROCESSING` + 56 spaces, then `ERROR: TYPE NOT VALID`

**Proven by**: source-only

### BR-TRT-010 Add inserts a TRANSACTION_TYPE row

**Rule**: Action 'A' executes INSERT INTO CARDDEMO.TRANSACTION_TYPE (TR_TYPE, TR_DESCRIPTION) VALUES (:INPUT-REC-NUMBER, :INPUT-REC-DESC). On SQLCODE 0 it displays `RECORD INSERTED SUCCESSFULLY`.

**Source**: `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:111-113`; `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:137-153`; `db/postgres/01-transaction-type.sql:6-9`

**Fields**: `INPUT-REC-NUMBER`, `INPUT-REC-DESC`, `TR_TYPE`, `TR_DESCRIPTION`

**Category**: state-change

**Test**:
- Given seeded table 01/02/03 and INPFILE `A04Authorization`, `A05Refund`
- When MNTTRDB2 runs
- Then SYSOUT shows `ADDING RECORD` / `RECORD INSERTED SUCCESSFULLY` twice; rows 04 and 05 exist; step RC 0

**Proven by**: fixtures/trantype-update/t01-add

### BR-TRT-011 Descriptions are stored space-padded to 50 bytes

**Rule**: COBTUPDT binds the fixed-length PIC X(50) INPUT-REC-DESC, not the DCLGEN VARCHAR structure (DCL-TR-DESCRIPTION-LEN/-TEXT is included but never used), and Open COBOL ESQL sends the full 50-byte host variable. Every row written by COBTUPDT therefore holds a 50-character TR_DESCRIPTION with trailing spaces, whereas seed rows and rows set by dynamic SQL literals are unpadded.

**Source**: `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:76-77`; `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:145-146`; `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:173-173`; `app/app-transaction-type-db2/dcl/DCLTRTYP.dcl:35-46`; `db/postgres/01-transaction-type.sql:8-8`

**Fields**: `INPUT-REC-DESC`, `DCL-TR-DESCRIPTION-LEN`, `DCL-TR-DESCRIPTION-TEXT`, `TR_DESCRIPTION`

**Category**: data-mapping

**Test**:
- Given INPFILE `U02Payment - bank transfer`
- When the update is applied
- Then TR_DESCRIPTION for 02 is `Payment - bank transfer` followed by 27 spaces (50 characters), while 01 stays `Purchase` unpadded

**Proven by**: fixtures/trantype-update/t02-update, fixtures/trantype-update/t01-add

**Finding**: The VARCHAR column receives trailing blanks and the DCLGEN length field is ignored, so the table mixes padded and unpadded descriptions. Must be reproduced (golden DB snapshots contain the padding).

### BR-TRT-012 No validation of type code or description

**Rule**: COBTUPDT does not check that the type code is numeric (the JCL comment says it should be) or non-blank, nor that the description is non-blank. A blank type code inserts TR_TYPE '  '; a non-numeric code such as '9X' is inserted as is; a card with no description inserts 50 spaces (NOT NULL is still satisfied). Confirmed by an off-host scratch run of the legacy estate (not a golden case).

**Source**: `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:109-113`; `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:137-148`; `app/app-transaction-type-db2/jcl/MNTTRDB2.jcl:16-16`

**Fields**: `INPUT-REC-NUMBER`, `INPUT-REC-DESC`, `TR_TYPE`

**Category**: validation

**Test**:
- Given INPFILE `A  NoNumber` and `A9Xshort`
- When MNTTRDB2 runs
- Then rows TR_TYPE='  ' (description `NoNumber` padded) and TR_TYPE='9X' (description `short` padded) are inserted, `RECORD INSERTED SUCCESSFULLY` twice, step RC 0

**Proven by**: source-only

**Finding**: Type codes documented as numeric are not validated; blank and alphanumeric keys are accepted. Must be reproduced.

### BR-TRT-013 Duplicate add fails with negative SQLCODE (-403 under ocesql)

**Rule**: A primary-key violation on INSERT gives SQLCODE < 0. Through Open COBOL ESQL/PostgreSQL the value is -403 (Db2 would return -803). COBTUPDT reports it via 9999-ABEND and sets RC 4; the row is not inserted.

**Source**: `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:151-163`; `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:230-233`; `db/postgres/01-transaction-type.sql:9-9`

**Fields**: `TR_TYPE`, `SQLCODE`, `WS-VAR-SQLCODE`, `RETURN-CODE`

**Category**: return-code

**Test**:
- Given seeded table 01/02/03 and INPFILE `A01Purchase`
- When MNTTRDB2 runs
- Then SYSOUT `Error accessing: TRANSACTION_TYPE table. SQLCODE: -403`; step RC 4; table unchanged

**Proven by**: fixtures/trantype-update/t06-duplicate-add

**Finding**: The SQLCODE printed is the ocesql value -403, not Db2's -803. The golden SYSOUT records -403 and must be reproduced.

### BR-TRT-014 SQL error message format

**Rule**: On SQLCODE < 0 the program STRINGs `Error accessing:` + ` TRANSACTION_TYPE table. SQLCODE:` + WS-VAR-SQLCODE (edited PIC ----9, 5 characters, so -403 becomes ` -403`) into the 80-byte WS-RETURN-MSG, and 9999-ABEND DISPLAYs the whole 80-byte field (trailing spaces included).

**Source**: `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:61-65`; `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:155-161`; `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:186-192`; `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:217-223`; `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:230-231`

**Fields**: `WS-RETURN-MSG`, `WS-VAR-SQLCODE`, `SQLCODE`

**Category**: sysout

**Test**:
- Given SQLCODE -403 on an insert
- When the error message is built and displayed
- Then SYSOUT line is `Error accessing: TRANSACTION_TYPE table. SQLCODE: -403` padded with spaces to 80 characters

**Proven by**: fixtures/trantype-update/t06-duplicate-add

### BR-TRT-015 WS-RETURN-MSG is never cleared between messages

**Rule**: STRING writes from position 1 without clearing WS-RETURN-MSG, so a shorter message overwrites only the start of the previous one. After a 54-character SQL error, an invalid action shows `ERROR: TYPE NOT VALID` followed by the leftover tail of the earlier text. Confirmed by an off-host scratch run of the legacy estate (not a golden case).

**Source**: `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:60-62`; `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:123-128`; `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:155-161`; `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:230-231`

**Fields**: `WS-RETURN-MSG`

**Category**: edge-case

**Test**:
- Given seeded table 01/02/03 and INPFILE `A01Dup`, `X01bad`
- When MNTTRDB2 runs
- Then SYSOUT shows `Error accessing: TRANSACTION_TYPE table. SQLCODE: -403` then `ERROR: TYPE NOT VALIDSACTION_TYPE table. SQLCODE: -403`

**Proven by**: source-only

**Finding**: Error text is corrupted by residue from the previous message. Looks wrong; operators see it in SYSOUT, so it must be reproduced byte for byte.

### BR-TRT-016 Positive SQLCODE on insert is silently ignored

**Rule**: The insert EVALUATE only handles SQLCODE = 0 and SQLCODE < 0. A positive SQLCODE (a warning) prints no success or error line and leaves RETURN-CODE unchanged.

**Source**: `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:151-163`

**Fields**: `SQLCODE`

**Category**: edge-case

**Test**:
- Given an INSERT that returns a positive warning SQLCODE
- When 10031-INSERT-DB evaluates it
- Then only `ADDING RECORD` is shown for the card and RC is unchanged

**Proven by**: source-only

### BR-TRT-017 Update replaces the description of an existing type

**Rule**: Action 'U' executes UPDATE CARDDEMO.TRANSACTION_TYPE SET TR_DESCRIPTION = :INPUT-REC-DESC WHERE TR_TYPE = :INPUT-REC-NUMBER and on SQLCODE 0 displays `RECORD UPDATED SUCCESSFULLY`. TR_TYPE itself is never changed.

**Source**: `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:114-116`; `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:171-179`

**Fields**: `INPUT-REC-NUMBER`, `INPUT-REC-DESC`, `TR_TYPE`, `TR_DESCRIPTION`

**Category**: state-change

**Test**:
- Given seeded 02='Payment' and INPFILE `U02Payment - bank transfer`
- When MNTTRDB2 runs
- Then `UPDATING RECORD` / `RECORD UPDATED SUCCESSFULLY`; 02 now holds `Payment - bank transfer` padded to 50; RC 0

**Proven by**: fixtures/trantype-update/t02-update

### BR-TRT-018 Update of a missing type reports success under ocesql

**Rule**: The update has a +100 branch (`No records found.` + RC 4), but through Open COBOL ESQL/PostgreSQL a searched UPDATE that matches no row returns SQLCODE 0, so `RECORD UPDATED SUCCESSFULLY` prints, nothing changes and RC stays 0. On Db2 the same card would give +100, `No records found.` and RC 4. Confirmed by an off-host scratch run of the legacy estate (not a golden case).

**Source**: `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:176-184`

**Fields**: `SQLCODE`, `WS-RETURN-MSG`, `RETURN-CODE`

**Category**: edge-case

**Test**:
- Given seeded table 01/02/03 and INPFILE `U09Nothing`
- When MNTTRDB2 runs
- Then SYSOUT `UPDATING RECORD` / `RECORD UPDATED SUCCESSFULLY`; table unchanged; step RC 0

**Proven by**: source-only

**Finding**: A no-op update is reported as successful and the +100 branch can never fire under the harness. Must be reproduced as the PostgreSQL-recorded behaviour.

### BR-TRT-019 Update SQL error reports and sets RC 4

**Rule**: A negative SQLCODE on UPDATE prints the standard `Error accessing: TRANSACTION_TYPE table. SQLCODE:` message and sets RETURN-CODE 4; the row is not changed.

**Source**: `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:185-193`; `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:230-233`

**Fields**: `SQLCODE`, `WS-VAR-SQLCODE`, `RETURN-CODE`

**Category**: return-code

**Test**:
- Given a transaction already aborted by a failed dynamic statement and INPFILE `U01Purchase - updated`
- When COBTUPDT runs the update
- Then SYSOUT `Error accessing: TRANSACTION_TYPE table. SQLCODE: -400`; 01 stays `Purchase`; step RC 4

**Proven by**: fixtures/trantype-dynamic/d03-bad-statement

### BR-TRT-020 Delete removes a type by key

**Rule**: Action 'D' executes DELETE FROM CARDDEMO.TRANSACTION_TYPE WHERE TR_TYPE = :INPUT-REC-NUMBER (the description columns are ignored) and on SQLCODE 0 displays `RECORD DELETED SUCCESSFULLY`.

**Source**: `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:117-119`; `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:201-209`

**Fields**: `INPUT-REC-NUMBER`, `TR_TYPE`

**Category**: state-change

**Test**:
- Given seeded table 01/02/03 and INPFILE `D03`
- When MNTTRDB2 runs
- Then `DELETING RECORD` / `RECORD DELETED SUCCESSFULLY`; rows 01 and 02 remain; RC 0

**Proven by**: fixtures/trantype-update/t03-delete

### BR-TRT-021 Delete of a missing type reports success

**Rule**: Deleting a type that does not exist prints `RECORD DELETED SUCCESSFULLY` with RC 0 and leaves the table unchanged. Under Open COBOL ESQL/PostgreSQL a 0-row searched DELETE returns SQLCODE 0, so the coded +100 branch (`No records found.` + RC 4) is never reached; on Db2 the +100 branch would fire.

**Source**: `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:205-214`

**Fields**: `SQLCODE`, `TR_TYPE`

**Category**: edge-case

**Test**:
- Given seeded table 01/02/03 and INPFILE `D09`
- When MNTTRDB2 runs
- Then SYSOUT `DELETING RECORD` / `RECORD DELETED SUCCESSFULLY`; table unchanged; step RC 0

**Proven by**: fixtures/trantype-update/t05-delete-missing

**Finding**: Success is reported for a delete that removed nothing. The fixture README attributes this to COBTUPDT testing only SQLCODE < 0, but the source does test +100; the real cause is that ocesql returns 0. Must be reproduced.

### BR-TRT-022 Delete SQL error reports and sets RC 4

**Rule**: A negative SQLCODE on DELETE (for example a foreign-key restriction from TRANSACTION_TYPE_CATEGOR, or -400 in an aborted transaction) prints the standard SQL error message and sets RETURN-CODE 4.

**Source**: `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:216-224`; `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:230-233`; `db/postgres/01-transaction-type.sql:16-17`

**Fields**: `SQLCODE`, `WS-VAR-SQLCODE`, `RETURN-CODE`

**Category**: return-code

**Test**:
- Given a type 01 that is referenced by a transaction_type_categor row and INPFILE `D01`
- When COBTUPDT runs the delete
- Then SYSOUT `Error accessing: TRANSACTION_TYPE table. SQLCODE:` with the negative code; row 01 kept (and see BR-TRT-029 for the whole-run rollback); RC 4

**Proven by**: source-only

### BR-TRT-023 Errors never stop the batch

**Rule**: Despite its name, 9999-ABEND only displays the message and moves 4 to RETURN-CODE; control returns to the read loop and the remaining cards are processed. Confirmed by an off-host scratch run of the legacy estate (not a golden case).

**Source**: `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:230-233`; `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:93-96`

**Fields**: `RETURN-CODE`, `LASTREC`

**Category**: control-flow

**Test**:
- Given INPFILE `A04Authorization`, `A01Dup`, `X01bad`, `A05Refund`
- When MNTTRDB2 runs
- Then all four cards are echoed and processed after the -403 on `A01Dup`; there is no abend

**Proven by**: source-only

**Finding**: The paragraph named ABEND does not abend. Must be reproduced (continue after error).

### BR-TRT-024 RETURN-CODE 4 is reset by any later SQL statement

**Rule**: RC 4 is not sticky. Each EXEC SQL is precompiled by ocesql into CALLs to the ocesql runtime, and each CALL overwrites the RETURN-CODE special register (normally with 0). The step RC therefore reflects only an error raised after the last executed SQL statement: an invalid card followed by a successful SQL card ends with RC 0; an invalid card followed only by comments ends with RC 4. Confirmed by an off-host scratch run of the legacy estate (not a golden case).

**Source**: `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:232-232`; `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:137-148`; `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:201-204`

**Fields**: `RETURN-CODE`

**Category**: return-code

**Test**:
- Given seeded table 01/02/03 and INPFILE `X01bad`, `D99`
- When MNTTRDB2 runs
- Then SYSOUT shows `ERROR: TYPE NOT VALID` then `RECORD DELETED SUCCESSFULLY`; step RC is 0, not 4

**Proven by**: source-only

**Finding**: Earlier errors are lost from the step RC whenever a later SQL statement runs. Looks wrong; the RC must be reproduced as observed under the harness.

### BR-TRT-025 End of input closes the file and STOP RUN

**Rule**: At end of file COBTUPDT closes INPFILE and executes STOP RUN (the EXIT before it is a no-op). An empty INPFILE gives only `OPEN FILE OK` and RC 0.

**Source**: `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:97-99`; `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:234-236`

**Fields**: `LASTREC`

**Category**: control-flow

**Test**:
- Given an empty INPFILE
- When COBTUPDT runs
- Then SYSOUT from COBTUPDT is just `OPEN FILE OK`; no SQL; RC 0

**Proven by**: fixtures/trantype-dynamic/d02-delete-statement

### BR-TRT-026 MNTTRDB2 step and RC

**Rule**: MNTTRDB2 has one step, STEP1 (EXEC PGM=IKJEFT01) with SYSTSIN `DSN SYSTEM(DAZ1)` / `RUN PROGRAM(COBTUPDT) PLAN(CARDDEMO)` and INPFILE DD. Harness behaviour: the runner maps the RUN subcommand to `cobcrun DSNRUN COBTUPDT`; the STEP1 RC (and job maxrc) is the RETURN-CODE in effect at COBTUPDT's STOP RUN.

**Source**: `app/app-transaction-type-db2/jcl/MNTTRDB2.jcl:21-30`; `tools/runjcl/runjcl.py:704-715`

**Fields**: `RETURN-CODE`

**Category**: return-code

**Test**:
- Given INPFILE `A01Purchase` against the seeded table
- When MNTTRDB2 runs
- Then rc.json is `{"MNTTRDB2": {"maxrc": 4, "steps": {"STEP1": 4}}}`

**Proven by**: fixtures/trantype-update/t06-duplicate-add, fixtures/trantype-update/t01-add

### BR-TRT-027 DSN attach banner and connect failure (harness)

**Rule**: Harness behaviour emulating IKJEFT01/DSN: DSNRUN reads the program name and optional PARMS from its command line, connects to PostgreSQL using OCDB_NAME/OCDB_USER/OCDB_PASS and displays `DSNRUN: DSN ATTACHED, RUNNING <program>` before calling it. If the connect fails it displays `DSNRUN: DATABASE CONNECT FAILED <sqlcode>` and stops with RC 12 without running the program.

**Source**: `tools/stubs/DSNRUN.cbl:31-48`

**Fields**: `WS-PGM-NAME`, `WS-DB-NAME`, `WS-DB-USER`, `WS-DB-PASS`, `RETURN-CODE`

**Category**: sysout

**Test**:
- Given a reachable database
- When MNTTRDB2 STEP1 starts
- Then SYSOUT line 1 is `DSNRUN: DSN ATTACHED, RUNNING COBTUPDT`

**Proven by**: fixtures/trantype-update/t01-add

### BR-TRT-028 Commit at end of task (harness)

**Rule**: Harness behaviour: the application programs never COMMIT. DSNRUN installs DSNEXIT with CBL_EXIT_PROC, so when the program ends (STOP RUN) DSNEXIT issues COMMIT, displays `DSNRUN: COMMIT AT END OF TASK` (or `DSNRUN: COMMIT FAILED <sqlcode>`) and disconnects. This mirrors the implicit commit of the TSO DSN command at normal end of task. All changes of the run are one unit of work.

**Source**: `tools/stubs/DSNRUN.cbl:49-52`; `tools/stubs/DSNEXIT.cbl:13-21`; `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:97-99`

**Fields**: `SQLCODE`

**Category**: state-change

**Test**:
- Given INPFILE `A04Authorization`, `A05Refund`
- When COBTUPDT reaches STOP RUN
- Then the last SYSOUT line is `DSNRUN: COMMIT AT END OF TASK` and rows 04/05 are persisted

**Proven by**: fixtures/trantype-update/t01-add

### BR-TRT-029 Failed statement aborts the whole PostgreSQL unit of work

**Rule**: Under PostgreSQL any failed SQL statement puts the transaction into the aborted state: every later statement in the same run gets SQLCODE -400, and the end-of-task COMMIT silently becomes a rollback, so earlier successful inserts/updates/deletes of the run are lost too, although SYSOUT still says `RECORD INSERTED SUCCESSFULLY` and `DSNRUN: COMMIT AT END OF TASK`. Db2 would roll back only the failing statement and commit the rest. The rollback of earlier successes was confirmed by an off-host scratch run.

**Source**: `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:149-163`; `tools/stubs/DSNEXIT.cbl:14-19`; `app/app-transaction-type-db2/cbl/CBTRNDYN.cbl:95-101`

**Fields**: `SQLCODE`, `TR_TYPE`, `TR_DESCRIPTION`

**Category**: state-change

**Test**:
- Given seeded table 01/02/03 and INPFILE `A04Authorization`, `A01Dup`, `X01bad`, `A05Refund`
- When MNTTRDB2 runs
- Then 04 prints `RECORD INSERTED SUCCESSFULLY`, 01 fails with -403, 05 fails with -400, SYSOUT ends `DSNRUN: COMMIT AT END OF TASK`, but the table still holds only 01/02/03; step RC 4

**Proven by**: fixtures/trantype-dynamic/d03-bad-statement

**Finding**: A single error discards the whole run while SYSOUT reports success and commit. This is a PostgreSQL vs Db2 semantic gap recorded in the golden files; it must be reproduced (or changed only by an explicit migration decision).

### BR-TRT-030 MNTTRDYN step, DDs and PARMS

**Rule**: MNTTRDYN STEP1 runs IKJEFT01 with `DSN SYSTEM(DAZ1)` / `RUN PROGRAM(CBTRNDYN) PLAN(CARDDEMO) PARMS('COBTUPDT')` / `END`, and allocates DYNSQL (statement cards) and INPFILE (COBTUPDT update cards). Harness behaviour: the runner passes the PARMS text as a second argument and DSNRUN builds the z/OS halfword-length parameter area (PARM-LENGTH 8, PARM-VALUE 'COBTUPDT') passed USING to CBTRNDYN.

**Source**: `app/app-transaction-type-db2/jcl/MNTTRDYN.jcl:23-34`; `tools/stubs/DSNRUN.cbl:26-35`; `tools/stubs/DSNRUN.cbl:51-51`; `app/app-transaction-type-db2/cbl/CBTRNDYN.cbl:52-56`; `tools/runjcl/runjcl.py:707-711`

**Fields**: `PARM-LENGTH`, `PARM-VALUE`, `WS-PARM-LENGTH`, `WS-PARM-VALUE`

**Category**: control-flow

**Test**:
- Given the MNTTRDYN JCL
- When STEP1 runs
- Then SYSOUT line 1 is `DSNRUN: DSN ATTACHED, RUNNING CBTRNDYN` and CBTRNDYN receives PARM-LENGTH=8

**Proven by**: fixtures/trantype-dynamic/d01-update-then-apply

### BR-TRT-031 DYNSQL card reading and comment cards

**Rule**: CBTRNDYN reads every 80-byte DYNSQL card to EOF. A card whose columns 1-2 are `--` is skipped; every other card contributes columns 1-72 plus one space to WS-SQL-STMT-ARR (columns 73-80, the sequence-number area, are dropped). All cards form one statement; WS-SQL-STMT-LEN = pointer - 1, i.e. 73 bytes per card including trailing spaces.

**Source**: `app/app-transaction-type-db2/cbl/CBTRNDYN.cbl:34-40`; `app/app-transaction-type-db2/cbl/CBTRNDYN.cbl:48-51`; `app/app-transaction-type-db2/cbl/CBTRNDYN.cbl:67-83`; `tools/runjcl/chains.json:96-96`

**Fields**: `DYNSQL-REC`, `WS-SQL-STMT-LEN`, `WS-SQL-STMT-ARR`, `WS-SQL-POS`

**Category**: data-mapping

**Test**:
- Given DYNSQL `-- ad-hoc fix requested by operations`, `UPDATE TRANSACTION_TYPE`, `   SET TR_DESCRIPTION = 'Payment - SEPA'`, ` WHERE TR_TYPE = '02'`
- When CBTRNDYN reads DYNSQL
- Then the comment is skipped and the three remaining cards are concatenated into one 219-byte statement that updates type 02

**Proven by**: fixtures/trantype-dynamic/d01-update-then-apply

### BR-TRT-032 SQL lines read message

**Rule**: After reading DYNSQL the program displays `CBTRNDYN: ` + WS-LINE-COUNT (PIC 9(02), two digits with leading zero) + ` SQL LINES READ`. The count includes every non-comment card, blank cards too.

**Source**: `app/app-transaction-type-db2/cbl/CBTRNDYN.cbl:45-45`; `app/app-transaction-type-db2/cbl/CBTRNDYN.cbl:78-78`; `app/app-transaction-type-db2/cbl/CBTRNDYN.cbl:84-84`

**Fields**: `WS-LINE-COUNT`

**Category**: sysout

**Test**:
- Given DYNSQL with one comment card and three statement cards
- When CBTRNDYN finishes reading
- Then SYSOUT `CBTRNDYN: 03 SQL LINES READ`

**Proven by**: fixtures/trantype-dynamic/d01-update-then-apply, fixtures/trantype-dynamic/d02-delete-statement

### BR-TRT-033 Blank cards and columns 73-80

**Rule**: A blank DYNSQL card is not a comment: it adds 73 spaces to the statement and is counted in WS-LINE-COUNT. Text in columns 73-80 never reaches the SQL text. Confirmed by an off-host scratch run of the legacy estate (not a golden case).

**Source**: `app/app-transaction-type-db2/cbl/CBTRNDYN.cbl:73-78`

**Fields**: `DYNSQL-REC`, `WS-LINE-COUNT`

**Category**: edge-case

**Test**:
- Given DYNSQL `UPDATE TRANSACTION_TYPE` padded to col 72 + `SEQ00010` in cols 73-80, a blank card, `   SET TR_DESCRIPTION = 'Seq' WHERE TR_TYPE = '03'`
- When MNTTRDYN runs with an empty INPFILE
- Then SYSOUT `CBTRNDYN: 03 SQL LINES READ` and `CBTRNDYN: EXECUTE SQLCODE +0000000000`; 03 becomes `Seq`; RC 0

**Proven by**: source-only

### BR-TRT-034 Only column-1 `--` is a comment; inline `--` swallows the rest

**Rule**: Comment detection looks only at columns 1-2. Because all cards are joined into one line with no newline, a `--` that starts later on a card (an inline or indented comment) comments out everything after it, including the following cards.

**Source**: `app/app-transaction-type-db2/cbl/CBTRNDYN.cbl:73-77`

**Fields**: `DYNSQL-REC`, `WS-SQL-STMT-ARR`

**Category**: edge-case

**Test**:
- Given DYNSQL `DELETE FROM TRANSACTION_TYPE  -- remove fee` then `WHERE TR_TYPE = '06'`
- When CBTRNDYN builds and executes the statement
- Then the statement text becomes `DELETE FROM TRANSACTION_TYPE  -- remove fee ... WHERE TR_TYPE = '06'` all on one line, so the WHERE clause is commented out and the DELETE has no WHERE clause

**Proven by**: source-only

**Finding**: Indented or trailing `--` comments silently change the statement (a WHERE clause can be lost). Looks dangerous; must be reproduced (source-only).

### BR-TRT-035 Statement buffer limit 800 bytes; line count wraps at 100

**Rule**: WS-SQL-STMT-ARR is X(800) and each card takes 73 bytes, so only 10 full cards fit; the 11th is cut after 70 bytes and later cards are dropped (STRING ... WITH POINTER with no ON OVERFLOW). WS-SQL-STMT-LEN stops at 800. WS-LINE-COUNT is PIC 9(02) with no ON SIZE ERROR, so a 100th card wraps the count to 00.

**Source**: `app/app-transaction-type-db2/cbl/CBTRNDYN.cbl:45-45`; `app/app-transaction-type-db2/cbl/CBTRNDYN.cbl:48-51`; `app/app-transaction-type-db2/cbl/CBTRNDYN.cbl:74-78`; `app/app-transaction-type-db2/cbl/CBTRNDYN.cbl:83-83`

**Fields**: `WS-SQL-STMT-ARR`, `WS-SQL-STMT-LEN`, `WS-SQL-POS`, `WS-LINE-COUNT`

**Category**: edge-case

**Test**:
- Given DYNSQL with 12 non-comment cards
- When CBTRNDYN reads them
- Then WS-SQL-STMT-LEN = 800, text from card 11 column 71 onward is lost without a message, and SYSOUT still says `CBTRNDYN: 12 SQL LINES READ`

**Proven by**: source-only

**Finding**: Long statements are silently truncated and the line count is not limited. Must be reproduced (source-only).

### BR-TRT-036 PREPARE then EXECUTE, PREPARE failure path

**Rule**: CBTRNDYN runs PREPARE DYNSTMT FROM :WS-SQL-STMT. If SQLCODE is not 0 it displays `CBTRNDYN: PREPARE FAILED SQLCODE ` + SQLCODE, moves 8 to RETURN-CODE and skips EXECUTE. Otherwise it runs EXECUTE DYNSTMT and always displays `CBTRNDYN: EXECUTE SQLCODE ` + SQLCODE (sign followed by 10 digits under ocesql, e.g. `+0000000000`).

**Source**: `app/app-transaction-type-db2/cbl/CBTRNDYN.cbl:87-98`

**Fields**: `WS-SQL-STMT`, `SQLCODE`, `RETURN-CODE`

**Category**: control-flow

**Test**:
- Given DYNSQL `DELETE FROM TRANSACTION_TYPE WHERE TR_TYPE = '03'`
- When MNTTRDYN runs
- Then SYSOUT `CBTRNDYN: EXECUTE SQLCODE +0000000000`; row 03 is deleted

**Proven by**: fixtures/trantype-dynamic/d02-delete-statement

### BR-TRT-037 Statement errors surface at EXECUTE, not PREPARE, under ocesql

**Rule**: Under Open COBOL ESQL the PREPARE returned SQLCODE 0 in every observed case, so the `PREPARE FAILED` branch was never reached: an unknown column surfaces at EXECUTE as -400 (golden d03) and an empty DYNSQL (statement length 0) surfaces at EXECUTE as -212 (scratch run). Db2 would normally reject such statements at PREPARE time with its own SQLCODEs.

**Source**: `app/app-transaction-type-db2/cbl/CBTRNDYN.cbl:88-101`

**Fields**: `SQLCODE`, `WS-SQL-STMT-LEN`

**Category**: edge-case

**Test**:
- Given DYNSQL `UPDATE TRANSACTION_TYPE SET NO_SUCH_COLUMN = 'x' WHERE TR_TYPE = '01'`
- When CBTRNDYN prepares and executes it
- Then no PREPARE FAILED line; SYSOUT `CBTRNDYN: EXECUTE SQLCODE -0000000400`

**Proven by**: fixtures/trantype-dynamic/d03-bad-statement

**Finding**: The PREPARE error path was not reached under the harness and the codes differ from Db2 (-400/-212). The recorded ocesql behaviour must be reproduced.

### BR-TRT-038 EXECUTE SQLCODE < 0 sets RC 8; non-negative does not

**Rule**: After EXECUTE only a negative SQLCODE moves 8 to RETURN-CODE. SQLCODE 0, including a dynamic UPDATE/DELETE that matches no row under ocesql, leaves RC unchanged. In every case the program continues to the dynamic CALL. Confirmed by an off-host scratch run of the legacy estate (not a golden case).

**Source**: `app/app-transaction-type-db2/cbl/CBTRNDYN.cbl:98-101`

**Fields**: `SQLCODE`, `RETURN-CODE`

**Category**: return-code

**Test**:
- Given DYNSQL `UPDATE TRANSACTION_TYPE SET TR_DESCRIPTION = 'x' WHERE TR_TYPE = '99'`
- When CBTRNDYN executes it
- Then SYSOUT `CBTRNDYN: EXECUTE SQLCODE +0000000000`; no RC 8; COBTUPDT is still called

**Proven by**: source-only

### BR-TRT-039 CBTRNDYN's RC 8 is not the step RC once COBTUPDT runs

**Rule**: The step RC is the RETURN-CODE at COBTUPDT's STOP RUN, not the highest code set in the run. The RC 8 set by CBTRNDYN after a failed EXECUTE does not survive the dynamic CALL: with a clean COBTUPDT run the step ends RC 0 (scratch run); in d03 it ends RC 4 because COBTUPDT's own update fails.

**Source**: `app/app-transaction-type-db2/cbl/CBTRNDYN.cbl:98-101`; `app/app-transaction-type-db2/cbl/CBTRNDYN.cbl:59-63`; `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:97-99`; `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:232-232`

**Fields**: `RETURN-CODE`

**Category**: return-code

**Test**:
- Given DYNSQL with an unknown column and INPFILE `U01Purchase - updated`
- When MNTTRDYN runs
- Then SYSOUT shows `EXECUTE SQLCODE -0000000400` and COBTUPDT's -400 error; rc.json STEP1 = 4 (not 8)

**Proven by**: fixtures/trantype-dynamic/d03-bad-statement

**Finding**: A failed ad-hoc statement does not show in the step RC (RC 0 if COBTUPDT has nothing to do). Looks wrong; must be reproduced.

### BR-TRT-040 Dynamic CALL of the program named in PARMS

**Rule**: When PARM-LENGTH > 0, CBTRNDYN moves PARM-VALUE(1:PARM-LENGTH) to WS-PGM-NAME (X(8)), displays `CBTRNDYN: CALLING ` + WS-PGM-NAME and runs CALL WS-PGM-NAME with no USING and no ON EXCEPTION. The CALL happens whatever the dynamic SQL outcome. The target is known only from the JCL PARMS, not at compile time.

**Source**: `app/app-transaction-type-db2/cbl/CBTRNDYN.cbl:59-63`; `app/app-transaction-type-db2/cbl/CBTRNDYN.cbl:46-46`; `app/app-transaction-type-db2/jcl/MNTTRDYN.jcl:33-33`

**Fields**: `PARM-LENGTH`, `PARM-VALUE`, `WS-PGM-NAME`

**Category**: control-flow

**Test**:
- Given PARMS('COBTUPDT') and a failed dynamic statement
- When CBTRNDYN reaches the CALL
- Then SYSOUT `CBTRNDYN: CALLING COBTUPDT` followed by COBTUPDT's `OPEN FILE OK`

**Proven by**: fixtures/trantype-dynamic/d01-update-then-apply, fixtures/trantype-dynamic/d03-bad-statement

### BR-TRT-041 COBTUPDT's STOP RUN ends the run unit

**Rule**: COBTUPDT ends with STOP RUN, not GOBACK, so control never returns to CBTRNDYN: `CBTRNDYN: END` is never displayed and its GOBACK is not reached. The end-of-task exit then commits and prints `DSNRUN: COMMIT AT END OF TASK` as the last line.

**Source**: `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:97-99`; `app/app-transaction-type-db2/cbl/CBTRNDYN.cbl:64-65`; `tools/stubs/DSNEXIT.cbl:13-21`

**Fields**: `RETURN-CODE`

**Category**: control-flow

**Test**:
- Given the standard MNTTRDYN run
- When COBTUPDT finishes its INPFILE
- Then SYSOUT ends with `RECORD INSERTED SUCCESSFULLY` then `DSNRUN: COMMIT AT END OF TASK`; no `CBTRNDYN: END` line

**Proven by**: fixtures/trantype-dynamic/d01-update-then-apply

**Finding**: The driver's END message and any code after the CALL are dead in practice. Must be reproduced (no `CBTRNDYN: END` in SYSOUT).

### BR-TRT-042 No PARMS: no CALL and normal end

**Rule**: When PARM-LENGTH is 0 (RUN PROGRAM(CBTRNDYN) without PARMS) the CALL is skipped, `CBTRNDYN: END` is displayed and GOBACK returns to DSNRUN, whose STOP RUN triggers the end-of-task commit. A PARMS value longer than 8 characters is truncated to 8 in WS-PGM-NAME.

**Source**: `app/app-transaction-type-db2/cbl/CBTRNDYN.cbl:59-65`; `tools/stubs/DSNRUN.cbl:31-35`; `tools/stubs/DSNRUN.cbl:51-52`

**Fields**: `PARM-LENGTH`, `WS-PGM-NAME`

**Category**: edge-case

**Test**:
- Given SYSTSIN `RUN PROGRAM(CBTRNDYN) PLAN(CARDDEMO)` and a valid DYNSQL
- When the step runs
- Then SYSOUT `CBTRNDYN: 0n SQL LINES READ`, `CBTRNDYN: EXECUTE SQLCODE ...`, `CBTRNDYN: END`, `DSNRUN: COMMIT AT END OF TASK`; INPFILE is not read

**Proven by**: source-only

### BR-TRT-043 Dynamic SQL and COBTUPDT share one unit of work

**Rule**: The dynamic statement and COBTUPDT's changes are in one unit of work committed at end of task: both persist together on success, and if any statement fails under PostgreSQL both are lost (see BR-TRT-029), including a dynamic statement that reported SQLCODE 0.

**Source**: `app/app-transaction-type-db2/cbl/CBTRNDYN.cbl:5-12`; `app/app-transaction-type-db2/cbl/CBTRNDYN.cbl:95-97`; `tools/stubs/DSNEXIT.cbl:14-14`

**Fields**: `TR_TYPE`, `TR_DESCRIPTION`

**Category**: state-change

**Test**:
- Given DYNSQL updating 02 to 'Payment - SEPA' and INPFILE `A04Authorization`
- When MNTTRDYN runs
- Then both changes are committed: 02='Payment - SEPA' and 04='Authorization' padded; RC 0

**Proven by**: fixtures/trantype-dynamic/d01-update-then-apply

### BR-TRT-044 Unqualified names and unpadded literals in dynamic SQL

**Rule**: Dynamic statements name TRANSACTION_TYPE without the CARDDEMO qualifier; under PostgreSQL this resolves to the carddemo schema through the default search_path ("$user" = carddemo), standing in for the Db2 plan qualifier. Values supplied as SQL literals are stored exactly as written (no 50-byte padding), unlike COBTUPDT's host variables.

**Source**: `app/app-transaction-type-db2/cbl/CBTRNDYN.cbl:88-90`; `db/postgres/01-transaction-type.sql:4-8`

**Fields**: `TR_TYPE`, `TR_DESCRIPTION`

**Category**: data-mapping

**Test**:
- Given DYNSQL `UPDATE TRANSACTION_TYPE SET TR_DESCRIPTION = 'Payment - SEPA' WHERE TR_TYPE = '02'`
- When MNTTRDYN runs
- Then carddemo.transaction_type row 02 holds `Payment - SEPA` with no trailing spaces, while 04 inserted by COBTUPDT is padded to 50

**Proven by**: fixtures/trantype-dynamic/d01-update-then-apply

### BR-TRT-045 ocesql source normalisation (harness)

**Rule**: Harness behaviour: tools/esql_prep.py builds scratch copies for Open COBOL ESQL without editing app/: it strips columns 73-80, joins the three-line EXEC SQL INCLUDE SQLCA into one line and removes EXEC SQL DECLARE ... TABLE from DCLGEN members. It does not change program logic, so every rule above is the logic of the unmodified estate source.

**Source**: `tools/esql_prep.py:29-55`; `app/app-transaction-type-db2/dcl/DCLTRTYP.dcl:28-31`; `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:50-52`

**Fields**: `DCLTRANSACTION-TYPE`, `SQLCA`

**Category**: data-mapping

**Test**:
- Given COBTUPDT.cbl with a three-line INCLUDE SQLCA
- When make build runs esql_prep
- Then work/esql/COBTUPDT.cbl has a one-line `EXEC SQL INCLUDE SQLCA END-EXEC.` and the original file is unchanged

**Proven by**: source-only

## Machine-readable rules

```json
{
 "slice": "trantype",
 "rules": [
  {
   "id": "BR-TRT-001",
   "title": "INPFILE record layout",
   "rule": "Each INPFILE record is a fixed 53-byte card read INTO WS-INPUT-REC: column 1 is the action code (INPUT-REC-TYPE, X(1)), columns 2-3 the transaction type code (INPUT-REC-NUMBER, X(2)) and columns 4-53 the description (INPUT-REC-DESC, X(50)). The JCL header documents A=add, U=update, D=delete, *=comment.",
   "category": "data-mapping",
   "sources": [
    {
     "file": "app/app-transaction-type-db2/cbl/COBTUPDT.cbl",
     "startLine": 39,
     "endLine": 46
    },
    {
     "file": "app/app-transaction-type-db2/cbl/COBTUPDT.cbl",
     "startLine": 71,
     "endLine": 77
    },
    {
     "file": "app/app-transaction-type-db2/jcl/MNTTRDB2.jcl",
     "startLine": 8,
     "endLine": 18
    },
    {
     "file": "tools/runjcl/chains.json",
     "startLine": 73,
     "endLine": 75
    }
   ],
   "fields": [
    "INPUT-TYPE",
    "INPUT-TR-NUMBER",
    "INPUT-TR-DESC",
    "INPUT-REC-TYPE",
    "INPUT-REC-NUMBER",
    "INPUT-REC-DESC"
   ],
   "given": "INPFILE card `A04Authorization`",
   "when": "COBTUPDT reads the card",
   "then": "INPUT-REC-TYPE='A', INPUT-REC-NUMBER='04', INPUT-REC-DESC='Authorization' followed by 37 spaces",
   "fixtures": [
    "fixtures/trantype-update/t01-add"
   ],
   "finding": null
  },
  {
   "id": "BR-TRT-002",
   "title": "Short cards are space-padded to LRECL 53",
   "rule": "Harness behaviour: the fixture loader writes INPFILE as fixed 53-byte records, right-padding shorter lines with spaces and truncating longer ones, which matches a RECFM=FB LRECL=53 dataset on z/OS. A card shorter than 53 bytes therefore yields a space-filled description, and a one-character type code leaves the second digit blank.",
   "category": "data-mapping",
   "sources": [
    {
     "file": "tools/parity/record.py",
     "startLine": 46,
     "endLine": 51
    },
    {
     "file": "tools/runjcl/chains.json",
     "startLine": 73,
     "endLine": 75
    }
   ],
   "fields": [
    "INPUT-REC-DESC",
    "INPUT-REC-NUMBER"
   ],
   "given": "INPFILE line `D03` (3 bytes)",
   "when": "the case is materialised and COBTUPDT reads it",
   "then": "the record is `D03` + 50 spaces and SYSOUT shows `PROCESSING   D03` padded to 66 characters",
   "fixtures": [
    "fixtures/trantype-update/t03-delete"
   ],
   "finding": null
  },
  {
   "id": "BR-TRT-003",
   "title": "Sequential read loop until end of file",
   "rule": "COBTUPDT performs a priming read, then repeats treat-record / read-next until the AT END condition sets LASTREC='Y'. Records are processed strictly in file order, one at a time; there is no sorting, de-duplication or look-ahead.",
   "category": "control-flow",
   "sources": [
    {
     "file": "app/app-transaction-type-db2/cbl/COBTUPDT.cbl",
     "startLine": 91,
     "endLine": 96
    },
    {
     "file": "app/app-transaction-type-db2/cbl/COBTUPDT.cbl",
     "startLine": 100,
     "endLine": 103
    }
   ],
   "fields": [
    "LASTREC",
    "WS-INPUT-REC"
   ],
   "given": "INPFILE `A07Chargeback`, `U01Purchase - card present`, `*  skip`, `D02`",
   "when": "MNTTRDB2 runs",
   "then": "the four records are processed in that order: insert 07, update 01, comment, delete 02",
   "fixtures": [
    "fixtures/trantype-update/t07-mixed-batch"
   ],
   "finding": null
  },
  {
   "id": "BR-TRT-004",
   "title": "PROCESSING echo for every record",
   "rule": "Every record read (including comments and invalid actions) is echoed to SYSOUT as the literal `PROCESSING   ` (PROCESSING + 3 spaces) followed by the full 53-byte WS-INPUT-REC, trailing spaces included. Nothing is echoed for the end-of-file read.",
   "category": "sysout",
   "sources": [
    {
     "file": "app/app-transaction-type-db2/cbl/COBTUPDT.cbl",
     "startLine": 104,
     "endLine": 106
    }
   ],
   "fields": [
    "WS-INPUT-REC"
   ],
   "given": "INPFILE `*  Nightly maintenance batch`",
   "when": "the record is read",
   "then": "SYSOUT line `PROCESSING   *  Nightly maintenance batch` padded with spaces to 66 characters",
   "fixtures": [
    "fixtures/trantype-update/t04-comments"
   ],
   "finding": null
  },
  {
   "id": "BR-TRT-005",
   "title": "Open status message, no stop on open failure",
   "rule": "After OPEN INPUT the program displays `OPEN FILE OK` when WS-INF-STATUS='00', otherwise `OPEN FILE NOT OK`. In both cases processing continues into the read loop; no return code is set and the run is not stopped.",
   "category": "sysout",
   "sources": [
    {
     "file": "app/app-transaction-type-db2/cbl/COBTUPDT.cbl",
     "startLine": 82,
     "endLine": 89
    },
    {
     "file": "app/app-transaction-type-db2/cbl/COBTUPDT.cbl",
     "startLine": 31,
     "endLine": 34
    },
    {
     "file": "app/app-transaction-type-db2/cbl/COBTUPDT.cbl",
     "startLine": 67,
     "endLine": 69
    }
   ],
   "fields": [
    "WS-INF-STATUS",
    "WS-INF-STAT1",
    "WS-INF-STAT2"
   ],
   "given": "a valid INPFILE dataset",
   "when": "COBTUPDT opens INPFILE",
   "then": "SYSOUT line 2 is `OPEN FILE OK`",
   "fixtures": [
    "fixtures/trantype-update/t01-add"
   ],
   "finding": "On an open failure the program only prints `OPEN FILE NOT OK` and carries on reading an unopened file with no RC and no stop. Looks wrong; it must be reproduced (source-only path)."
  },
  {
   "id": "BR-TRT-006",
   "title": "Action dispatch on column 1 (case-sensitive)",
   "rule": "The action code is evaluated exactly: 'A' -> insert, 'U' -> update, 'D' -> delete, '*' -> comment, anything else (including lower-case letters and space) -> invalid. Each valid action first displays `ADDING RECORD`, `UPDATING RECORD`, `DELETING RECORD` or `IGNORING COMMENTED LINE`. Confirmed by an off-host scratch run of the legacy estate (not a golden case).",
   "category": "validation",
   "sources": [
    {
     "file": "app/app-transaction-type-db2/cbl/COBTUPDT.cbl",
     "startLine": 109,
     "endLine": 129
    }
   ],
   "fields": [
    "INPUT-REC-TYPE"
   ],
   "given": "INPFILE `a08lower`",
   "when": "COBTUPDT evaluates the action code",
   "then": "lower-case 'a' is not an add: SYSOUT shows `ERROR: TYPE NOT VALID` and no row 08 is inserted",
   "fixtures": [],
   "finding": null
  },
  {
   "id": "BR-TRT-007",
   "title": "Comment cards are skipped",
   "rule": "A card with '*' in column 1 displays `IGNORING COMMENTED LINE` and performs no SQL; it does not change the return code.",
   "category": "control-flow",
   "sources": [
    {
     "file": "app/app-transaction-type-db2/cbl/COBTUPDT.cbl",
     "startLine": 120,
     "endLine": 121
    }
   ],
   "fields": [
    "INPUT-REC-TYPE"
   ],
   "given": "INPFILE `*  Nightly maintenance batch`, `A06Fee`, `*  end`",
   "when": "MNTTRDB2 runs",
   "then": "both comments print `IGNORING COMMENTED LINE`, only type 06 is inserted, step RC 0",
   "fixtures": [
    "fixtures/trantype-update/t04-comments"
   ],
   "finding": null
  },
  {
   "id": "BR-TRT-008",
   "title": "Invalid action code reports an error and sets RC 4",
   "rule": "Any action code other than A/U/D/* builds `ERROR: TYPE NOT VALID` into WS-RETURN-MSG, performs 9999-ABEND which DISPLAYs the 80-byte WS-RETURN-MSG and moves 4 to RETURN-CODE, then continues with the next record. Confirmed by an off-host scratch run of the legacy estate (not a golden case).",
   "category": "validation",
   "sources": [
    {
     "file": "app/app-transaction-type-db2/cbl/COBTUPDT.cbl",
     "startLine": 122,
     "endLine": 128
    },
    {
     "file": "app/app-transaction-type-db2/cbl/COBTUPDT.cbl",
     "startLine": 230,
     "endLine": 233
    }
   ],
   "fields": [
    "INPUT-REC-TYPE",
    "WS-RETURN-MSG",
    "RETURN-CODE"
   ],
   "given": "seeded table 01/02/03 and INPFILE `X01bad`, `*  c`",
   "when": "MNTTRDB2 runs",
   "then": "SYSOUT shows `ERROR: TYPE NOT VALID` padded to 80 characters, then the comment is ignored; step RC 4; table unchanged",
   "fixtures": [],
   "finding": null
  },
  {
   "id": "BR-TRT-009",
   "title": "Blank card is an invalid action",
   "rule": "A blank (all-space) card is not treated as a comment or skipped: it is echoed as `PROCESSING` plus spaces and rejected with `ERROR: TYPE NOT VALID` (RC 4 unless a later SQL statement resets it, see BR-TRT-024). Confirmed by an off-host scratch run of the legacy estate (not a golden case).",
   "category": "edge-case",
   "sources": [
    {
     "file": "app/app-transaction-type-db2/cbl/COBTUPDT.cbl",
     "startLine": 104,
     "endLine": 106
    },
    {
     "file": "app/app-transaction-type-db2/cbl/COBTUPDT.cbl",
     "startLine": 122,
     "endLine": 128
    }
   ],
   "fields": [
    "INPUT-REC-TYPE"
   ],
   "given": "INPFILE containing one empty line",
   "when": "COBTUPDT reads it",
   "then": "SYSOUT shows `PROCESSING` + 56 spaces, then `ERROR: TYPE NOT VALID`",
   "fixtures": [],
   "finding": null
  },
  {
   "id": "BR-TRT-010",
   "title": "Add inserts a TRANSACTION_TYPE row",
   "rule": "Action 'A' executes INSERT INTO CARDDEMO.TRANSACTION_TYPE (TR_TYPE, TR_DESCRIPTION) VALUES (:INPUT-REC-NUMBER, :INPUT-REC-DESC). On SQLCODE 0 it displays `RECORD INSERTED SUCCESSFULLY`.",
   "category": "state-change",
   "sources": [
    {
     "file": "app/app-transaction-type-db2/cbl/COBTUPDT.cbl",
     "startLine": 111,
     "endLine": 113
    },
    {
     "file": "app/app-transaction-type-db2/cbl/COBTUPDT.cbl",
     "startLine": 137,
     "endLine": 153
    },
    {
     "file": "db/postgres/01-transaction-type.sql",
     "startLine": 6,
     "endLine": 9
    }
   ],
   "fields": [
    "INPUT-REC-NUMBER",
    "INPUT-REC-DESC",
    "TR_TYPE",
    "TR_DESCRIPTION"
   ],
   "given": "seeded table 01/02/03 and INPFILE `A04Authorization`, `A05Refund`",
   "when": "MNTTRDB2 runs",
   "then": "SYSOUT shows `ADDING RECORD` / `RECORD INSERTED SUCCESSFULLY` twice; rows 04 and 05 exist; step RC 0",
   "fixtures": [
    "fixtures/trantype-update/t01-add"
   ],
   "finding": null
  },
  {
   "id": "BR-TRT-011",
   "title": "Descriptions are stored space-padded to 50 bytes",
   "rule": "COBTUPDT binds the fixed-length PIC X(50) INPUT-REC-DESC, not the DCLGEN VARCHAR structure (DCL-TR-DESCRIPTION-LEN/-TEXT is included but never used), and Open COBOL ESQL sends the full 50-byte host variable. Every row written by COBTUPDT therefore holds a 50-character TR_DESCRIPTION with trailing spaces, whereas seed rows and rows set by dynamic SQL literals are unpadded.",
   "category": "data-mapping",
   "sources": [
    {
     "file": "app/app-transaction-type-db2/cbl/COBTUPDT.cbl",
     "startLine": 76,
     "endLine": 77
    },
    {
     "file": "app/app-transaction-type-db2/cbl/COBTUPDT.cbl",
     "startLine": 145,
     "endLine": 146
    },
    {
     "file": "app/app-transaction-type-db2/cbl/COBTUPDT.cbl",
     "startLine": 173,
     "endLine": 173
    },
    {
     "file": "app/app-transaction-type-db2/dcl/DCLTRTYP.dcl",
     "startLine": 35,
     "endLine": 46
    },
    {
     "file": "db/postgres/01-transaction-type.sql",
     "startLine": 8,
     "endLine": 8
    }
   ],
   "fields": [
    "INPUT-REC-DESC",
    "DCL-TR-DESCRIPTION-LEN",
    "DCL-TR-DESCRIPTION-TEXT",
    "TR_DESCRIPTION"
   ],
   "given": "INPFILE `U02Payment - bank transfer`",
   "when": "the update is applied",
   "then": "TR_DESCRIPTION for 02 is `Payment - bank transfer` followed by 27 spaces (50 characters), while 01 stays `Purchase` unpadded",
   "fixtures": [
    "fixtures/trantype-update/t02-update",
    "fixtures/trantype-update/t01-add"
   ],
   "finding": "The VARCHAR column receives trailing blanks and the DCLGEN length field is ignored, so the table mixes padded and unpadded descriptions. Must be reproduced (golden DB snapshots contain the padding)."
  },
  {
   "id": "BR-TRT-012",
   "title": "No validation of type code or description",
   "rule": "COBTUPDT does not check that the type code is numeric (the JCL comment says it should be) or non-blank, nor that the description is non-blank. A blank type code inserts TR_TYPE '  '; a non-numeric code such as '9X' is inserted as is; a card with no description inserts 50 spaces (NOT NULL is still satisfied). Confirmed by an off-host scratch run of the legacy estate (not a golden case).",
   "category": "validation",
   "sources": [
    {
     "file": "app/app-transaction-type-db2/cbl/COBTUPDT.cbl",
     "startLine": 109,
     "endLine": 113
    },
    {
     "file": "app/app-transaction-type-db2/cbl/COBTUPDT.cbl",
     "startLine": 137,
     "endLine": 148
    },
    {
     "file": "app/app-transaction-type-db2/jcl/MNTTRDB2.jcl",
     "startLine": 16,
     "endLine": 16
    }
   ],
   "fields": [
    "INPUT-REC-NUMBER",
    "INPUT-REC-DESC",
    "TR_TYPE"
   ],
   "given": "INPFILE `A  NoNumber` and `A9Xshort`",
   "when": "MNTTRDB2 runs",
   "then": "rows TR_TYPE='  ' (description `NoNumber` padded) and TR_TYPE='9X' (description `short` padded) are inserted, `RECORD INSERTED SUCCESSFULLY` twice, step RC 0",
   "fixtures": [],
   "finding": "Type codes documented as numeric are not validated; blank and alphanumeric keys are accepted. Must be reproduced."
  },
  {
   "id": "BR-TRT-013",
   "title": "Duplicate add fails with negative SQLCODE (-403 under ocesql)",
   "rule": "A primary-key violation on INSERT gives SQLCODE < 0. Through Open COBOL ESQL/PostgreSQL the value is -403 (Db2 would return -803). COBTUPDT reports it via 9999-ABEND and sets RC 4; the row is not inserted.",
   "category": "return-code",
   "sources": [
    {
     "file": "app/app-transaction-type-db2/cbl/COBTUPDT.cbl",
     "startLine": 151,
     "endLine": 163
    },
    {
     "file": "app/app-transaction-type-db2/cbl/COBTUPDT.cbl",
     "startLine": 230,
     "endLine": 233
    },
    {
     "file": "db/postgres/01-transaction-type.sql",
     "startLine": 9,
     "endLine": 9
    }
   ],
   "fields": [
    "TR_TYPE",
    "SQLCODE",
    "WS-VAR-SQLCODE",
    "RETURN-CODE"
   ],
   "given": "seeded table 01/02/03 and INPFILE `A01Purchase`",
   "when": "MNTTRDB2 runs",
   "then": "SYSOUT `Error accessing: TRANSACTION_TYPE table. SQLCODE: -403`; step RC 4; table unchanged",
   "fixtures": [
    "fixtures/trantype-update/t06-duplicate-add"
   ],
   "finding": "The SQLCODE printed is the ocesql value -403, not Db2's -803. The golden SYSOUT records -403 and must be reproduced."
  },
  {
   "id": "BR-TRT-014",
   "title": "SQL error message format",
   "rule": "On SQLCODE < 0 the program STRINGs `Error accessing:` + ` TRANSACTION_TYPE table. SQLCODE:` + WS-VAR-SQLCODE (edited PIC ----9, 5 characters, so -403 becomes ` -403`) into the 80-byte WS-RETURN-MSG, and 9999-ABEND DISPLAYs the whole 80-byte field (trailing spaces included).",
   "category": "sysout",
   "sources": [
    {
     "file": "app/app-transaction-type-db2/cbl/COBTUPDT.cbl",
     "startLine": 61,
     "endLine": 65
    },
    {
     "file": "app/app-transaction-type-db2/cbl/COBTUPDT.cbl",
     "startLine": 155,
     "endLine": 161
    },
    {
     "file": "app/app-transaction-type-db2/cbl/COBTUPDT.cbl",
     "startLine": 186,
     "endLine": 192
    },
    {
     "file": "app/app-transaction-type-db2/cbl/COBTUPDT.cbl",
     "startLine": 217,
     "endLine": 223
    },
    {
     "file": "app/app-transaction-type-db2/cbl/COBTUPDT.cbl",
     "startLine": 230,
     "endLine": 231
    }
   ],
   "fields": [
    "WS-RETURN-MSG",
    "WS-VAR-SQLCODE",
    "SQLCODE"
   ],
   "given": "SQLCODE -403 on an insert",
   "when": "the error message is built and displayed",
   "then": "SYSOUT line is `Error accessing: TRANSACTION_TYPE table. SQLCODE: -403` padded with spaces to 80 characters",
   "fixtures": [
    "fixtures/trantype-update/t06-duplicate-add"
   ],
   "finding": null
  },
  {
   "id": "BR-TRT-015",
   "title": "WS-RETURN-MSG is never cleared between messages",
   "rule": "STRING writes from position 1 without clearing WS-RETURN-MSG, so a shorter message overwrites only the start of the previous one. After a 54-character SQL error, an invalid action shows `ERROR: TYPE NOT VALID` followed by the leftover tail of the earlier text. Confirmed by an off-host scratch run of the legacy estate (not a golden case).",
   "category": "edge-case",
   "sources": [
    {
     "file": "app/app-transaction-type-db2/cbl/COBTUPDT.cbl",
     "startLine": 60,
     "endLine": 62
    },
    {
     "file": "app/app-transaction-type-db2/cbl/COBTUPDT.cbl",
     "startLine": 123,
     "endLine": 128
    },
    {
     "file": "app/app-transaction-type-db2/cbl/COBTUPDT.cbl",
     "startLine": 155,
     "endLine": 161
    },
    {
     "file": "app/app-transaction-type-db2/cbl/COBTUPDT.cbl",
     "startLine": 230,
     "endLine": 231
    }
   ],
   "fields": [
    "WS-RETURN-MSG"
   ],
   "given": "seeded table 01/02/03 and INPFILE `A01Dup`, `X01bad`",
   "when": "MNTTRDB2 runs",
   "then": "SYSOUT shows `Error accessing: TRANSACTION_TYPE table. SQLCODE: -403` then `ERROR: TYPE NOT VALIDSACTION_TYPE table. SQLCODE: -403`",
   "fixtures": [],
   "finding": "Error text is corrupted by residue from the previous message. Looks wrong; operators see it in SYSOUT, so it must be reproduced byte for byte."
  },
  {
   "id": "BR-TRT-016",
   "title": "Positive SQLCODE on insert is silently ignored",
   "rule": "The insert EVALUATE only handles SQLCODE = 0 and SQLCODE < 0. A positive SQLCODE (a warning) prints no success or error line and leaves RETURN-CODE unchanged.",
   "category": "edge-case",
   "sources": [
    {
     "file": "app/app-transaction-type-db2/cbl/COBTUPDT.cbl",
     "startLine": 151,
     "endLine": 163
    }
   ],
   "fields": [
    "SQLCODE"
   ],
   "given": "an INSERT that returns a positive warning SQLCODE",
   "when": "10031-INSERT-DB evaluates it",
   "then": "only `ADDING RECORD` is shown for the card and RC is unchanged",
   "fixtures": [],
   "finding": null
  },
  {
   "id": "BR-TRT-017",
   "title": "Update replaces the description of an existing type",
   "rule": "Action 'U' executes UPDATE CARDDEMO.TRANSACTION_TYPE SET TR_DESCRIPTION = :INPUT-REC-DESC WHERE TR_TYPE = :INPUT-REC-NUMBER and on SQLCODE 0 displays `RECORD UPDATED SUCCESSFULLY`. TR_TYPE itself is never changed.",
   "category": "state-change",
   "sources": [
    {
     "file": "app/app-transaction-type-db2/cbl/COBTUPDT.cbl",
     "startLine": 114,
     "endLine": 116
    },
    {
     "file": "app/app-transaction-type-db2/cbl/COBTUPDT.cbl",
     "startLine": 171,
     "endLine": 179
    }
   ],
   "fields": [
    "INPUT-REC-NUMBER",
    "INPUT-REC-DESC",
    "TR_TYPE",
    "TR_DESCRIPTION"
   ],
   "given": "seeded 02='Payment' and INPFILE `U02Payment - bank transfer`",
   "when": "MNTTRDB2 runs",
   "then": "`UPDATING RECORD` / `RECORD UPDATED SUCCESSFULLY`; 02 now holds `Payment - bank transfer` padded to 50; RC 0",
   "fixtures": [
    "fixtures/trantype-update/t02-update"
   ],
   "finding": null
  },
  {
   "id": "BR-TRT-018",
   "title": "Update of a missing type reports success under ocesql",
   "rule": "The update has a +100 branch (`No records found.` + RC 4), but through Open COBOL ESQL/PostgreSQL a searched UPDATE that matches no row returns SQLCODE 0, so `RECORD UPDATED SUCCESSFULLY` prints, nothing changes and RC stays 0. On Db2 the same card would give +100, `No records found.` and RC 4. Confirmed by an off-host scratch run of the legacy estate (not a golden case).",
   "category": "edge-case",
   "sources": [
    {
     "file": "app/app-transaction-type-db2/cbl/COBTUPDT.cbl",
     "startLine": 176,
     "endLine": 184
    }
   ],
   "fields": [
    "SQLCODE",
    "WS-RETURN-MSG",
    "RETURN-CODE"
   ],
   "given": "seeded table 01/02/03 and INPFILE `U09Nothing`",
   "when": "MNTTRDB2 runs",
   "then": "SYSOUT `UPDATING RECORD` / `RECORD UPDATED SUCCESSFULLY`; table unchanged; step RC 0",
   "fixtures": [],
   "finding": "A no-op update is reported as successful and the +100 branch can never fire under the harness. Must be reproduced as the PostgreSQL-recorded behaviour."
  },
  {
   "id": "BR-TRT-019",
   "title": "Update SQL error reports and sets RC 4",
   "rule": "A negative SQLCODE on UPDATE prints the standard `Error accessing: TRANSACTION_TYPE table. SQLCODE:` message and sets RETURN-CODE 4; the row is not changed.",
   "category": "return-code",
   "sources": [
    {
     "file": "app/app-transaction-type-db2/cbl/COBTUPDT.cbl",
     "startLine": 185,
     "endLine": 193
    },
    {
     "file": "app/app-transaction-type-db2/cbl/COBTUPDT.cbl",
     "startLine": 230,
     "endLine": 233
    }
   ],
   "fields": [
    "SQLCODE",
    "WS-VAR-SQLCODE",
    "RETURN-CODE"
   ],
   "given": "a transaction already aborted by a failed dynamic statement and INPFILE `U01Purchase - updated`",
   "when": "COBTUPDT runs the update",
   "then": "SYSOUT `Error accessing: TRANSACTION_TYPE table. SQLCODE: -400`; 01 stays `Purchase`; step RC 4",
   "fixtures": [
    "fixtures/trantype-dynamic/d03-bad-statement"
   ],
   "finding": null
  },
  {
   "id": "BR-TRT-020",
   "title": "Delete removes a type by key",
   "rule": "Action 'D' executes DELETE FROM CARDDEMO.TRANSACTION_TYPE WHERE TR_TYPE = :INPUT-REC-NUMBER (the description columns are ignored) and on SQLCODE 0 displays `RECORD DELETED SUCCESSFULLY`.",
   "category": "state-change",
   "sources": [
    {
     "file": "app/app-transaction-type-db2/cbl/COBTUPDT.cbl",
     "startLine": 117,
     "endLine": 119
    },
    {
     "file": "app/app-transaction-type-db2/cbl/COBTUPDT.cbl",
     "startLine": 201,
     "endLine": 209
    }
   ],
   "fields": [
    "INPUT-REC-NUMBER",
    "TR_TYPE"
   ],
   "given": "seeded table 01/02/03 and INPFILE `D03`",
   "when": "MNTTRDB2 runs",
   "then": "`DELETING RECORD` / `RECORD DELETED SUCCESSFULLY`; rows 01 and 02 remain; RC 0",
   "fixtures": [
    "fixtures/trantype-update/t03-delete"
   ],
   "finding": null
  },
  {
   "id": "BR-TRT-021",
   "title": "Delete of a missing type reports success",
   "rule": "Deleting a type that does not exist prints `RECORD DELETED SUCCESSFULLY` with RC 0 and leaves the table unchanged. Under Open COBOL ESQL/PostgreSQL a 0-row searched DELETE returns SQLCODE 0, so the coded +100 branch (`No records found.` + RC 4) is never reached; on Db2 the +100 branch would fire.",
   "category": "edge-case",
   "sources": [
    {
     "file": "app/app-transaction-type-db2/cbl/COBTUPDT.cbl",
     "startLine": 205,
     "endLine": 214
    }
   ],
   "fields": [
    "SQLCODE",
    "TR_TYPE"
   ],
   "given": "seeded table 01/02/03 and INPFILE `D09`",
   "when": "MNTTRDB2 runs",
   "then": "SYSOUT `DELETING RECORD` / `RECORD DELETED SUCCESSFULLY`; table unchanged; step RC 0",
   "fixtures": [
    "fixtures/trantype-update/t05-delete-missing"
   ],
   "finding": "Success is reported for a delete that removed nothing. The fixture README attributes this to COBTUPDT testing only SQLCODE < 0, but the source does test +100; the real cause is that ocesql returns 0. Must be reproduced."
  },
  {
   "id": "BR-TRT-022",
   "title": "Delete SQL error reports and sets RC 4",
   "rule": "A negative SQLCODE on DELETE (for example a foreign-key restriction from TRANSACTION_TYPE_CATEGOR, or -400 in an aborted transaction) prints the standard SQL error message and sets RETURN-CODE 4.",
   "category": "return-code",
   "sources": [
    {
     "file": "app/app-transaction-type-db2/cbl/COBTUPDT.cbl",
     "startLine": 216,
     "endLine": 224
    },
    {
     "file": "app/app-transaction-type-db2/cbl/COBTUPDT.cbl",
     "startLine": 230,
     "endLine": 233
    },
    {
     "file": "db/postgres/01-transaction-type.sql",
     "startLine": 16,
     "endLine": 17
    }
   ],
   "fields": [
    "SQLCODE",
    "WS-VAR-SQLCODE",
    "RETURN-CODE"
   ],
   "given": "a type 01 that is referenced by a transaction_type_categor row and INPFILE `D01`",
   "when": "COBTUPDT runs the delete",
   "then": "SYSOUT `Error accessing: TRANSACTION_TYPE table. SQLCODE:` with the negative code; row 01 kept (and see BR-TRT-029 for the whole-run rollback); RC 4",
   "fixtures": [],
   "finding": null
  },
  {
   "id": "BR-TRT-023",
   "title": "Errors never stop the batch",
   "rule": "Despite its name, 9999-ABEND only displays the message and moves 4 to RETURN-CODE; control returns to the read loop and the remaining cards are processed. Confirmed by an off-host scratch run of the legacy estate (not a golden case).",
   "category": "control-flow",
   "sources": [
    {
     "file": "app/app-transaction-type-db2/cbl/COBTUPDT.cbl",
     "startLine": 230,
     "endLine": 233
    },
    {
     "file": "app/app-transaction-type-db2/cbl/COBTUPDT.cbl",
     "startLine": 93,
     "endLine": 96
    }
   ],
   "fields": [
    "RETURN-CODE",
    "LASTREC"
   ],
   "given": "INPFILE `A04Authorization`, `A01Dup`, `X01bad`, `A05Refund`",
   "when": "MNTTRDB2 runs",
   "then": "all four cards are echoed and processed after the -403 on `A01Dup`; there is no abend",
   "fixtures": [],
   "finding": "The paragraph named ABEND does not abend. Must be reproduced (continue after error)."
  },
  {
   "id": "BR-TRT-024",
   "title": "RETURN-CODE 4 is reset by any later SQL statement",
   "rule": "RC 4 is not sticky. Each EXEC SQL is precompiled by ocesql into CALLs to the ocesql runtime, and each CALL overwrites the RETURN-CODE special register (normally with 0). The step RC therefore reflects only an error raised after the last executed SQL statement: an invalid card followed by a successful SQL card ends with RC 0; an invalid card followed only by comments ends with RC 4. Confirmed by an off-host scratch run of the legacy estate (not a golden case).",
   "category": "return-code",
   "sources": [
    {
     "file": "app/app-transaction-type-db2/cbl/COBTUPDT.cbl",
     "startLine": 232,
     "endLine": 232
    },
    {
     "file": "app/app-transaction-type-db2/cbl/COBTUPDT.cbl",
     "startLine": 137,
     "endLine": 148
    },
    {
     "file": "app/app-transaction-type-db2/cbl/COBTUPDT.cbl",
     "startLine": 201,
     "endLine": 204
    }
   ],
   "fields": [
    "RETURN-CODE"
   ],
   "given": "seeded table 01/02/03 and INPFILE `X01bad`, `D99`",
   "when": "MNTTRDB2 runs",
   "then": "SYSOUT shows `ERROR: TYPE NOT VALID` then `RECORD DELETED SUCCESSFULLY`; step RC is 0, not 4",
   "fixtures": [],
   "finding": "Earlier errors are lost from the step RC whenever a later SQL statement runs. Looks wrong; the RC must be reproduced as observed under the harness."
  },
  {
   "id": "BR-TRT-025",
   "title": "End of input closes the file and STOP RUN",
   "rule": "At end of file COBTUPDT closes INPFILE and executes STOP RUN (the EXIT before it is a no-op). An empty INPFILE gives only `OPEN FILE OK` and RC 0.",
   "category": "control-flow",
   "sources": [
    {
     "file": "app/app-transaction-type-db2/cbl/COBTUPDT.cbl",
     "startLine": 97,
     "endLine": 99
    },
    {
     "file": "app/app-transaction-type-db2/cbl/COBTUPDT.cbl",
     "startLine": 234,
     "endLine": 236
    }
   ],
   "fields": [
    "LASTREC"
   ],
   "given": "an empty INPFILE",
   "when": "COBTUPDT runs",
   "then": "SYSOUT from COBTUPDT is just `OPEN FILE OK`; no SQL; RC 0",
   "fixtures": [
    "fixtures/trantype-dynamic/d02-delete-statement"
   ],
   "finding": null
  },
  {
   "id": "BR-TRT-026",
   "title": "MNTTRDB2 step and RC",
   "rule": "MNTTRDB2 has one step, STEP1 (EXEC PGM=IKJEFT01) with SYSTSIN `DSN SYSTEM(DAZ1)` / `RUN PROGRAM(COBTUPDT) PLAN(CARDDEMO)` and INPFILE DD. Harness behaviour: the runner maps the RUN subcommand to `cobcrun DSNRUN COBTUPDT`; the STEP1 RC (and job maxrc) is the RETURN-CODE in effect at COBTUPDT's STOP RUN.",
   "category": "return-code",
   "sources": [
    {
     "file": "app/app-transaction-type-db2/jcl/MNTTRDB2.jcl",
     "startLine": 21,
     "endLine": 30
    },
    {
     "file": "tools/runjcl/runjcl.py",
     "startLine": 704,
     "endLine": 715
    }
   ],
   "fields": [
    "RETURN-CODE"
   ],
   "given": "INPFILE `A01Purchase` against the seeded table",
   "when": "MNTTRDB2 runs",
   "then": "rc.json is `{\"MNTTRDB2\": {\"maxrc\": 4, \"steps\": {\"STEP1\": 4}}}`",
   "fixtures": [
    "fixtures/trantype-update/t06-duplicate-add",
    "fixtures/trantype-update/t01-add"
   ],
   "finding": null
  },
  {
   "id": "BR-TRT-027",
   "title": "DSN attach banner and connect failure (harness)",
   "rule": "Harness behaviour emulating IKJEFT01/DSN: DSNRUN reads the program name and optional PARMS from its command line, connects to PostgreSQL using OCDB_NAME/OCDB_USER/OCDB_PASS and displays `DSNRUN: DSN ATTACHED, RUNNING <program>` before calling it. If the connect fails it displays `DSNRUN: DATABASE CONNECT FAILED <sqlcode>` and stops with RC 12 without running the program.",
   "category": "sysout",
   "sources": [
    {
     "file": "tools/stubs/DSNRUN.cbl",
     "startLine": 31,
     "endLine": 48
    }
   ],
   "fields": [
    "WS-PGM-NAME",
    "WS-DB-NAME",
    "WS-DB-USER",
    "WS-DB-PASS",
    "RETURN-CODE"
   ],
   "given": "a reachable database",
   "when": "MNTTRDB2 STEP1 starts",
   "then": "SYSOUT line 1 is `DSNRUN: DSN ATTACHED, RUNNING COBTUPDT`",
   "fixtures": [
    "fixtures/trantype-update/t01-add"
   ],
   "finding": null
  },
  {
   "id": "BR-TRT-028",
   "title": "Commit at end of task (harness)",
   "rule": "Harness behaviour: the application programs never COMMIT. DSNRUN installs DSNEXIT with CBL_EXIT_PROC, so when the program ends (STOP RUN) DSNEXIT issues COMMIT, displays `DSNRUN: COMMIT AT END OF TASK` (or `DSNRUN: COMMIT FAILED <sqlcode>`) and disconnects. This mirrors the implicit commit of the TSO DSN command at normal end of task. All changes of the run are one unit of work.",
   "category": "state-change",
   "sources": [
    {
     "file": "tools/stubs/DSNRUN.cbl",
     "startLine": 49,
     "endLine": 52
    },
    {
     "file": "tools/stubs/DSNEXIT.cbl",
     "startLine": 13,
     "endLine": 21
    },
    {
     "file": "app/app-transaction-type-db2/cbl/COBTUPDT.cbl",
     "startLine": 97,
     "endLine": 99
    }
   ],
   "fields": [
    "SQLCODE"
   ],
   "given": "INPFILE `A04Authorization`, `A05Refund`",
   "when": "COBTUPDT reaches STOP RUN",
   "then": "the last SYSOUT line is `DSNRUN: COMMIT AT END OF TASK` and rows 04/05 are persisted",
   "fixtures": [
    "fixtures/trantype-update/t01-add"
   ],
   "finding": null
  },
  {
   "id": "BR-TRT-029",
   "title": "Failed statement aborts the whole PostgreSQL unit of work",
   "rule": "Under PostgreSQL any failed SQL statement puts the transaction into the aborted state: every later statement in the same run gets SQLCODE -400, and the end-of-task COMMIT silently becomes a rollback, so earlier successful inserts/updates/deletes of the run are lost too, although SYSOUT still says `RECORD INSERTED SUCCESSFULLY` and `DSNRUN: COMMIT AT END OF TASK`. Db2 would roll back only the failing statement and commit the rest. The rollback of earlier successes was confirmed by an off-host scratch run.",
   "category": "state-change",
   "sources": [
    {
     "file": "app/app-transaction-type-db2/cbl/COBTUPDT.cbl",
     "startLine": 149,
     "endLine": 163
    },
    {
     "file": "tools/stubs/DSNEXIT.cbl",
     "startLine": 14,
     "endLine": 19
    },
    {
     "file": "app/app-transaction-type-db2/cbl/CBTRNDYN.cbl",
     "startLine": 95,
     "endLine": 101
    }
   ],
   "fields": [
    "SQLCODE",
    "TR_TYPE",
    "TR_DESCRIPTION"
   ],
   "given": "seeded table 01/02/03 and INPFILE `A04Authorization`, `A01Dup`, `X01bad`, `A05Refund`",
   "when": "MNTTRDB2 runs",
   "then": "04 prints `RECORD INSERTED SUCCESSFULLY`, 01 fails with -403, 05 fails with -400, SYSOUT ends `DSNRUN: COMMIT AT END OF TASK`, but the table still holds only 01/02/03; step RC 4",
   "fixtures": [
    "fixtures/trantype-dynamic/d03-bad-statement"
   ],
   "finding": "A single error discards the whole run while SYSOUT reports success and commit. This is a PostgreSQL vs Db2 semantic gap recorded in the golden files; it must be reproduced (or changed only by an explicit migration decision)."
  },
  {
   "id": "BR-TRT-030",
   "title": "MNTTRDYN step, DDs and PARMS",
   "rule": "MNTTRDYN STEP1 runs IKJEFT01 with `DSN SYSTEM(DAZ1)` / `RUN PROGRAM(CBTRNDYN) PLAN(CARDDEMO) PARMS('COBTUPDT')` / `END`, and allocates DYNSQL (statement cards) and INPFILE (COBTUPDT update cards). Harness behaviour: the runner passes the PARMS text as a second argument and DSNRUN builds the z/OS halfword-length parameter area (PARM-LENGTH 8, PARM-VALUE 'COBTUPDT') passed USING to CBTRNDYN.",
   "category": "control-flow",
   "sources": [
    {
     "file": "app/app-transaction-type-db2/jcl/MNTTRDYN.jcl",
     "startLine": 23,
     "endLine": 34
    },
    {
     "file": "tools/stubs/DSNRUN.cbl",
     "startLine": 26,
     "endLine": 35
    },
    {
     "file": "tools/stubs/DSNRUN.cbl",
     "startLine": 51,
     "endLine": 51
    },
    {
     "file": "app/app-transaction-type-db2/cbl/CBTRNDYN.cbl",
     "startLine": 52,
     "endLine": 56
    },
    {
     "file": "tools/runjcl/runjcl.py",
     "startLine": 707,
     "endLine": 711
    }
   ],
   "fields": [
    "PARM-LENGTH",
    "PARM-VALUE",
    "WS-PARM-LENGTH",
    "WS-PARM-VALUE"
   ],
   "given": "the MNTTRDYN JCL",
   "when": "STEP1 runs",
   "then": "SYSOUT line 1 is `DSNRUN: DSN ATTACHED, RUNNING CBTRNDYN` and CBTRNDYN receives PARM-LENGTH=8",
   "fixtures": [
    "fixtures/trantype-dynamic/d01-update-then-apply"
   ],
   "finding": null
  },
  {
   "id": "BR-TRT-031",
   "title": "DYNSQL card reading and comment cards",
   "rule": "CBTRNDYN reads every 80-byte DYNSQL card to EOF. A card whose columns 1-2 are `--` is skipped; every other card contributes columns 1-72 plus one space to WS-SQL-STMT-ARR (columns 73-80, the sequence-number area, are dropped). All cards form one statement; WS-SQL-STMT-LEN = pointer - 1, i.e. 73 bytes per card including trailing spaces.",
   "category": "data-mapping",
   "sources": [
    {
     "file": "app/app-transaction-type-db2/cbl/CBTRNDYN.cbl",
     "startLine": 34,
     "endLine": 40
    },
    {
     "file": "app/app-transaction-type-db2/cbl/CBTRNDYN.cbl",
     "startLine": 48,
     "endLine": 51
    },
    {
     "file": "app/app-transaction-type-db2/cbl/CBTRNDYN.cbl",
     "startLine": 67,
     "endLine": 83
    },
    {
     "file": "tools/runjcl/chains.json",
     "startLine": 96,
     "endLine": 96
    }
   ],
   "fields": [
    "DYNSQL-REC",
    "WS-SQL-STMT-LEN",
    "WS-SQL-STMT-ARR",
    "WS-SQL-POS"
   ],
   "given": "DYNSQL `-- ad-hoc fix requested by operations`, `UPDATE TRANSACTION_TYPE`, `   SET TR_DESCRIPTION = 'Payment - SEPA'`, ` WHERE TR_TYPE = '02'`",
   "when": "CBTRNDYN reads DYNSQL",
   "then": "the comment is skipped and the three remaining cards are concatenated into one 219-byte statement that updates type 02",
   "fixtures": [
    "fixtures/trantype-dynamic/d01-update-then-apply"
   ],
   "finding": null
  },
  {
   "id": "BR-TRT-032",
   "title": "SQL lines read message",
   "rule": "After reading DYNSQL the program displays `CBTRNDYN: ` + WS-LINE-COUNT (PIC 9(02), two digits with leading zero) + ` SQL LINES READ`. The count includes every non-comment card, blank cards too.",
   "category": "sysout",
   "sources": [
    {
     "file": "app/app-transaction-type-db2/cbl/CBTRNDYN.cbl",
     "startLine": 45,
     "endLine": 45
    },
    {
     "file": "app/app-transaction-type-db2/cbl/CBTRNDYN.cbl",
     "startLine": 78,
     "endLine": 78
    },
    {
     "file": "app/app-transaction-type-db2/cbl/CBTRNDYN.cbl",
     "startLine": 84,
     "endLine": 84
    }
   ],
   "fields": [
    "WS-LINE-COUNT"
   ],
   "given": "DYNSQL with one comment card and three statement cards",
   "when": "CBTRNDYN finishes reading",
   "then": "SYSOUT `CBTRNDYN: 03 SQL LINES READ`",
   "fixtures": [
    "fixtures/trantype-dynamic/d01-update-then-apply",
    "fixtures/trantype-dynamic/d02-delete-statement"
   ],
   "finding": null
  },
  {
   "id": "BR-TRT-033",
   "title": "Blank cards and columns 73-80",
   "rule": "A blank DYNSQL card is not a comment: it adds 73 spaces to the statement and is counted in WS-LINE-COUNT. Text in columns 73-80 never reaches the SQL text. Confirmed by an off-host scratch run of the legacy estate (not a golden case).",
   "category": "edge-case",
   "sources": [
    {
     "file": "app/app-transaction-type-db2/cbl/CBTRNDYN.cbl",
     "startLine": 73,
     "endLine": 78
    }
   ],
   "fields": [
    "DYNSQL-REC",
    "WS-LINE-COUNT"
   ],
   "given": "DYNSQL `UPDATE TRANSACTION_TYPE` padded to col 72 + `SEQ00010` in cols 73-80, a blank card, `   SET TR_DESCRIPTION = 'Seq' WHERE TR_TYPE = '03'`",
   "when": "MNTTRDYN runs with an empty INPFILE",
   "then": "SYSOUT `CBTRNDYN: 03 SQL LINES READ` and `CBTRNDYN: EXECUTE SQLCODE +0000000000`; 03 becomes `Seq`; RC 0",
   "fixtures": [],
   "finding": null
  },
  {
   "id": "BR-TRT-034",
   "title": "Only column-1 `--` is a comment; inline `--` swallows the rest",
   "rule": "Comment detection looks only at columns 1-2. Because all cards are joined into one line with no newline, a `--` that starts later on a card (an inline or indented comment) comments out everything after it, including the following cards.",
   "category": "edge-case",
   "sources": [
    {
     "file": "app/app-transaction-type-db2/cbl/CBTRNDYN.cbl",
     "startLine": 73,
     "endLine": 77
    }
   ],
   "fields": [
    "DYNSQL-REC",
    "WS-SQL-STMT-ARR"
   ],
   "given": "DYNSQL `DELETE FROM TRANSACTION_TYPE  -- remove fee` then `WHERE TR_TYPE = '06'`",
   "when": "CBTRNDYN builds and executes the statement",
   "then": "the statement text becomes `DELETE FROM TRANSACTION_TYPE  -- remove fee ... WHERE TR_TYPE = '06'` all on one line, so the WHERE clause is commented out and the DELETE has no WHERE clause",
   "fixtures": [],
   "finding": "Indented or trailing `--` comments silently change the statement (a WHERE clause can be lost). Looks dangerous; must be reproduced (source-only)."
  },
  {
   "id": "BR-TRT-035",
   "title": "Statement buffer limit 800 bytes; line count wraps at 100",
   "rule": "WS-SQL-STMT-ARR is X(800) and each card takes 73 bytes, so only 10 full cards fit; the 11th is cut after 70 bytes and later cards are dropped (STRING ... WITH POINTER with no ON OVERFLOW). WS-SQL-STMT-LEN stops at 800. WS-LINE-COUNT is PIC 9(02) with no ON SIZE ERROR, so a 100th card wraps the count to 00.",
   "category": "edge-case",
   "sources": [
    {
     "file": "app/app-transaction-type-db2/cbl/CBTRNDYN.cbl",
     "startLine": 45,
     "endLine": 45
    },
    {
     "file": "app/app-transaction-type-db2/cbl/CBTRNDYN.cbl",
     "startLine": 48,
     "endLine": 51
    },
    {
     "file": "app/app-transaction-type-db2/cbl/CBTRNDYN.cbl",
     "startLine": 74,
     "endLine": 78
    },
    {
     "file": "app/app-transaction-type-db2/cbl/CBTRNDYN.cbl",
     "startLine": 83,
     "endLine": 83
    }
   ],
   "fields": [
    "WS-SQL-STMT-ARR",
    "WS-SQL-STMT-LEN",
    "WS-SQL-POS",
    "WS-LINE-COUNT"
   ],
   "given": "DYNSQL with 12 non-comment cards",
   "when": "CBTRNDYN reads them",
   "then": "WS-SQL-STMT-LEN = 800, text from card 11 column 71 onward is lost without a message, and SYSOUT still says `CBTRNDYN: 12 SQL LINES READ`",
   "fixtures": [],
   "finding": "Long statements are silently truncated and the line count is not limited. Must be reproduced (source-only)."
  },
  {
   "id": "BR-TRT-036",
   "title": "PREPARE then EXECUTE, PREPARE failure path",
   "rule": "CBTRNDYN runs PREPARE DYNSTMT FROM :WS-SQL-STMT. If SQLCODE is not 0 it displays `CBTRNDYN: PREPARE FAILED SQLCODE ` + SQLCODE, moves 8 to RETURN-CODE and skips EXECUTE. Otherwise it runs EXECUTE DYNSTMT and always displays `CBTRNDYN: EXECUTE SQLCODE ` + SQLCODE (sign followed by 10 digits under ocesql, e.g. `+0000000000`).",
   "category": "control-flow",
   "sources": [
    {
     "file": "app/app-transaction-type-db2/cbl/CBTRNDYN.cbl",
     "startLine": 87,
     "endLine": 98
    }
   ],
   "fields": [
    "WS-SQL-STMT",
    "SQLCODE",
    "RETURN-CODE"
   ],
   "given": "DYNSQL `DELETE FROM TRANSACTION_TYPE WHERE TR_TYPE = '03'`",
   "when": "MNTTRDYN runs",
   "then": "SYSOUT `CBTRNDYN: EXECUTE SQLCODE +0000000000`; row 03 is deleted",
   "fixtures": [
    "fixtures/trantype-dynamic/d02-delete-statement"
   ],
   "finding": null
  },
  {
   "id": "BR-TRT-037",
   "title": "Statement errors surface at EXECUTE, not PREPARE, under ocesql",
   "rule": "Under Open COBOL ESQL the PREPARE returned SQLCODE 0 in every observed case, so the `PREPARE FAILED` branch was never reached: an unknown column surfaces at EXECUTE as -400 (golden d03) and an empty DYNSQL (statement length 0) surfaces at EXECUTE as -212 (scratch run). Db2 would normally reject such statements at PREPARE time with its own SQLCODEs.",
   "category": "edge-case",
   "sources": [
    {
     "file": "app/app-transaction-type-db2/cbl/CBTRNDYN.cbl",
     "startLine": 88,
     "endLine": 101
    }
   ],
   "fields": [
    "SQLCODE",
    "WS-SQL-STMT-LEN"
   ],
   "given": "DYNSQL `UPDATE TRANSACTION_TYPE SET NO_SUCH_COLUMN = 'x' WHERE TR_TYPE = '01'`",
   "when": "CBTRNDYN prepares and executes it",
   "then": "no PREPARE FAILED line; SYSOUT `CBTRNDYN: EXECUTE SQLCODE -0000000400`",
   "fixtures": [
    "fixtures/trantype-dynamic/d03-bad-statement"
   ],
   "finding": "The PREPARE error path was not reached under the harness and the codes differ from Db2 (-400/-212). The recorded ocesql behaviour must be reproduced."
  },
  {
   "id": "BR-TRT-038",
   "title": "EXECUTE SQLCODE < 0 sets RC 8; non-negative does not",
   "rule": "After EXECUTE only a negative SQLCODE moves 8 to RETURN-CODE. SQLCODE 0, including a dynamic UPDATE/DELETE that matches no row under ocesql, leaves RC unchanged. In every case the program continues to the dynamic CALL. Confirmed by an off-host scratch run of the legacy estate (not a golden case).",
   "category": "return-code",
   "sources": [
    {
     "file": "app/app-transaction-type-db2/cbl/CBTRNDYN.cbl",
     "startLine": 98,
     "endLine": 101
    }
   ],
   "fields": [
    "SQLCODE",
    "RETURN-CODE"
   ],
   "given": "DYNSQL `UPDATE TRANSACTION_TYPE SET TR_DESCRIPTION = 'x' WHERE TR_TYPE = '99'`",
   "when": "CBTRNDYN executes it",
   "then": "SYSOUT `CBTRNDYN: EXECUTE SQLCODE +0000000000`; no RC 8; COBTUPDT is still called",
   "fixtures": [],
   "finding": null
  },
  {
   "id": "BR-TRT-039",
   "title": "CBTRNDYN's RC 8 is not the step RC once COBTUPDT runs",
   "rule": "The step RC is the RETURN-CODE at COBTUPDT's STOP RUN, not the highest code set in the run. The RC 8 set by CBTRNDYN after a failed EXECUTE does not survive the dynamic CALL: with a clean COBTUPDT run the step ends RC 0 (scratch run); in d03 it ends RC 4 because COBTUPDT's own update fails.",
   "category": "return-code",
   "sources": [
    {
     "file": "app/app-transaction-type-db2/cbl/CBTRNDYN.cbl",
     "startLine": 98,
     "endLine": 101
    },
    {
     "file": "app/app-transaction-type-db2/cbl/CBTRNDYN.cbl",
     "startLine": 59,
     "endLine": 63
    },
    {
     "file": "app/app-transaction-type-db2/cbl/COBTUPDT.cbl",
     "startLine": 97,
     "endLine": 99
    },
    {
     "file": "app/app-transaction-type-db2/cbl/COBTUPDT.cbl",
     "startLine": 232,
     "endLine": 232
    }
   ],
   "fields": [
    "RETURN-CODE"
   ],
   "given": "DYNSQL with an unknown column and INPFILE `U01Purchase - updated`",
   "when": "MNTTRDYN runs",
   "then": "SYSOUT shows `EXECUTE SQLCODE -0000000400` and COBTUPDT's -400 error; rc.json STEP1 = 4 (not 8)",
   "fixtures": [
    "fixtures/trantype-dynamic/d03-bad-statement"
   ],
   "finding": "A failed ad-hoc statement does not show in the step RC (RC 0 if COBTUPDT has nothing to do). Looks wrong; must be reproduced."
  },
  {
   "id": "BR-TRT-040",
   "title": "Dynamic CALL of the program named in PARMS",
   "rule": "When PARM-LENGTH > 0, CBTRNDYN moves PARM-VALUE(1:PARM-LENGTH) to WS-PGM-NAME (X(8)), displays `CBTRNDYN: CALLING ` + WS-PGM-NAME and runs CALL WS-PGM-NAME with no USING and no ON EXCEPTION. The CALL happens whatever the dynamic SQL outcome. The target is known only from the JCL PARMS, not at compile time.",
   "category": "control-flow",
   "sources": [
    {
     "file": "app/app-transaction-type-db2/cbl/CBTRNDYN.cbl",
     "startLine": 59,
     "endLine": 63
    },
    {
     "file": "app/app-transaction-type-db2/cbl/CBTRNDYN.cbl",
     "startLine": 46,
     "endLine": 46
    },
    {
     "file": "app/app-transaction-type-db2/jcl/MNTTRDYN.jcl",
     "startLine": 33,
     "endLine": 33
    }
   ],
   "fields": [
    "PARM-LENGTH",
    "PARM-VALUE",
    "WS-PGM-NAME"
   ],
   "given": "PARMS('COBTUPDT') and a failed dynamic statement",
   "when": "CBTRNDYN reaches the CALL",
   "then": "SYSOUT `CBTRNDYN: CALLING COBTUPDT` followed by COBTUPDT's `OPEN FILE OK`",
   "fixtures": [
    "fixtures/trantype-dynamic/d01-update-then-apply",
    "fixtures/trantype-dynamic/d03-bad-statement"
   ],
   "finding": null
  },
  {
   "id": "BR-TRT-041",
   "title": "COBTUPDT's STOP RUN ends the run unit",
   "rule": "COBTUPDT ends with STOP RUN, not GOBACK, so control never returns to CBTRNDYN: `CBTRNDYN: END` is never displayed and its GOBACK is not reached. The end-of-task exit then commits and prints `DSNRUN: COMMIT AT END OF TASK` as the last line.",
   "category": "control-flow",
   "sources": [
    {
     "file": "app/app-transaction-type-db2/cbl/COBTUPDT.cbl",
     "startLine": 97,
     "endLine": 99
    },
    {
     "file": "app/app-transaction-type-db2/cbl/CBTRNDYN.cbl",
     "startLine": 64,
     "endLine": 65
    },
    {
     "file": "tools/stubs/DSNEXIT.cbl",
     "startLine": 13,
     "endLine": 21
    }
   ],
   "fields": [
    "RETURN-CODE"
   ],
   "given": "the standard MNTTRDYN run",
   "when": "COBTUPDT finishes its INPFILE",
   "then": "SYSOUT ends with `RECORD INSERTED SUCCESSFULLY` then `DSNRUN: COMMIT AT END OF TASK`; no `CBTRNDYN: END` line",
   "fixtures": [
    "fixtures/trantype-dynamic/d01-update-then-apply"
   ],
   "finding": "The driver's END message and any code after the CALL are dead in practice. Must be reproduced (no `CBTRNDYN: END` in SYSOUT)."
  },
  {
   "id": "BR-TRT-042",
   "title": "No PARMS: no CALL and normal end",
   "rule": "When PARM-LENGTH is 0 (RUN PROGRAM(CBTRNDYN) without PARMS) the CALL is skipped, `CBTRNDYN: END` is displayed and GOBACK returns to DSNRUN, whose STOP RUN triggers the end-of-task commit. A PARMS value longer than 8 characters is truncated to 8 in WS-PGM-NAME.",
   "category": "edge-case",
   "sources": [
    {
     "file": "app/app-transaction-type-db2/cbl/CBTRNDYN.cbl",
     "startLine": 59,
     "endLine": 65
    },
    {
     "file": "tools/stubs/DSNRUN.cbl",
     "startLine": 31,
     "endLine": 35
    },
    {
     "file": "tools/stubs/DSNRUN.cbl",
     "startLine": 51,
     "endLine": 52
    }
   ],
   "fields": [
    "PARM-LENGTH",
    "WS-PGM-NAME"
   ],
   "given": "SYSTSIN `RUN PROGRAM(CBTRNDYN) PLAN(CARDDEMO)` and a valid DYNSQL",
   "when": "the step runs",
   "then": "SYSOUT `CBTRNDYN: 0n SQL LINES READ`, `CBTRNDYN: EXECUTE SQLCODE ...`, `CBTRNDYN: END`, `DSNRUN: COMMIT AT END OF TASK`; INPFILE is not read",
   "fixtures": [],
   "finding": null
  },
  {
   "id": "BR-TRT-043",
   "title": "Dynamic SQL and COBTUPDT share one unit of work",
   "rule": "The dynamic statement and COBTUPDT's changes are in one unit of work committed at end of task: both persist together on success, and if any statement fails under PostgreSQL both are lost (see BR-TRT-029), including a dynamic statement that reported SQLCODE 0.",
   "category": "state-change",
   "sources": [
    {
     "file": "app/app-transaction-type-db2/cbl/CBTRNDYN.cbl",
     "startLine": 5,
     "endLine": 12
    },
    {
     "file": "app/app-transaction-type-db2/cbl/CBTRNDYN.cbl",
     "startLine": 95,
     "endLine": 97
    },
    {
     "file": "tools/stubs/DSNEXIT.cbl",
     "startLine": 14,
     "endLine": 14
    }
   ],
   "fields": [
    "TR_TYPE",
    "TR_DESCRIPTION"
   ],
   "given": "DYNSQL updating 02 to 'Payment - SEPA' and INPFILE `A04Authorization`",
   "when": "MNTTRDYN runs",
   "then": "both changes are committed: 02='Payment - SEPA' and 04='Authorization' padded; RC 0",
   "fixtures": [
    "fixtures/trantype-dynamic/d01-update-then-apply"
   ],
   "finding": null
  },
  {
   "id": "BR-TRT-044",
   "title": "Unqualified names and unpadded literals in dynamic SQL",
   "rule": "Dynamic statements name TRANSACTION_TYPE without the CARDDEMO qualifier; under PostgreSQL this resolves to the carddemo schema through the default search_path (\"$user\" = carddemo), standing in for the Db2 plan qualifier. Values supplied as SQL literals are stored exactly as written (no 50-byte padding), unlike COBTUPDT's host variables.",
   "category": "data-mapping",
   "sources": [
    {
     "file": "app/app-transaction-type-db2/cbl/CBTRNDYN.cbl",
     "startLine": 88,
     "endLine": 90
    },
    {
     "file": "db/postgres/01-transaction-type.sql",
     "startLine": 4,
     "endLine": 8
    }
   ],
   "fields": [
    "TR_TYPE",
    "TR_DESCRIPTION"
   ],
   "given": "DYNSQL `UPDATE TRANSACTION_TYPE SET TR_DESCRIPTION = 'Payment - SEPA' WHERE TR_TYPE = '02'`",
   "when": "MNTTRDYN runs",
   "then": "carddemo.transaction_type row 02 holds `Payment - SEPA` with no trailing spaces, while 04 inserted by COBTUPDT is padded to 50",
   "fixtures": [
    "fixtures/trantype-dynamic/d01-update-then-apply"
   ],
   "finding": null
  },
  {
   "id": "BR-TRT-045",
   "title": "ocesql source normalisation (harness)",
   "rule": "Harness behaviour: tools/esql_prep.py builds scratch copies for Open COBOL ESQL without editing app/: it strips columns 73-80, joins the three-line EXEC SQL INCLUDE SQLCA into one line and removes EXEC SQL DECLARE ... TABLE from DCLGEN members. It does not change program logic, so every rule above is the logic of the unmodified estate source.",
   "category": "data-mapping",
   "sources": [
    {
     "file": "tools/esql_prep.py",
     "startLine": 29,
     "endLine": 55
    },
    {
     "file": "app/app-transaction-type-db2/dcl/DCLTRTYP.dcl",
     "startLine": 28,
     "endLine": 31
    },
    {
     "file": "app/app-transaction-type-db2/cbl/COBTUPDT.cbl",
     "startLine": 50,
     "endLine": 52
    }
   ],
   "fields": [
    "DCLTRANSACTION-TYPE",
    "SQLCA"
   ],
   "given": "COBTUPDT.cbl with a three-line INCLUDE SQLCA",
   "when": "make build runs esql_prep",
   "then": "work/esql/COBTUPDT.cbl has a one-line `EXEC SQL INCLUDE SQLCA END-EXEC.` and the original file is unchanged",
   "fixtures": [],
   "finding": null
  }
 ]
}
```
