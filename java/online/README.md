# java/online - CICS account flow as REST, HLASM and PL/I routine ports

Slice `online` of the CardDemo Java 21 target (package `com.carddemo.online`, Flyway V400-V499,
schema `carddemo_online`).

> **Analysis-only legacy.** CICS/BMS (COSGN00C, COMEN01C, COACTVWC, COACTUPC), HLASM (COBDATFT,
> MVSWAIT) and PL/I (CBFEECAL) cannot be executed off-host: there is no CICS region, BMS or HLASM
> assembler, or PL/I compiler in the estate. **None of them was executed.** This slice has no
> regression (parity) layer and no `parity-chains` file. It is a specification-driven port,
> verified by JUnit tests derived from business rules BR-ONL-001..074 and from the CBFEECAL worked
> examples in `docs/LEGACY-LANGUAGES.md`, plus Playwright journeys against the Java service.

## Design

| Package | Contents |
|---|---|
| `signon` | `SignOnService`: COSGN00C |
| `menu` | `MenuService`, `MenuOption`: COMEN01C with the COMEN02Y option table; `INQUIRE PROGRAM` is an injectable predicate (COPAUS0C is not installed by default) |
| `account` | `AccountViewService` (COACTVWC), `AccountUpdateService` (COACTUPC), `FieldEdits` (the 1215-1280 edit paragraphs and the CSUTLDPY date edits), `AccountLookup` (the shared 9000-READ-ACCT), `LookupCodes` (generated from CSLKPCDY: 410 area codes, 56 state codes, 240 state/zip prefixes) |
| `routines` | `DateFormatRoutine` (HLASM COBDATFT), `WaitRoutine` (HLASM MVSWAIT + COBSWAIT), `LateFeeCalculator` (PL/I CBFEECAL), `FeeDriver` (COBOL CBFEEDRV request area and FEEOUT line) |
| `store` | `OnlineStore` (CICS file requests returning RESP/RESP2), `JdbcOnlineStore` (PostgreSQL, schema `carddemo_online`), `InMemoryOnlineStore` (unit tests; can force any RESP) |
| `web` | `OnlineController`: REST under `/api/online` |
| (root) | `Commarea` (COCOM01Y), `Pic` (PIC X padding, LOW-VALUES, NUMERIC, NUMVAL-C, `+ZZZ,ZZZ,ZZZ.99`), `Aid` (EIBAID / CSSTRPFY PF13-24 folding), `Messages` (CSMSG01Y) |

**Pseudo-conversation.** CICS keeps state in the commarea between tasks. Over REST, the client
carries the `commarea`. The update screen also carries its `state` (ACUP-CHANGE-ACTION) and the
`snapshot` (ACUP-OLD-DETAILS), exactly like WS-THIS-PROGCOMMAREA, so the service is stateless.
REST calls are re-entries (CDEMO-PGM-REENTER). The empty map that a fresh XCTL sends is the
console screen load; the services still implement the fresh-entry branch, and the unit tests
cover it.

**Storage semantics.** Text fields are kept as PIC X storage: blank-padded, with LOW-VALUES
(U+0000) meaning "not supplied". An input of spaces or `*` becomes LOW-VALUES (BR-ONL-034). Money
is `BigDecimal` at scale 2. `COMPUTE` without ROUNDED truncates (`RoundingMode.DOWN`). PL/I
DECIMAL FIXED assignment truncates too, and raises SIZE when the integer part does not fit.
Tables use `CHAR(n)`, so PostgreSQL returns blank-padded values like the VSAM record area.

**Messages** are copied verbatim, including the double space in "must  be" (F-03) and the missing
space in "...is coming soon". The first error wins (`IF WS-RETURN-MSG-OFF`), and messages are cut
to WS-RETURN-MSG X(75).

**Optimistic concurrency.** PF5 reads both records `FOR UPDATE NOWAIT` (a lock failure means the
record is held by another task). It compares the current records with the snapshot
(9700-CHECK-CHANGE-IN-REC), then rewrites the account and then the customer. If the customer
rewrite fails, the Spring transaction is rolled back (SYNCPOINT ROLLBACK).

## CICS map -> REST / console screen

