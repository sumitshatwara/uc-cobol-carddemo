# Business rules: Interest (INTCALC / CBACT04C)

Slice `interest` of the CardDemo batch estate: job `app/jcl/INTCALC.jcl` (STEP15, `PGM=CBACT04C,PARM='2022071800'`), run after the setup jobs DEFGDGB, ACCTFILE, XREFFILE, TCATBALF and DISCGRP (`tools/runjcl/chains.json`). CBACT04C reads every transaction-category balance, looks up the annual rate for the account's disclosure group, writes one interest transaction per interest-bearing balance to a new SYSTRAN generation and adds the interest to the account balance.

Sources read line by line: `app/cbl/CBACT04C.cbl`, `app/jcl/INTCALC.jcl`, `app/jcl/XREFFILE.jcl` (AIX), copybooks `CVTRA01Y` (TCATBAL), `CVACT01Y` (account), `CVACT03Y` (card xref), `CVTRA02Y` (disclosure group), `CVTRA05Y` (transaction), fixtures `fixtures/interest/i01`..`i07` (README and expected output) and `tools/parity/naive_ref.py`. Line numbers refer to the files as committed on the integration branch. Fields marked `CBACT04C WORKING-STORAGE`, `FD` or `LINKAGE` are declared in the program itself.

Fixture citations mean the golden files under `expected/` show the behaviour. Rules without a fixture are derived from the source only (mostly error and abend paths); no fixture exercises an abend. Nothing was executed for this analysis; the statements about fixtures come from reading the recorded golden files.

## Summary

| Category | Rules |
|---|---|
| validation | 3 |
| calculation | 4 |
| rounding | 2 |
| state-change | 8 |
| return-code | 7 |
| sysout | 4 |
| edge-case | 10 |
| control-flow | 3 |
| data-access | 6 |
| linkage | 1 |
| **total** | **48** |