| Legacy (transaction, map) | REST | Console route |
|---|---|---|
| COSGN00C (CC00, COSGN00/COSGN0A) | `POST /api/online/signon` `{action,userId,password}` -> `{screen,message,cursor,next,commarea}` | `/online/signon` |
| COMEN01C (CM00, COMEN01/COMEN1A) | `GET /api/online/menu?userType=`; `POST /api/online/menu/select` `{action,option,userType,commarea}` | `/online/menu` |
| COACTVWC (CAVW, COACTVW/CACTVWA) | `GET /api/online/accounts/{acctId}` -> account + customer blocks, legacy messages | `/online/accounts` |
| COACTUPC (CAUP, COACTUP/CACTUPA) | `POST /api/online/accounts/{acctId}/changes` (search, ENTER edits, PF12, PF3); `PUT /api/online/accounts/{acctId}` (PF5 save) `{action,state,snapshot,fields,commarea}` | `/online/accounts/update` |
| HLASM COBDATFT | `POST /api/online/dates/convert` `{inType,inDate,outType}` -> `{outDate,errorMsg,returnCode}` | `/online/dates` |
| PL/I CBFEECAL (+ CBFEEDRV line) | `POST /api/online/fees` `{acctId,groupId,cycleBal,lateDays,cycleDays}` -> `{feeAmt,avgDaily,rc,sysprint,feeLine}` | `/online/fees` |
| HLASM MVSWAIT / COBSWAIT | none (`WaitRoutine`, injectable sleeper) | none |

PF keys map to the `action` field (`ENTER`, `PF3`, `PF5`, `PF12`, ...). Admin sign-on XCTLs to
COADM01C, the admin menu, which is out of scope. The console then shows COMEN01C with a note.

## HLASM linkage and Java equivalents

* **COBDATFT** (`CALL 'COBDATFT' USING CODATECN-REC`) follows standard OS linkage.
  `STM R14,R12,12(R13)` saves the caller's registers in the caller's save area. The routine
  chains its own 18-fullword `SAVE` area (`ST R13,SAVE+4` / `ST R12,8(,R13)`), uses R12 as the
  base register, and reads the one-entry parameter list: `L R2,0(R1)` gives R2 = the address of
  CODATECN-REC (DSECT COCDATFT: COINTYPE CL1, COINPDT CL20, COOUTYPE CL1, COOUTDT CL20, COERMSG
  CL38). On exit, `L R13,SAVE+4; LM R14,R12,12(R13); SR R15,R15; BR R14` always returns RC 0.
  In Java, `DateFormatRoutine.call(char[] rec)` updates an 80-char record in place and returns 0.
  It never throws; errors appear only in COERMSG.
* **MVSWAIT** has no save area of its own. `STM 14,12,12(13)`, then `L 5,0(1)` / `L 1,0(5)` load
  the delay fullword, which is stored in BINLBL inside the CSECT (not reentrant). `ASMWAIT`
  (STIMER WAIT, 1/100 s) waits, then `LM`, then `MVI 12(13),X'FF'`, then `SR 15,15`. In Java,
  `WaitRoutine.mvswait(hundredths)` calls an injectable `Sleeper` with hundredths x 10 ms; tests
  use a no-op or a recorder. `cobswait(card)` reproduces COBSWAIT's unchecked MOVE of an X(8)
  SYSIN card to 9(8) COMP by taking the low nibble of each EBCDIC byte.
* **CBFEECAL** (PL/I): DECIMAL FIXED maps to `BigDecimal`, and FIXED BINARY(15) maps to `int`.
  Truncating assignment is `RoundingMode.DOWN`. ON ZERODIVIDE gives RC 8 with fee and average 0.
  ON SIZE gives RC 8 with fee 0. ON ERROR gives RC 8 and leaves the fee as it was. Each ON unit
  writes its PUT SKIP LIST line to `sysprint`. The floor is 5.00, the cap is 35.00, and a GOLD
  balance under 100.00 is waived with RC 4.

## Legacy behaviour reproduced deliberately (findings)

The rules file lists F-01..F-15. Every one that affects behaviour is reproduced, and reading the
code for the conversion found four more (F-16..F-19). All are for a business decision; none was
fixed.

| # | Reproduced as | Test |
|---|---|---|
| F-01 | The account/customer not-found guards never fire: the customer is read even when the account is missing, and COACTUPC snapshots whatever was read. The view shows an empty account block when only the customer exists. | `BR_ONL_025`, `BR_ONL_027` |
| F-02 | PF3 from view/update sets user type `U` (an admin is downgraded). | `BR_ONL_021`, `BR_ONL_030` |
| F-03 | 'No input received' overwrites 'Account number not provided'; the double space in "must  be" is kept; the info line is always the prompt. | `BR_ONL_022`, `BR_ONL_023` |
| F-04 | The blank-phone test checks the area code twice (`A = SPACES OR C = LOW-VALUES`). | `BR_ONL_050` |
| F-05 | Status (and primary holder) changes are detected case-insensitively, but the Y/N edit is case-sensitive: 'Y' -> 'y' gives "No change detected". | `BR_ONL_038` |
| F-06 | SSN part 1 '000' gets 'must not be zero.', not the 000/666/9xx message. | `BR_ONL_044` |
| F-07 | Cycle fields are labelled "...Limit"; only 5 zip characters are edited; address line 2, government id and group id are not edited; no credit/cash cross-check; a DOB equal to today is "in the future". | `BR_ONL_039`, `BR_ONL_043`, `BR_ONL_047` |
| F-08 | `+ZZZ,ZZZ,ZZZ.99` has 9 integer digits: 1,234,567,890.12 shows as `+234,567,890.12`. | `BR_ONL_058` |
| F-09 | `GO TO 9600-WRITE-PROCESSING-EXIT` out of 9700 becomes an early return from the save. | `BR_ONL_055` |
| F-10 | Out-of-range menu options are not range-checked before the admin subscript (Java treats storage outside the table as not 'A'); no space before "is coming soon". | `BR_ONL_014`, `BR_ONL_017` |
| F-11 | The wrong-password branch does not set WS-ERR-FLG (no visible effect). | `BR_ONL_009` |
| F-12 | COBDATFT `CLC COINPDT+4,=C'-'` is a 20-byte compare against the literal pool, so '2025-03-15' as type 1 becomes '2025--0-3-'. The type-2 separator check is commented out, so '2025/03/15' is accepted. | `BR_ONL_062_finding12_*`, `BR_ONL_062` |
| F-13 | MVSWAIT keeps the delay in its CSECT (not reentrant); COBSWAIT does not check its SYSIN card (low-nibble MOVE). | `BR_ONL_064` |
| F-14 | CBFEECAL is `OPTIONS(MAIN)` with a POINTER parameter while CBFEEDRV passes FEE-REQ BY REFERENCE (a linkage risk). Java implements the intended contract: it operates on FEE-REQ. | `BR_ONL_065` |
| F-15 | The average-daily division runs before the late-days test (0 cycle days gives RC 8 even when the account is not late); the GOLD test is case-sensitive. | `BR_ONL_066` |
| **F-16** (new) | COACTUPC: a **customer** lock failure sets 'Could not lock customer record for update', but 2000-DECIDE-ACTION tests only COULD-NOT-LOCK-ACCT-FOR-UPDATE, so the state falls through `WHEN OTHER` to 'C' ("Changes committed to database") although nothing was written. The rules text (BR-ONL-054) says state 'L'; the code wins. | `BR_ONL_054` |
| **F-17** (new) | In state 'N', 1200-EDIT-MAP-INPUTS skips all edits, so whatever is received with PF5 is saved without re-validation. On a 3270 the fields are expected to be protected in this state; over REST the console sends back the validated values. | (behaviour of `editMapInputs`: early exit in state N) |
| **F-18** (new) | 9600 INITIALIZEs ACCT-UPDATE-RECORD and never moves ACCT-ADDR-ZIP, so a save blanks the account's zip. | `BR_ONL_056` |
| **F-19** (new) | Blank optional inputs become LOW-VALUES, but stored blanks are SPACES, and `FUNCTION TRIM` does not trim LOW-VALUES. A customer with a blank optional field (middle name, address line 2, phone 2) therefore always shows a "change", so "No change detected" never appears for that customer. LOW-VALUES written by a save are stored as blanks (PostgreSQL text cannot hold U+0000). | (behaviour of `changeHasOccurred`; seeded account 99 has no blank optional fields) |