| ID | Title | Category |
|---|---|---|
| [BR-INT-001](#br-int-001) | Start and end banners | sysout |
| [BR-INT-002](#br-int-002) | Run parameter supplies the transaction-id prefix | linkage |
| [BR-INT-003](#br-int-003) | Files and open modes | data-access |
| [BR-INT-004](#br-int-004) | Open failure messages | sysout |
| [BR-INT-005](#br-int-005) | Balances are read sequentially in key order | data-access |
| [BR-INT-006](#br-int-006) | End of balance file | control-flow |
| [BR-INT-007](#br-int-007) | Balance file read error | return-code |
| [BR-INT-008](#br-int-008) | Each balance record is echoed to SYSOUT | sysout |
| [BR-INT-009](#br-int-009) | Account break detection | control-flow |
| [BR-INT-010](#br-int-010) | First record does not trigger an update | control-flow |
| [BR-INT-011](#br-int-011) | Interest accumulator reset per account | state-change |
| [BR-INT-012](#br-int-012) | Account lookup by balance account id | data-access |
| [BR-INT-013](#br-int-013) | Missing account abends | edge-case |
| [BR-INT-014](#br-int-014) | Card lookup by account id (alternate key) | data-access |
| [BR-INT-015](#br-int-015) | Missing card cross-reference abends | edge-case |
| [BR-INT-016](#br-int-016) | Accounts without balance rows are untouched | data-access |
| [BR-INT-017](#br-int-017) | Disclosure-group key | data-access |
| [BR-INT-018](#br-int-018) | Fallback to the DEFAULT disclosure group | validation |
| [BR-INT-019](#br-int-019) | Accounts in the DEFAULT group use it directly | validation |
| [BR-INT-020](#br-int-020) | Disclosure-group read error | return-code |
| [BR-INT-021](#br-int-021) | Missing DEFAULT group row abends | edge-case |
| [BR-INT-022](#br-int-022) | Zero-rate categories are skipped | validation |
| [BR-INT-023](#br-int-023) | Monthly interest formula | calculation |
| [BR-INT-024](#br-int-024) | Interest is truncated, not rounded | rounding |
| [BR-INT-025](#br-int-025) | Non-tie sub-cent remainders | rounding |
| [BR-INT-026](#br-int-026) | Negative balances produce negative interest | calculation |
| [BR-INT-027](#br-int-027) | Interest accumulated per account | calculation |
| [BR-INT-028](#br-int-028) | Zero balance with a non-zero rate still posts | edge-case |
| [BR-INT-029](#br-int-029) | Silent overflow on large amounts | edge-case |
| [BR-INT-030](#br-int-030) | Fees calculation is a stub | calculation |
| [BR-INT-031](#br-int-031) | One interest transaction per non-zero-rate balance | state-change |
| [BR-INT-032](#br-int-032) | Transaction id format | state-change |
| [BR-INT-033](#br-int-033) | Fixed classification of interest transactions | state-change |
| [BR-INT-034](#br-int-034) | Transaction description | state-change |
| [BR-INT-035](#br-int-035) | Amount, merchant and card fields | state-change |
| [BR-INT-036](#br-int-036) | Transaction timestamps | state-change |
| [BR-INT-037](#br-int-037) | Transaction write error | return-code |
| [BR-INT-038](#br-int-038) | Zoned signed output encoding | edge-case |
| [BR-INT-039](#br-int-039) | Account balance update and cycle reset | state-change |
| [BR-INT-040](#br-int-040) | Accounts with only zero-rate categories are still reset | edge-case |
| [BR-INT-041](#br-int-041) | Account rewrite error | return-code |
| [BR-INT-042](#br-int-042) | Last account on the balance file is never updated | edge-case |
| [BR-INT-043](#br-int-043) | Empty balance file | edge-case |
| [BR-INT-044](#br-int-044) | Close files and close errors | return-code |
| [BR-INT-045](#br-int-045) | File status display format | sysout |
| [BR-INT-046](#br-int-046) | Normal completion return code | return-code |
| [BR-INT-047](#br-int-047) | Abend routine | return-code |
| [BR-INT-048](#br-int-048) | No rollback on abend | edge-case |

## Rules

### BR-INT-001

**Start and end banners**

The program writes `START OF EXECUTION OF PROGRAM CBACT04C` to SYSOUT before opening any file and `END OF EXECUTION OF PROGRAM CBACT04C` after all files are closed on a normal run.

- **Category:** sysout
- **Source:** `app/cbl/CBACT04C.cbl:181`, `app/cbl/CBACT04C.cbl:230`
- **Fields:** none
- **Fixtures:** `fixtures/interest/i01-baseline`, `fixtures/interest/i02-tie-half-cent`, `fixtures/interest/i03-zero-rate-skipped`, `fixtures/interest/i04-default-group-fallback`, `fixtures/interest/i05-multi-category`, `fixtures/interest/i06-negative-balance`, `fixtures/interest/i07-nontie-rounding`
- **Test:** *Given* any valid set of input files; *when* INTCALC STEP15 runs to completion; *then* the first SYSOUT line is `START OF EXECUTION OF PROGRAM CBACT04C` and the last is `END OF EXECUTION OF PROGRAM CBACT04C`.

### BR-INT-002

**Run parameter supplies the transaction-id prefix**

The JCL passes a 10-character PARM (`2022071800`) that the program receives as PARM-DATE. It is not validated (PARM-LENGTH is never checked) and its only use is as the first 10 characters of every generated transaction id; it is not used as the posting date or for timestamps.

- **Category:** linkage
- **Source:** `app/jcl/INTCALC.jcl:22`, `app/cbl/CBACT04C.cbl:175-180`, `app/cbl/CBACT04C.cbl:476-480`
- **Fields:** `PARM-LENGTH (CBACT04C LINKAGE)`, `PARM-DATE (CBACT04C LINKAGE)`, `TRAN-ID (CVTRA05Y)`
- **Fixtures:** `fixtures/interest/i01-baseline`, `fixtures/interest/i02-tie-half-cent`, `fixtures/interest/i03-zero-rate-skipped`, `fixtures/interest/i04-default-group-fallback`, `fixtures/interest/i05-multi-category`, `fixtures/interest/i06-negative-balance`, `fixtures/interest/i07-nontie-rounding`
- **Test:** *Given* EXEC PGM=CBACT04C,PARM='2022071800' and a frozen clock of 2022-07-05 10:00:00.00; *when* the first interest transaction is written; *then* TRAN-ID starts with `2022071800` while TRAN-ORIG-TS shows `2022-07-05-10.00.00.000000` (the PARM date is not the timestamp date).

### BR-INT-003

**Files and open modes**

Five files are opened in order: transaction-category balances (TCATBALF) input, card cross-reference (XREFFILE) input, disclosure groups (DISCGRP) input, account master (ACCTFILE) input-output, and the interest transaction file (TRANSACT, a new SYSTRAN GDG generation) output. Any status other than `00` on open stops the run.

- **Category:** data-access
- **Source:** `app/cbl/CBACT04C.cbl:28-56`, `app/cbl/CBACT04C.cbl:182-186`, `app/cbl/CBACT04C.cbl:234-323`, `app/jcl/INTCALC.jcl:27-41`
- **Fields:** `TCATBALF-STATUS`, `XREFFILE-STATUS`, `DISCGRP-STATUS`, `ACCTFILE-STATUS`, `TRANFILE-STATUS (CBACT04C WORKING-STORAGE)`
- **Fixtures:** `fixtures/interest/i01-baseline`, `fixtures/interest/i02-tie-half-cent`, `fixtures/interest/i03-zero-rate-skipped`, `fixtures/interest/i04-default-group-fallback`, `fixtures/interest/i05-multi-category`, `fixtures/interest/i06-negative-balance`, `fixtures/interest/i07-nontie-rounding`
- **Test:** *Given* all five datasets exist and are catalogued; *when* the program starts; *then* all five opens return `00` and processing begins; the new SYSTRAN generation is G0001V00 in a fresh harness run.

### BR-INT-004

**Open failure messages**

If a file fails to open the program displays a file-specific message, the formatted file status (BR-INT-045) and abends (BR-INT-047). Messages: `ERROR OPENING TRANSACTION CATEGORY BALANCE`, `ERROR OPENING CROSS REF FILE` immediately followed by the 2-character status, `ERROR OPENING DALY REJECTS FILE` (for DISCGRP), `ERROR OPENING ACCOUNT MASTER FILE`, `ERROR OPENING TRANSACTION FILE`.

- **Category:** sysout
- **Source:** `app/cbl/CBACT04C.cbl:234-250`, `app/cbl/CBACT04C.cbl:252-268`, `app/cbl/CBACT04C.cbl:270-286`, `app/cbl/CBACT04C.cbl:289-305`, `app/cbl/CBACT04C.cbl:307-323`
- **Fields:** `TCATBALF-STATUS`, `XREFFILE-STATUS`, `DISCGRP-STATUS`, `ACCTFILE-STATUS`, `TRANFILE-STATUS`, `APPL-RESULT (CBACT04C WORKING-STORAGE)`
- **Fixtures:** none (source analysis only)
- **Test:** *Given* the DISCGRP dataset is missing so OPEN returns status `35`; *when* the program opens DISCGRP; *then* SYSOUT shows `ERROR OPENING DALY REJECTS FILE`, `FILE STATUS IS: NNNN0035`, `ABENDING PROGRAM` and the step abends (no interest is posted).

### BR-INT-005

**Balances are read sequentially in key order**

Transaction-category balance records are read one at a time in ascending key order: account id, then transaction type code, then category code. Each read is moved into TRAN-CAT-BAL-RECORD.

- **Category:** data-access
- **Source:** `app/cbl/CBACT04C.cbl:28-32`, `app/cbl/CBACT04C.cbl:61-67`, `app/cbl/CBACT04C.cbl:188-191`, `app/cbl/CBACT04C.cbl:325-335`
- **Fields:** `TRANCAT-ACCT-ID`, `TRANCAT-TYPE-CD`, `TRANCAT-CD`, `TRAN-CAT-BAL (CVTRA01Y)`
- **Fixtures:** `fixtures/interest/i05-multi-category`
- **Test:** *Given* TCATBALF rows for account 2007 with keys (01,0001) and (01,0002); *when* the file is processed; *then* (01,0001) is processed before (01,0002) and interest transactions 000001 and 000002 are written in that order.

### BR-INT-006

**End of balance file**

File status `10` on the balance file is end of file: the END-OF-FILE switch is set to `Y` and the main loop stops.

- **Category:** control-flow
- **Source:** `app/cbl/CBACT04C.cbl:137`, `app/cbl/CBACT04C.cbl:188-191`, `app/cbl/CBACT04C.cbl:330-340`
- **Fields:** `END-OF-FILE`, `APPL-RESULT (CBACT04C WORKING-STORAGE)`, `TCATBALF-STATUS`
- **Fixtures:** `fixtures/interest/i01-baseline`, `fixtures/interest/i02-tie-half-cent`, `fixtures/interest/i03-zero-rate-skipped`, `fixtures/interest/i04-default-group-fallback`, `fixtures/interest/i05-multi-category`, `fixtures/interest/i06-negative-balance`, `fixtures/interest/i07-nontie-rounding`
- **Test:** *Given* TCATBALF with two records; *when* the third READ returns status `10`; *then* END-OF-FILE becomes `Y`, no third record is processed and the program proceeds to close the files.

### BR-INT-007

**Balance file read error**

Any balance-file read status other than `00` or `10` displays `ERROR READING TRANSACTION CATEGORY FILE`, the formatted file status, and abends.

- **Category:** return-code
- **Source:** `app/cbl/CBACT04C.cbl:325-348`
- **Fields:** `TCATBALF-STATUS`, `IO-STATUS (CBACT04C WORKING-STORAGE)`
- **Fixtures:** none (source analysis only)
- **Test:** *Given* the TCATBALF read returns status `30` (permanent I/O error); *when* the next balance record is read; *then* SYSOUT shows `ERROR READING TRANSACTION CATEGORY FILE`, `FILE STATUS IS: NNNN0030`, `ABENDING PROGRAM` and the step abends.

### BR-INT-008

**Each balance record is echoed to SYSOUT**

Every balance record successfully read is counted and displayed on SYSOUT as its raw 50-byte image (key, zoned signed balance with overpunch sign, and the 22-byte blank filler).

- **Category:** sysout
- **Source:** `app/cbl/CBACT04C.cbl:191-193`
- **Fields:** `TRAN-CAT-BAL-RECORD (CVTRA01Y)`, `WS-RECORD-COUNT (CBACT04C WORKING-STORAGE)`
- **Fixtures:** `fixtures/interest/i01-baseline`, `fixtures/interest/i02-tie-half-cent`, `fixtures/interest/i03-zero-rate-skipped`, `fixtures/interest/i04-default-group-fallback`, `fixtures/interest/i05-multi-category`, `fixtures/interest/i06-negative-balance`, `fixtures/interest/i07-nontie-rounding`
- **Test:** *Given* balance record account 2001, type 01, category 0001, balance 1000.00; *when* the record is read; *then* SYSOUT shows `000000020010100010000010000{` followed by 22 spaces.

### BR-INT-009

**Account break detection**

When the account id on the balance record differs from the previous one, an account break occurs: the previous account is updated (except on the very first record), the interest accumulator is reset, and the new account and its card cross-reference are read. Records for the same account do not re-read the account.

- **Category:** control-flow
- **Source:** `app/cbl/CBACT04C.cbl:194-206`
- **Fields:** `TRANCAT-ACCT-ID (CVTRA01Y)`, `WS-LAST-ACCT-NUM`, `WS-FIRST-TIME (CBACT04C WORKING-STORAGE)`
- **Fixtures:** `fixtures/interest/i01-baseline`
- **Test:** *Given* balance records for accounts 2001 then 2002; *when* the record for 2002 is read; *then* account 2001 is rewritten with its interest, then account 2002 and its card are read.

### BR-INT-010

**First record does not trigger an update**

On the first balance record WS-FIRST-TIME is `Y`; it is switched to `N` and no account update is performed because there is no previous account.

- **Category:** control-flow
- **Source:** `app/cbl/CBACT04C.cbl:170`, `app/cbl/CBACT04C.cbl:195-199`
- **Fields:** `WS-FIRST-TIME (CBACT04C WORKING-STORAGE)`
- **Fixtures:** `fixtures/interest/i01-baseline`, `fixtures/interest/i02-tie-half-cent`, `fixtures/interest/i03-zero-rate-skipped`, `fixtures/interest/i04-default-group-fallback`, `fixtures/interest/i05-multi-category`, `fixtures/interest/i06-negative-balance`, `fixtures/interest/i07-nontie-rounding`
- **Test:** *Given* a fresh run where the first balance record is for account 2001; *when* the first record is processed; *then* no REWRITE is issued before account 2001 is read; WS-FIRST-TIME becomes `N`.

### BR-INT-011

**Interest accumulator reset per account**

The account's running interest total is set to zero at every account break, before the new account's categories are processed.

- **Category:** state-change
- **Source:** `app/cbl/CBACT04C.cbl:169`, `app/cbl/CBACT04C.cbl:200`
- **Fields:** `WS-TOTAL-INT (CBACT04C WORKING-STORAGE)`
- **Fixtures:** `fixtures/interest/i01-baseline`
- **Test:** *Given* account 2001 accrued 10.00 and the next record is for account 2002; *when* the account break is processed; *then* WS-TOTAL-INT is 0.00 before account 2002's interest is added, so 2002 does not inherit 2001's interest.

### BR-INT-012

**Account lookup by balance account id**

At each account break the account master is read by random key equal to the balance record's account id; the record is kept in ACCOUNT-RECORD until the next break.

- **Category:** data-access
- **Source:** `app/cbl/CBACT04C.cbl:41-45`, `app/cbl/CBACT04C.cbl:201-203`, `app/cbl/CBACT04C.cbl:372-383`
- **Fields:** `TRANCAT-ACCT-ID (CVTRA01Y)`, `ACCT-ID`, `ACCT-GROUP-ID`, `ACCT-CURR-BAL (CVACT01Y)`
- **Fixtures:** `fixtures/interest/i04-default-group-fallback`
- **Test:** *Given* TCATBALF row for account 2006 and an ACCTDATA row 2006 in group PLATINUM; *when* the account break for 2006 is processed; *then* ACCOUNT-RECORD holds account 2006 with ACCT-GROUP-ID `PLATINUM`.

### BR-INT-013

**Missing account abends**

If the balance record's account id is not on the account master the program displays `ACCOUNT NOT FOUND: ` followed by the 11-digit id, then `ERROR READING ACCOUNT FILE`, the formatted status, and abends.

- **Category:** edge-case
- **Source:** `app/cbl/CBACT04C.cbl:373-390`
- **Fields:** `FD-ACCT-ID (CBACT04C FD)`, `ACCTFILE-STATUS (CBACT04C WORKING-STORAGE)`
- **Fixtures:** none (source analysis only)
- **Test:** *Given* a TCATBALF row for account 2099 with no ACCTDATA row 2099; *when* the account break for 2099 is processed; *then* SYSOUT shows `ACCOUNT NOT FOUND: 00000002099`, `ERROR READING ACCOUNT FILE`, `FILE STATUS IS: NNNN0023`, `ABENDING PROGRAM`.

### BR-INT-014

**Card lookup by account id (alternate key)**

At each account break the card cross-reference is read by its alternate key (account id) through the AIX path to obtain the card number used on the interest transactions.

- **Category:** data-access
- **Source:** `app/cbl/CBACT04C.cbl:34-39`, `app/cbl/CBACT04C.cbl:204-205`, `app/cbl/CBACT04C.cbl:393-395`, `app/jcl/INTCALC.jcl:29-32`
- **Fields:** `XREF-ACCT-ID`, `XREF-CARD-NUM (CVACT03Y)`
- **Fixtures:** `fixtures/interest/i01-baseline`
- **Test:** *Given* CARDXREF row card 4000000000002001 for account 2001; *when* the account break for 2001 is processed; *then* the interest transaction for 2001 carries TRAN-CARD-NUM `4000000000002001`.

### BR-INT-015

**Missing card cross-reference abends**

If no cross-reference exists for the account the program displays `ACCOUNT NOT FOUND: ` followed by the account id (the same text as the account-file message), then `ERROR READING XREF FILE`, the formatted status, and abends.

- **Category:** edge-case
- **Source:** `app/cbl/CBACT04C.cbl:394-412`
- **Fields:** `FD-XREF-ACCT-ID (CBACT04C FD)`, `XREFFILE-STATUS (CBACT04C WORKING-STORAGE)`
- **Fixtures:** none (source analysis only)
- **Test:** *Given* account 2011 exists on ACCTDATA and TCATBALF but has no CARDXREF row; *when* the account break for 2011 is processed; *then* SYSOUT shows `ACCOUNT NOT FOUND: 00000002011`, `ERROR READING XREF FILE`, `FILE STATUS IS: NNNN0023`, `ABENDING PROGRAM`.

### BR-INT-016

**Accounts without balance rows are untouched**

Only accounts that appear on TCATBALF are read or rewritten; any other account master record keeps every field unchanged, including its cycle credit and debit.

- **Category:** data-access
- **Source:** `app/cbl/CBACT04C.cbl:194-206`, `app/cbl/CBACT04C.cbl:350-356`
- **Fields:** `ACCT-CURR-BAL`, `ACCT-CURR-CYC-CREDIT`, `ACCT-CURR-CYC-DEBIT (CVACT01Y)`
- **Fixtures:** none (source analysis only)
- **Test:** *Given* ACCTDATA has account 2050 with cycle credit 100.00 and no TCATBALF rows; *when* the run completes; *then* account 2050 still has cycle credit 100.00 and its original balance.

### BR-INT-017

**Disclosure-group key**

The interest rate is looked up in the disclosure-group file using the account's group id, the balance record's transaction type code and the balance record's category code.

- **Category:** data-access
- **Source:** `app/cbl/CBACT04C.cbl:47-51`, `app/cbl/CBACT04C.cbl:210-213`, `app/cbl/CBACT04C.cbl:415-416`
- **Fields:** `ACCT-GROUP-ID (CVACT01Y)`, `TRANCAT-TYPE-CD`, `TRANCAT-CD (CVTRA01Y)`, `DIS-ACCT-GROUP-ID`, `DIS-TRAN-TYPE-CD`, `DIS-TRAN-CAT-CD`, `DIS-INT-RATE (CVTRA02Y)`
- **Fixtures:** `fixtures/interest/i01-baseline`, `fixtures/interest/i07-nontie-rounding`
- **Test:** *Given* account 2002 in group STANDARD with a type 01 / category 0001 balance; DISCGRP STANDARD/01/0001 = 18.50; *when* the rate is looked up; *then* DIS-INT-RATE is 18.50 and interest 2400.00 * 18.50 / 1200 = 37.00 is posted.

### BR-INT-018

**Fallback to the DEFAULT disclosure group**

If no disclosure-group row exists for the account's group (status `23`), the program displays `DISCLOSURE GROUP RECORD MISSING` and `TRY WITH DEFAULT GROUP CODE`, replaces only the group id with `DEFAULT` (type and category are kept) and reads again.

- **Category:** validation
- **Source:** `app/cbl/CBACT04C.cbl:416-420`, `app/cbl/CBACT04C.cbl:422`, `app/cbl/CBACT04C.cbl:436-439`, `app/cbl/CBACT04C.cbl:443-444`
- **Fields:** `FD-DIS-ACCT-GROUP-ID (CBACT04C FD)`, `DIS-INT-RATE (CVTRA02Y)`, `ACCT-GROUP-ID (CVACT01Y)`
- **Fixtures:** `fixtures/interest/i04-default-group-fallback`
- **Test:** *Given* account 2006 in group PLATINUM (no DISCGRP rows) with a type 01 / category 0001 balance of 1000.00; DEFAULT/01/0001 = 21.00; *when* the rate is looked up; *then* SYSOUT shows the two fallback lines, the DEFAULT rate 21.00 is used and a 17.50 interest transaction is written.

### BR-INT-019

**Accounts in the DEFAULT group use it directly**

An account whose group id is already `DEFAULT` finds its row on the first read, so no fallback messages are shown.

- **Category:** validation
- **Source:** `app/cbl/CBACT04C.cbl:416-422`, `app/cbl/CBACT04C.cbl:436`
- **Fields:** `ACCT-GROUP-ID (CVACT01Y)`, `DIS-ACCT-GROUP-ID (CVTRA02Y)`
- **Fixtures:** `fixtures/interest/i07-nontie-rounding`
- **Test:** *Given* account 2010 in group DEFAULT with a 777.77 type 01 / category 0001 balance; DEFAULT/01/0001 = 21.00; *when* the rate is looked up; *then* 13.61 is posted and SYSOUT has no `DISCLOSURE GROUP RECORD MISSING` line.

### BR-INT-020

**Disclosure-group read error**

A disclosure-group read status other than `00` or `23` displays `ERROR READING DISCLOSURE GROUP FILE`, the formatted status, and abends.

- **Category:** return-code
- **Source:** `app/cbl/CBACT04C.cbl:422-435`
- **Fields:** `DISCGRP-STATUS (CBACT04C WORKING-STORAGE)`
- **Fixtures:** none (source analysis only)
- **Test:** *Given* the DISCGRP read returns status `30`; *when* a rate is looked up; *then* SYSOUT shows `ERROR READING DISCLOSURE GROUP FILE`, `FILE STATUS IS: NNNN0030`, `ABENDING PROGRAM`.

### BR-INT-021

**Missing DEFAULT group row abends**

If the DEFAULT group has no row for the type/category either, the program displays `ERROR READING DEFAULT DISCLOSURE GROUP`, the formatted status, and abends; there is no further fallback.

- **Category:** edge-case
- **Source:** `app/cbl/CBACT04C.cbl:443-460`
- **Fields:** `FD-DISCGRP-KEY (CBACT04C FD)`, `DISCGRP-STATUS (CBACT04C WORKING-STORAGE)`
- **Fixtures:** none (source analysis only)
- **Test:** *Given* account in group PLATINUM with a type 04 / category 0001 balance and no PLATINUM or DEFAULT row for 04/0001; *when* the rate is looked up; *then* SYSOUT shows `DISCLOSURE GROUP RECORD MISSING`, `TRY WITH DEFAULT GROUP CODE`, `ERROR READING DEFAULT DISCLOSURE GROUP`, `FILE STATUS IS: NNNN0023`, `ABENDING PROGRAM`.

### BR-INT-022

**Zero-rate categories are skipped**

When the resolved interest rate is zero, no interest is computed and no transaction is written for that balance record; the account is still processed for its other categories.

- **Category:** validation
- **Source:** `app/cbl/CBACT04C.cbl:214-217`
- **Fields:** `DIS-INT-RATE (CVTRA02Y)`, `TRAN-CAT-BAL (CVTRA01Y)`
- **Fixtures:** `fixtures/interest/i03-zero-rate-skipped`
- **Test:** *Given* account 2005 (GOLD) with a type 01 balance 1000.00 at 12.00 % and a type 02 balance -300.00 at 0.00 %; *when* the account is processed; *then* exactly one transaction (10.00, TRAN-ID 2022071800000001) is written; nothing is written for type 02.

### BR-INT-023

**Monthly interest formula**

Monthly interest for a balance record = category balance x annual rate (percent) / 1200.

- **Category:** calculation
- **Source:** `app/cbl/CBACT04C.cbl:462-465`
- **Fields:** `TRAN-CAT-BAL (CVTRA01Y)`, `DIS-INT-RATE (CVTRA02Y)`, `WS-MONTHLY-INT (CBACT04C WORKING-STORAGE)`
- **Fixtures:** `fixtures/interest/i01-baseline`, `fixtures/interest/i05-multi-category`
- **Test:** *Given* balance 1000.00 and rate 12.00; *when* interest is computed; *then* WS-MONTHLY-INT = 10.00.

### BR-INT-024

**Interest is truncated, not rounded**

The COMPUTE has no ROUNDED phrase, so the result is truncated to two decimals (fractions of a cent are dropped). An exact half cent is therefore cut down, where a half-even or half-up rule would round it up.

- **Category:** rounding
- **Source:** `app/cbl/CBACT04C.cbl:168`, `app/cbl/CBACT04C.cbl:464-465`
- **Fields:** `WS-MONTHLY-INT (CBACT04C WORKING-STORAGE)`, `TRAN-AMT (CVTRA05Y)`, `ACCT-CURR-BAL (CVACT01Y)`
- **Fixtures:** `fixtures/interest/i02-tie-half-cent`, `fixtures/interest/i07-nontie-rounding`
- **Test:** *Given* account 2003 (GOLD) with balance 1235.50 at 12.00 % (exact value 12.355); *when* interest is computed; *then* TRAN-AMT = 12.35 (half-even would give 12.36) and the rewritten ACCT-CURR-BAL = 1235.50 + 12.35 = 1247.85.

### BR-INT-025

**Non-tie sub-cent remainders**

Any fraction of a cent is dropped, whether it is below or above half a cent (12.357 also becomes 12.35, where half-even would give 12.36). When the dropped part is below half a cent, truncation and half-even agree.

- **Category:** rounding
- **Source:** `app/cbl/CBACT04C.cbl:464-465`
- **Fields:** `WS-MONTHLY-INT (CBACT04C WORKING-STORAGE)`, `TRAN-AMT (CVTRA05Y)`
- **Fixtures:** `fixtures/interest/i07-nontie-rounding`
- **Test:** *Given* balance 1000.33 at 18.50 % (15.42175) and balance 777.77 at 21.00 % (13.610975); *when* interest is computed; *then* TRAN-AMT = 15.42 and 13.61.

### BR-INT-026

**Negative balances produce negative interest**

A credit (negative) category balance is not excluded: it produces a negative interest amount, which is written as a negative transaction and (if the account is updated) reduces the balance. Truncation is toward zero.

- **Category:** calculation
- **Source:** `app/cbl/CBACT04C.cbl:464-467`, `app/cbl/CBACT04C.cbl:490`
- **Fields:** `TRAN-CAT-BAL (CVTRA01Y)`, `WS-MONTHLY-INT (CBACT04C WORKING-STORAGE)`, `TRAN-AMT (CVTRA05Y)`
- **Fixtures:** `fixtures/interest/i06-negative-balance`
- **Test:** *Given* account 2008 with a balance of -300.00 at 12.00 %; *when* interest is computed; *then* a transaction with TRAN-AMT -3.00 is written (zoned `0000000030}`); for -1235.50 at 12.00 % the amount would be -12.35, not -12.36.

### BR-INT-027

**Interest accumulated per account**

Each category's monthly interest is added to the account's running total, which is applied to the account balance at the next account break.

- **Category:** calculation
- **Source:** `app/cbl/CBACT04C.cbl:467`
- **Fields:** `WS-MONTHLY-INT`, `WS-TOTAL-INT (CBACT04C WORKING-STORAGE)`
- **Fixtures:** `fixtures/interest/i05-multi-category`
- **Test:** *Given* account 2007 with category interest 10.00 and 7.50 followed by a record for another account; *when* the account break occurs; *then* WS-TOTAL-INT = 17.50 is added to account 2007's balance.

### BR-INT-028

**Zero balance with a non-zero rate still posts**

Only the rate is tested; a zero category balance with a non-zero rate produces a 0.00 interest transaction.

- **Category:** edge-case
- **Source:** `app/cbl/CBACT04C.cbl:214-216`, `app/cbl/CBACT04C.cbl:464-468`
- **Fields:** `TRAN-CAT-BAL (CVTRA01Y)`, `DIS-INT-RATE (CVTRA02Y)`, `TRAN-AMT (CVTRA05Y)`
- **Fixtures:** none (source analysis only)
- **Test:** *Given* a balance record of 0.00 in GOLD/01/0001 (12.00 %); *when* the record is processed; *then* a transaction with TRAN-AMT 0.00 and the next TRAN-ID suffix is written.

### BR-INT-029

**Silent overflow on large amounts**

Interest and balance arithmetic has no ON SIZE ERROR clause; a result that does not fit the receiving field (9 integer digits for interest, 10 for the account balance) loses its high-order digits without any message.

- **Category:** edge-case
- **Source:** `app/cbl/CBACT04C.cbl:168-169`, `app/cbl/CBACT04C.cbl:352`, `app/cbl/CBACT04C.cbl:464-467`
- **Fields:** `WS-MONTHLY-INT`, `WS-TOTAL-INT (CBACT04C WORKING-STORAGE)`, `ACCT-CURR-BAL (CVACT01Y)`
- **Fixtures:** none (source analysis only)
- **Test:** *Given* a balance of 999,999,999.99 at 9,999.99 % (about 8.3 billion of interest); *when* interest is computed; *then* WS-MONTHLY-INT keeps only the low-order 9 integer digits and the run continues with RC 0.

### BR-INT-030

**Fees calculation is a stub**

A fee step is performed after each interest calculation but contains no logic, so no fees are charged and no fee transactions are written.

- **Category:** calculation
- **Source:** `app/cbl/CBACT04C.cbl:216`, `app/cbl/CBACT04C.cbl:518-520`
- **Fields:** none
- **Fixtures:** `fixtures/interest/i01-baseline`, `fixtures/interest/i02-tie-half-cent`, `fixtures/interest/i03-zero-rate-skipped`, `fixtures/interest/i04-default-group-fallback`, `fixtures/interest/i05-multi-category`, `fixtures/interest/i06-negative-balance`, `fixtures/interest/i07-nontie-rounding`
- **Test:** *Given* any balance record with a non-zero rate; *when* 1400-COMPUTE-FEES is performed; *then* nothing changes; only the interest transaction is written.

### BR-INT-031

**One interest transaction per non-zero-rate balance**

A separate transaction is written for every balance record with a non-zero rate, so an account with several interest-bearing categories gets several transactions.

- **Category:** state-change
- **Source:** `app/cbl/CBACT04C.cbl:215`, `app/cbl/CBACT04C.cbl:468`, `app/cbl/CBACT04C.cbl:473`, `app/cbl/CBACT04C.cbl:500`
- **Fields:** `TRAN-RECORD (CVTRA05Y)`
- **Fixtures:** `fixtures/interest/i05-multi-category`
- **Test:** *Given* account 2007 with purchases 1000.00 at 12.00 % and cash advances 500.00 at 18.00 %; *when* the account is processed; *then* two transactions are written: 10.00 and 7.50.

### BR-INT-032

**Transaction id format**

TRAN-ID is the 10-character PARM-DATE followed by a 6-digit sequence number that starts at 000001 and increases by one for every interest transaction in the run (it does not restart per account).

- **Category:** state-change
- **Source:** `app/cbl/CBACT04C.cbl:173`, `app/cbl/CBACT04C.cbl:474-480`
- **Fields:** `PARM-DATE (CBACT04C LINKAGE)`, `WS-TRANID-SUFFIX (CBACT04C WORKING-STORAGE)`, `TRAN-ID (CVTRA05Y)`
- **Fixtures:** `fixtures/interest/i01-baseline`, `fixtures/interest/i05-multi-category`
- **Test:** *Given* PARM `2022071800` and two accounts each with one interest-bearing balance; *when* both transactions are written; *then* TRAN-IDs are `2022071800000001` and `2022071800000002`.

### BR-INT-033

**Fixed classification of interest transactions**

Every interest transaction has type code `01`, category code 0005 and source `System` regardless of the balance category it was computed from.

- **Category:** state-change
- **Source:** `app/cbl/CBACT04C.cbl:482-484`
- **Fields:** `TRAN-TYPE-CD`, `TRAN-CAT-CD`, `TRAN-SOURCE (CVTRA05Y)`
- **Fixtures:** `fixtures/interest/i05-multi-category`
- **Test:** *Given* a cash-advance balance (type 01, category 0002); *when* its interest transaction is written; *then* TRAN-TYPE-CD = `01`, TRAN-CAT-CD = 0005 and TRAN-SOURCE = `System` (padded to 10).

### BR-INT-034

**Transaction description**

TRAN-DESC is the text `Int. for a/c ` followed by the 11-digit account id from the account master, padded with spaces to 100 characters.

- **Category:** state-change
- **Source:** `app/cbl/CBACT04C.cbl:485-489`
- **Fields:** `ACCT-ID (CVACT01Y)`, `TRAN-DESC (CVTRA05Y)`
- **Fixtures:** `fixtures/interest/i01-baseline`, `fixtures/interest/i02-tie-half-cent`, `fixtures/interest/i03-zero-rate-skipped`, `fixtures/interest/i04-default-group-fallback`, `fixtures/interest/i05-multi-category`, `fixtures/interest/i06-negative-balance`, `fixtures/interest/i07-nontie-rounding`
- **Test:** *Given* account 2001; *when* its interest transaction is written; *then* TRAN-DESC = `Int. for a/c 00000002001`.

### BR-INT-035

**Amount, merchant and card fields**

TRAN-AMT is the truncated monthly interest; merchant id is 0, merchant name, city and zip are blank, and the card number is the one found on the cross-reference for the account.

- **Category:** state-change
- **Source:** `app/cbl/CBACT04C.cbl:490-495`
- **Fields:** `WS-MONTHLY-INT (CBACT04C WORKING-STORAGE)`, `TRAN-AMT`, `TRAN-MERCHANT-ID`, `TRAN-MERCHANT-NAME`, `TRAN-MERCHANT-CITY`, `TRAN-MERCHANT-ZIP`, `TRAN-CARD-NUM (CVTRA05Y)`, `XREF-CARD-NUM (CVACT03Y)`
- **Fixtures:** `fixtures/interest/i04-default-group-fallback`
- **Test:** *Given* account 2006 with card 4000000000002006 and 17.50 of interest; *when* its interest transaction is written; *then* TRAN-AMT 17.50, TRAN-MERCHANT-ID 000000000, merchant text fields blank, TRAN-CARD-NUM `4000000000002006`.

### BR-INT-036

**Transaction timestamps**

Origin and processing timestamps are both set from the current system date-time in DB2 format `YYYY-MM-DD-HH.MM.SS.hh0000` (hundredths of a second followed by `0000`).

- **Category:** state-change
- **Source:** `app/cbl/CBACT04C.cbl:140-165`, `app/cbl/CBACT04C.cbl:496-498`, `app/cbl/CBACT04C.cbl:613-626`
- **Fields:** `COBOL-TS`, `DB2-FORMAT-TS (CBACT04C WORKING-STORAGE)`, `TRAN-ORIG-TS`, `TRAN-PROC-TS (CVTRA05Y)`
- **Fixtures:** `fixtures/interest/i01-baseline`, `fixtures/interest/i02-tie-half-cent`, `fixtures/interest/i03-zero-rate-skipped`, `fixtures/interest/i04-default-group-fallback`, `fixtures/interest/i05-multi-category`, `fixtures/interest/i06-negative-balance`, `fixtures/interest/i07-nontie-rounding`
- **Test:** *Given* the harness clock frozen at 2022-07-05 10:00:00.00; *when* an interest transaction is written; *then* TRAN-ORIG-TS = TRAN-PROC-TS = `2022-07-05-10.00.00.000000`.

### BR-INT-037

**Transaction write error**

If the interest transaction cannot be written the program displays `ERROR WRITING TRANSACTION RECORD`, the formatted status, and abends.

- **Category:** return-code
- **Source:** `app/cbl/CBACT04C.cbl:500-515`
- **Fields:** `TRANFILE-STATUS (CBACT04C WORKING-STORAGE)`
- **Fixtures:** none (source analysis only)
- **Test:** *Given* the SYSTRAN generation runs out of space (status `34`); *when* an interest transaction is written; *then* SYSOUT shows `ERROR WRITING TRANSACTION RECORD`, `FILE STATUS IS: NNNN0034`, `ABENDING PROGRAM`.

### BR-INT-038

**Zoned signed output encoding**

Amounts in this slice are zoned decimal (no COMP-3); the sign is overpunched on the last digit using the EBCDIC convention (`{`..`I` positive, `}`..`R` negative). Output fields must be byte-identical including that sign.

- **Category:** edge-case
- **Source:** `app/cbl/CBACT04C.cbl:490`, `app/cbl/CBACT04C.cbl:352`
- **Fields:** `TRAN-AMT (CVTRA05Y)`, `ACCT-CURR-BAL (CVACT01Y)`, `TRAN-CAT-BAL (CVTRA01Y)`, `DIS-INT-RATE (CVTRA02Y)`
- **Fixtures:** `fixtures/interest/i02-tie-half-cent`, `fixtures/interest/i06-negative-balance`
- **Test:** *Given* interest of 12.35, 12.34 and -3.00; *when* the transactions are written; *then* TRAN-AMT is encoded `0000000123E`, `0000000123D` and `0000000030}`.

### BR-INT-039

**Account balance update and cycle reset**

At an account break the previous account's total interest is added to its current balance, its current-cycle credit and debit are set to zero, and the account record is rewritten. No other account field changes.

- **Category:** state-change
- **Source:** `app/cbl/CBACT04C.cbl:350-356`
- **Fields:** `WS-TOTAL-INT (CBACT04C WORKING-STORAGE)`, `ACCT-CURR-BAL`, `ACCT-CURR-CYC-CREDIT`, `ACCT-CURR-CYC-DEBIT (CVACT01Y)`
- **Fixtures:** `fixtures/interest/i01-baseline`, `fixtures/interest/i02-tie-half-cent`, `fixtures/interest/i07-nontie-rounding`
- **Test:** *Given* account 2001 with balance 1000.00, cycle credit 1000.00, 10.00 interest, followed by account 2002; *when* the break to 2002 is processed; *then* account 2001 is rewritten with ACCT-CURR-BAL 1010.00 and cycle credit and debit 0.00.

### BR-INT-040

**Accounts with only zero-rate categories are still reset**

An account whose categories all have zero rates is still rewritten at the next account break: the balance is unchanged (plus 0.00) but cycle credit and debit are zeroed.

- **Category:** edge-case
- **Source:** `app/cbl/CBACT04C.cbl:194-200`, `app/cbl/CBACT04C.cbl:214`, `app/cbl/CBACT04C.cbl:350-356`
- **Fields:** `WS-TOTAL-INT (CBACT04C WORKING-STORAGE)`, `ACCT-CURR-CYC-CREDIT`, `ACCT-CURR-CYC-DEBIT (CVACT01Y)`
- **Fixtures:** none (source analysis only)
- **Test:** *Given* account 2005 with only a type 02 balance (0.00 % rate), cycle debit 300.00, followed by account 2006; *when* the break to 2006 is processed; *then* account 2005 keeps its balance, cycle debit becomes 0.00, and no transaction is written for it.

### BR-INT-041

**Account rewrite error**

If the account rewrite fails the program displays `ERROR RE-WRITING ACCOUNT FILE`, the formatted status, and abends.

- **Category:** return-code
- **Source:** `app/cbl/CBACT04C.cbl:356-370`
- **Fields:** `ACCTFILE-STATUS (CBACT04C WORKING-STORAGE)`
- **Fixtures:** none (source analysis only)
- **Test:** *Given* the account rewrite returns status `22` or another non-`00` value; *when* an account break is processed; *then* SYSOUT shows `ERROR RE-WRITING ACCOUNT FILE`, `FILE STATUS IS: NNNN00xx`, `ABENDING PROGRAM`.

### BR-INT-042

**Last account on the balance file is never updated**

The update of an account happens only at the next account break. When the end of the balance file is reached the loop condition stops the loop before the ELSE branch that would update the final account can run, so the last account keeps its old balance and cycle amounts, although its interest transactions are written.

- **Category:** edge-case
- **Source:** `app/cbl/CBACT04C.cbl:188-222`
- **Fields:** `END-OF-FILE (CBACT04C WORKING-STORAGE)`, `ACCT-CURR-BAL`, `ACCT-CURR-CYC-CREDIT`, `ACCT-CURR-CYC-DEBIT (CVACT01Y)`
- **Fixtures:** `fixtures/interest/i01-baseline`, `fixtures/interest/i02-tie-half-cent`, `fixtures/interest/i03-zero-rate-skipped`, `fixtures/interest/i04-default-group-fallback`, `fixtures/interest/i05-multi-category`, `fixtures/interest/i06-negative-balance`, `fixtures/interest/i07-nontie-rounding`
- **Test:** *Given* TCATBALF has rows only for accounts 2001 (10.00 interest) and 2002 (37.00 interest); *when* the run completes; *then* account 2001 becomes 1010.00 with cycle amounts zeroed; account 2002 stays at 2400.00 with cycle credit 2400.00, yet both interest transactions are on SYSTRAN.

### BR-INT-043

**Empty balance file**

If TCATBALF has no records, the first read hits end of file: no account is read or rewritten, the SYSTRAN generation is created empty, SYSOUT contains only the start and end banners, and the step ends with RC 0.

- **Category:** edge-case
- **Source:** `app/cbl/CBACT04C.cbl:188-191`, `app/cbl/CBACT04C.cbl:325-340`, `app/cbl/CBACT04C.cbl:224-232`
- **Fields:** `END-OF-FILE (CBACT04C WORKING-STORAGE)`
- **Fixtures:** none (source analysis only)
- **Test:** *Given* an empty TCATBALF and any account master; *when* the run completes; *then* 0 interest transactions, account master unchanged, RC 0.

### BR-INT-044

**Close files and close errors**

On normal completion all five files are closed. A close failure displays `ERROR CLOSING TRANSACTION BALANCE FILE`, `ERROR CLOSING CROSS REF FILE`, `ERROR CLOSING DISCLOSURE GROUP FILE`, `ERROR CLOSING ACCOUNT FILE` or `ERROR CLOSING TRANSACTION FILE`, the formatted status, and abends.

- **Category:** return-code
- **Source:** `app/cbl/CBACT04C.cbl:224-228`, `app/cbl/CBACT04C.cbl:522-611`
- **Fields:** `TCATBALF-STATUS`, `XREFFILE-STATUS`, `DISCGRP-STATUS`, `ACCTFILE-STATUS`, `TRANFILE-STATUS (CBACT04C WORKING-STORAGE)`
- **Fixtures:** none (source analysis only)
- **Test:** *Given* the TRANSACT close returns a non-`00` status; *when* the files are closed; *then* SYSOUT shows `ERROR CLOSING TRANSACTION FILE`, the status line and `ABENDING PROGRAM`.

### BR-INT-045

**File status display format**

Error paths print `FILE STATUS IS: NNNN` followed by a 4-character status: for a numeric status not starting with 9 it is `00` plus the 2-digit status; for a status starting with `9` or non-numeric it is the first status character followed by the binary value of the second byte as 3 digits.

- **Category:** sysout
- **Source:** `app/cbl/CBACT04C.cbl:122-131`, `app/cbl/CBACT04C.cbl:635-648`
- **Fields:** `IO-STATUS`, `IO-STATUS-04`, `TWO-BYTES-BINARY (CBACT04C WORKING-STORAGE)`
- **Fixtures:** none (source analysis only)
- **Test:** *Given* a file status of `23`; *when* 9910-DISPLAY-IO-STATUS runs; *then* SYSOUT shows `FILE STATUS IS: NNNN0023`.

### BR-INT-046

**Normal completion return code**

On a normal run the program returns with GOBACK without setting RETURN-CODE, so the step ends with RC 0.

- **Category:** return-code
- **Source:** `app/cbl/CBACT04C.cbl:232`
- **Fields:** none
- **Fixtures:** `fixtures/interest/i01-baseline`, `fixtures/interest/i02-tie-half-cent`, `fixtures/interest/i03-zero-rate-skipped`, `fixtures/interest/i04-default-group-fallback`, `fixtures/interest/i05-multi-category`, `fixtures/interest/i06-negative-balance`, `fixtures/interest/i07-nontie-rounding`
- **Test:** *Given* any valid fixture input; *when* INTCALC runs; *then* rc.json records maxrc 0 for INTCALC.

### BR-INT-047

**Abend routine**

Every unrecoverable error ends in 9999-ABEND-PROGRAM, which displays `ABENDING PROGRAM` and calls the Language Environment service CEE3ABD with abend code 999 and timing 0 (a U0999 abend on z/OS). Off-host the harness stand-in prints its own abend line and ends the step with RC 12.

- **Category:** return-code
- **Source:** `app/cbl/CBACT04C.cbl:628-632`
- **Fields:** `ABCODE`, `TIMING (CBACT04C WORKING-STORAGE)`
- **Fixtures:** none (source analysis only)
- **Test:** *Given* any error path (for example a missing account); *when* 9999-ABEND-PROGRAM runs; *then* SYSOUT shows `ABENDING PROGRAM` and no `END OF EXECUTION` banner; z/OS: user abend U0999; harness: step RC 12.

### BR-INT-048

**No rollback on abend**

Accounts rewritten before an abend stay updated, because each rewrite is committed individually. On z/OS the SYSTRAN generation is allocated DISP=(NEW,CATLG,DELETE), so on abend the interest transactions written so far are discarded while the account updates remain.

- **Category:** edge-case
- **Source:** `app/cbl/CBACT04C.cbl:350-356`, `app/cbl/CBACT04C.cbl:628-632`, `app/jcl/INTCALC.jcl:37-41`
- **Fields:** `ACCT-CURR-BAL (CVACT01Y)`, `TRAN-RECORD (CVTRA05Y)`
- **Fixtures:** none (source analysis only)
- **Test:** *Given* accounts 2001, 2002, 2099 on TCATBALF where 2099 is missing from the account master; *when* the run abends at account 2099; *then* accounts 2001 (at the 2002 break) and 2002 (at the start of the 2099 break) stay rewritten with their interest, while on z/OS the SYSTRAN generation holding their interest transactions is deleted.

## Findings

Possible legacy defects. Each is reproduced by a migration, not fixed, and raised with the business owner.

1. **Last account never updated (BR-INT-042).** The `PERFORM UNTIL END-OF-FILE = 'Y'` loop tests the switch before the `ELSE PERFORM 1050-UPDATE-ACCOUNT` branch can run, so the final account on TCATBALF never gets its interest added or its cycle amounts reset, although its interest transactions are written to SYSTRAN. Every interest fixture shows this. Reproduce it.
2. **Interest truncated instead of rounded (BR-INT-024).** `COMPUTE WS-MONTHLY-INT` has no `ROUNDED`, so 12.355 becomes 12.35. A port using HALF_EVEN or HALF_UP fails `fixtures/interest/i02-tie-half-cent`. Reproduce with truncation toward zero (`RoundingMode.DOWN`).
3. **Fixture READMEs describe updates that do not happen.** `i01-baseline` says each transaction is added to ACCT-CURR-BAL, `i05-multi-category` says the balance grows by 17.50 and `i06-negative-balance` says the balance is reduced. In the golden files only account 2001 in i01 is updated; the single or last account in each case keeps its old balance (BR-INT-042). The expected files are correct; only the README wording is inaccurate. Neither is changed here.
4. **Misleading DISCGRP open-error message (BR-INT-004).** A failure to open the disclosure-group file displays `ERROR OPENING DALY REJECTS FILE`, which looks like a copy-paste from CBTRN02C. The text must be kept as-is for SYSOUT parity.
5. **Same `ACCOUNT NOT FOUND:` text for account and cross-reference misses (BR-INT-013, BR-INT-015).** Operators cannot tell from the first line which file was missing the key; the second line (`ERROR READING ACCOUNT FILE` / `ERROR READING XREF FILE`) is the only difference.
6. **Non-unique alternate index without WITH DUPLICATES (BR-INT-014).** XREFFILE.jcl defines the AIX as NONUNIQUEKEY, but the SELECT declares the alternate key without `WITH DUPLICATES` and the read accepts only status `00`. An account with more than one card could, depending on the runtime, fail the read (for example with status `02`) and abend, or silently use the first card. No fixture has more than one card per account, so this behaviour is not proven here.
7. **Interest classification and timestamps ignore the source category (BR-INT-033, BR-INT-036).** All interest transactions are type 01 / category 0005, and the timestamps come from the system clock rather than the PARM date (`2022071800` vs `2022-07-05`). PARM-LENGTH is never checked, so a missing PARM yields an undefined TRAN-ID prefix.
8. **Negative balances earn negative interest (BR-INT-026).** Credit balances are not excluded, so a customer in credit receives a negative interest transaction (`fixtures/interest/i06-negative-balance`).
9. **No overflow protection and no rollback (BR-INT-029, BR-INT-048).** Arithmetic has no `ON SIZE ERROR`, and account rewrites done before an abend are not undone while the SYSTRAN generation is deleted on z/OS, leaving balances without matching interest transactions.
10. **Dead code.** `WS-RECORD-COUNT` is incremented but never displayed, and `1400-COMPUTE-FEES` is an empty stub (`* To be implemented`).