9700-CHECK-CHANGE-IN-REC compares the account status exactly but most customer text fields
case-insensitively (`UPPER-CASE`), and the group id through `LOWER-CASE`. This is reproduced
as written.

## Data

Flyway `V400__online_schema.sql` creates schema `carddemo_online` with `user_security`
(CSUSR01Y), `customer` (CVCUS01Y), `account` (CVACT01Y) and `card_xref` (CVACT03Y, indexed by
account like CXACAIX). `V401__online_seed.sql` loads synthetic data only: users ADMIN001 (type A)
and USER0001/USER0002 (type U), and accounts 00000000099-00000000101 with synthetic customers and
card numbers. It uses schema `carddemo_online`, not `carddemo`, because every batch chain run
truncates `carddemo`.

## Tests

* `make test-unit`: 82 JUnit tests in `src/test/java/com/carddemo/online` (no database; they use
  `InMemoryOnlineStore`). At least one test per BR id, each method named `BR_ONL_NNN_...`. The six
  CBFEECAL worked examples are each a test (`LateFeeCalculatorTest#BR_ONL_0xx_workedExampleN_*`).
* `ui/e2e/online.spec.ts`: 4 Playwright journeys titled `[online] ...`: admin sign-on -> menu ->
  account view of 00000000099 -> fee example 1 (30.86); wrong password; date converter; account
  update edit, validate and PF12.
* Regression: none (see the note at the top).

## BR coverage

| BR | Rule | Implementation (`com.carddemo.online.`) | Test(s) |
|---|---|---|---|
| BR-ONL-001 | Sign-on first display | `signon.SignOnService#process / firstDisplay` | `SignOnServiceTest#BR_ONL_001_firstDisplayIsBlankWithCursorOnUserId` |
| BR-ONL-002 | Sign-on PF3 ends the session | `signon.SignOnService#process / firstDisplay` | `SignOnServiceTest#BR_ONL_002_pf3SendsThankYouAndEndsSession` |
| BR-ONL-003 | Sign-on rejects keys other than ENTER and PF3 | `signon.SignOnService#process / firstDisplay` | `SignOnServiceTest#BR_ONL_003_otherKeysAreInvalid` |
| BR-ONL-004 | User ID is mandatory | `signon.SignOnService#process / firstDisplay` | `SignOnServiceTest#BR_ONL_004_userIdIsMandatoryAndUsrsecIsNotRead` |
| BR-ONL-005 | Password is mandatory | `signon.SignOnService#process / firstDisplay` | `SignOnServiceTest#BR_ONL_005_passwordIsMandatory` |
| BR-ONL-006 | Credentials are case-insensitive | `signon.SignOnService#process / firstDisplay` | `SignOnServiceTest#BR_ONL_006_credentialsAreUpperCasedBeforeTheCheck` |
| BR-ONL-007 | Unknown user | `signon.SignOnService#process / firstDisplay` | `SignOnServiceTest#BR_ONL_007_unknownUser` |
| BR-ONL-008 | Security file error | `signon.SignOnService#process / firstDisplay` | `SignOnServiceTest#BR_ONL_008_securityFileError` |
| BR-ONL-009 | Wrong password | `signon.SignOnService#process / firstDisplay` | `SignOnServiceTest#BR_ONL_009_wrongPassword` |
| BR-ONL-010 | Successful sign-on routes by user type | `signon.SignOnService#process / firstDisplay` | `SignOnServiceTest#BR_ONL_010_successRoutesByUserType` |
| BR-ONL-011 | Menu requires a session | `menu.MenuService#display` | `MenuServiceTest#BR_ONL_011_menuWithoutCommareaGoesToSignOn` |
| BR-ONL-012 | Menu first display lists eleven options | `menu.MenuService#display` | `MenuServiceTest#BR_ONL_012_firstDisplayListsElevenOptionsAndSetsReenter` |
| BR-ONL-013 | Menu option is right-justified and zero-filled | `menu.MenuService#select` | `MenuServiceTest#BR_ONL_013_optionIsRightJustifiedAndZeroFilled` |
| BR-ONL-014 | Menu option range check | `menu.MenuService#select` | `MenuServiceTest#BR_ONL_014_optionRangeCheck` |
| BR-ONL-015 | Admin-only menu options | `menu.MenuService#select` | `MenuServiceTest#BR_ONL_015_adminOnlyOptionRejectedForUser` |
| BR-ONL-016 | Pending-authorization option checks installation | `menu.MenuService#select` | `MenuServiceTest#BR_ONL_016_pendingAuthorizationNotInstalled` |
| BR-ONL-017 | Placeholder options show 'coming soon' | `menu.MenuService#select` | `MenuServiceTest#BR_ONL_017_dummyProgramIsComingSoon` |
| BR-ONL-018 | Valid menu option transfers control | `menu.MenuService#select` | `MenuServiceTest#BR_ONL_018_validOptionTransfersControl` |
| BR-ONL-019 | Menu PF3 and invalid keys | `menu.MenuService#select` | `MenuServiceTest#BR_ONL_019_pf3ReturnsToSignOnOtherKeysInvalid` |
| BR-ONL-020 | Account view entry and PF-key mapping | `account.AccountViewService#process` | `AccountViewServiceTest#BR_ONL_020_freshEntryAndPfKeyMapping` |
| BR-ONL-021 | Account view PF3 return | `account.AccountViewService#process` | `AccountViewServiceTest#BR_ONL_021_pf3ReturnsToCallerAsUserType_U` |
| BR-ONL-022 | Account view blank search key | `account.AccountViewService#process` | `AccountViewServiceTest#BR_ONL_022_blankOrStarSearchKey` |
| BR-ONL-023 | Account view key must be a non-zero 11-digit number | `account.AccountViewService#process` | `AccountViewServiceTest#BR_ONL_023_keyMustBeNonZero11Digits` |
| BR-ONL-024 | Account lookup chain: xref, account, customer | `account.AccountLookup#read` | `AccountViewServiceTest#BR_ONL_024_lookupChainXrefAccountCustomer` |
| BR-ONL-025 | Account view not-found messages | `account.AccountLookup#read` | `AccountViewServiceTest#BR_ONL_025_notFoundMessagesTruncatedTo75` |
| BR-ONL-026 | Account view file errors | `account.AccountLookup#read` | `AccountViewServiceTest#BR_ONL_026_fileErrorMessage` |
| BR-ONL-027 | Account view display formatting | `account.AccountViewService#accountFields/customerFields` | `AccountViewServiceTest#BR_ONL_027_displayFormatting` |
| BR-ONL-028 | Account update PF-key validity | `account.AccountUpdateService#process` | `AccountUpdateServiceTest#BR_ONL_028_pf5BeforeValidationActsAsEnter` |
| BR-ONL-029 | Account update state machine | `account.AccountUpdateService#process` | `AccountUpdateServiceTest#BR_ONL_029_stateMachineAndInfoMessages` |
| BR-ONL-030 | Account update PF3 exit commits | `account.AccountUpdateService#process` | `AccountUpdateServiceTest#BR_ONL_030_pf3SyncpointsAndReturnsAsUser` |
| BR-ONL-031 | Account update fresh entry and post-save reset | `account.AccountUpdateService#process` | `AccountUpdateServiceTest#BR_ONL_031_freshEntryAndResetAfterSave` |
| BR-ONL-032 | Account update search key edit | `account.AccountUpdateService#editAccount` | `AccountUpdateServiceTest#BR_ONL_032_searchKeyEdit` |
| BR-ONL-033 | Account update fetch and snapshot | `account.AccountUpdateService#readAcct/storeFetchedData` | `AccountUpdateServiceTest#BR_ONL_033_fetchSnapshotsOldValues` |
| BR-ONL-034 | Blank or '*' fields mean 'not supplied' | `account.AccountUpdateService#in` | `AccountUpdateServiceTest#BR_ONL_034_starMeansNotSupplied` |
| BR-ONL-035 | Money input conversion truncates | `account.AccountUpdateService#money, Pic#numvalC/s9v2` | `AccountUpdateServiceTest#BR_ONL_035_moneyInputTruncates` |
| BR-ONL-036 | Change detection before editing | `account.AccountUpdateService#changeHasOccurred` | `AccountUpdateServiceTest#BR_ONL_036_changeDetectionIsTrimmedAndCaseInsensitive` |
| BR-ONL-037 | First error wins; all edits run | `account.AccountUpdateService#editMapInputs` | `AccountUpdateServiceTest#BR_ONL_037_firstErrorWins` |
| BR-ONL-038 | Account status and primary card holder must be Y or N | `account.FieldEdits#yesNo` | `AccountUpdateServiceTest#BR_ONL_038_yesNoFields` |
| BR-ONL-039 | Signed money fields are required and numeric | `account.FieldEdits#signed9v2` | `AccountUpdateServiceTest#BR_ONL_039_signedMoneyFields` |
| BR-ONL-040 | Date edit: year and century | `account.FieldEdits#date` | `AccountUpdateServiceTest#BR_ONL_040_yearAndCentury` |
| BR-ONL-041 | Date edit: month and day ranges | `account.FieldEdits#date` | `AccountUpdateServiceTest#BR_ONL_041_monthAndDayRanges` |
| BR-ONL-042 | Date edit: day/month combinations and leap years | `account.FieldEdits#date` | `AccountUpdateServiceTest#BR_ONL_042_dayMonthCombinationsAndLeapYears` |
| BR-ONL-043 | Date of birth cannot be today or later | `account.FieldEdits#dateOfBirth` | `AccountUpdateServiceTest#BR_ONL_043_dateOfBirthMustBeBeforeToday` |
| BR-ONL-044 | SSN edit | `account.FieldEdits#ssn` | `AccountUpdateServiceTest#BR_ONL_044_ssnEdit` |
| BR-ONL-045 | FICO score range | `account.FieldEdits#numReqd/ficoRange` | `AccountUpdateServiceTest#BR_ONL_045_ficoRange` |
| BR-ONL-046 | Names must be alphabetic | `account.FieldEdits#alphaReqd/alphaOpt` | `AccountUpdateServiceTest#BR_ONL_046_namesAlphabeticOnly` |
| BR-ONL-047 | Address, city and country edits | `account.FieldEdits#mandatory/alphaReqd` | `AccountUpdateServiceTest#BR_ONL_047_addressCityCountry` |
| BR-ONL-048 | State code must be a US state | `account.FieldEdits#usState` | `AccountUpdateServiceTest#BR_ONL_048_stateCode` |
| BR-ONL-049 | Zip code and state/zip consistency | `account.FieldEdits#numReqd/stateZip` | `AccountUpdateServiceTest#BR_ONL_049_zipAndStateZipCombination` |
| BR-ONL-050 | Phone number edit | `account.FieldEdits#phone` | `AccountUpdateServiceTest#BR_ONL_050_phoneNumberEdit` |
| BR-ONL-051 | EFT account id edit | `account.FieldEdits#numReqd` | `AccountUpdateServiceTest#BR_ONL_051_eftAccountId` |
| BR-ONL-052 | Validated changes ask for confirmation | `account.AccountUpdateService#editMapInputs/send` | `AccountUpdateServiceTest#BR_ONL_052_validChangesAskForConfirmation` |
| BR-ONL-053 | PF12 cancels changes | `account.AccountUpdateService#decideAction (PF12)` | `AccountUpdateServiceTest#BR_ONL_053_pf12CancelsChanges` |
| BR-ONL-054 | Save locks both records | `account.AccountUpdateService#writeProcessing` | `AccountUpdateServiceTest#BR_ONL_054_saveLocksBothRecords` |
| BR-ONL-055 | Optimistic concurrency check | `account.AccountUpdateService#changedBeforeUpdate` | `AccountUpdateServiceTest#BR_ONL_055_optimisticConcurrencyCheck` |
| BR-ONL-056 | Saved record formats | `account.AccountUpdateService#writeProcessing` | `AccountUpdateServiceTest#BR_ONL_056_savedRecordFormats` |
| BR-ONL-057 | Rewrite outcome and rollback | `account.AccountUpdateService#writeProcessing` | `AccountUpdateServiceTest#BR_ONL_057_rewriteFailureRollsBack` |
| BR-ONL-058 | Currency display picture | `Pic#currency` | `AccountUpdateServiceTest#BR_ONL_058_currencyDisplayPicture` |
| BR-ONL-059 | Unexpected state abends | `account.AccountUpdateService#decideAction (abend)` | `AccountUpdateServiceTest#BR_ONL_059_unexpectedStateAbends` |
| BR-ONL-060 | COBDATFT linkage | `routines.DateFormatRoutine#call` | `RoutinesTest#BR_ONL_060_linkageUpdatesRecordInPlaceAndReturnsZero` |
| BR-ONL-061 | COBDATFT YYYYMMDD to YYYY-MM-DD | `routines.DateFormatRoutine#call` | `RoutinesTest#BR_ONL_061_yyyymmddToIsoDate` |
| BR-ONL-062 | COBDATFT YYYY-MM-DD to YYYYMMDD | `routines.DateFormatRoutine#call` | `RoutinesTest#BR_ONL_062_isoDateToYyyymmddWithoutSeparatorCheck`<br>`RoutinesTest#BR_ONL_062_finding12_hyphenGuardComparesTwentyBytes` |
| BR-ONL-063 | COBDATFT invalid input | `routines.DateFormatRoutine#call` | `RoutinesTest#BR_ONL_063_invalidInputMessageLeavesOutputUnchanged` |
| BR-ONL-064 | MVSWAIT timed wait | `routines.WaitRoutine#mvswait/cobswait` | `RoutinesTest#BR_ONL_064_mvswaitSleepsHundredthsInjectable` |
| BR-ONL-065 | CBFEECAL request area and driver | `routines.FeeDriver#buildRequest` | `LateFeeCalculatorTest#BR_ONL_065_driverBuildsRequestArea` |
| BR-ONL-066 | Average daily balance truncates | `routines.LateFeeCalculator#calculate` | `LateFeeCalculatorTest#BR_ONL_066_averageDailyBalanceTruncatesAndIsAlwaysComputed` |
| BR-ONL-067 | Nothing overdue: no fee | `routines.LateFeeCalculator#calculate` | `LateFeeCalculatorTest#BR_ONL_067_workedExample6_nothingOverdue`<br>`LateFeeCalculatorTest#BR_ONL_067_negativeLateDaysMeansNoFee` |
| BR-ONL-068 | GOLD small-balance waiver | `routines.LateFeeCalculator#calculate` | `LateFeeCalculatorTest#BR_ONL_068_workedExample4_goldWaiverRc4`<br>`LateFeeCalculatorTest#BR_ONL_068_goldAtHundredIsNotWaived` |
| BR-ONL-069 | Late fee is 2.5% truncated to cents | `routines.LateFeeCalculator#calculate` | `LateFeeCalculatorTest#BR_ONL_069_workedExample1_feeTruncatesTo3086`<br>`LateFeeCalculatorTest#BR_ONL_069_lateFeeTruncatesToCents` |
| BR-ONL-070 | Late fee floor | `routines.LateFeeCalculator#calculate` | `LateFeeCalculatorTest#BR_ONL_070_workedExample2_floorFiveDollars`<br>`LateFeeCalculatorTest#BR_ONL_070_feeJustBelowFloorIsLifted` |
| BR-ONL-071 | Late fee cap | `routines.LateFeeCalculator#calculate` | `LateFeeCalculatorTest#BR_ONL_071_workedExample3_capThirtyFiveDollars`<br>`LateFeeCalculatorTest#BR_ONL_071_feeJustAboveCapIsCapped` |
| BR-ONL-072 | Zero-day cycle raises ZERODIVIDE | `routines.LateFeeCalculator#calculate` | `LateFeeCalculatorTest#BR_ONL_072_workedExample5_zeroDivideRc8`<br>`LateFeeCalculatorTest#BR_ONL_072_zeroDivideMessage` |
| BR-ONL-073 | SIZE and ERROR conditions | `routines.LateFeeCalculator#calculate` | `LateFeeCalculatorTest#BR_ONL_073_sizeConditionRc8`<br>`LateFeeCalculatorTest#BR_ONL_073_unexpectedErrorRc8` |
| BR-ONL-074 | Late-fee output line | `routines.FeeDriver#feeLine` | `LateFeeCalculatorTest#BR_ONL_074_feeOutputLine` |
