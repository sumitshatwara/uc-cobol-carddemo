# Business rules: interest slice (BR-INT-*)

**Scope.** The monthly interest calculation chain `interest`: job `app/jcl/INTCALC.jcl` (STEP15 -> `app/cbl/CBACT04C.cbl`), preceded by the setup jobs `DEFGDGB`, `ACCTFILE`, `XREFFILE`, `TCATBALF` and `DISCGRP` (`tools/runjcl/chains.json`). Copybooks: `CVTRA01Y` (TCATBALF transaction-category balance), `CVACT01Y` (account master), `CVACT03Y` (card cross-reference), `CVTRA02Y` (disclosure group / interest rate) and `CVTRA05Y` (SYSTRAN transaction). Golden fixtures: `fixtures/interest/i01-baseline` .. `i07-nontie-rounding`.

**Method.** The rules were reverse-engineered by reading the COBOL, JCL and copybooks on the integration branch line by line, then checked against the golden `expected/` datasets, SYSOUT and `rc.json` of all seven interest fixtures. Where a fixture README contradicts its `expected/` files, the golden files win; this happens for the i05 and i06 balance claims (BR-INT-035). `tools/parity/naive_ref.py` was read only as a contrast: it is a half-even reference that differs from the legacy code on the half-cent tie. Every rule cites exact `path:start-end` line ranges and has one concrete Given/When/Then test.

**Run status.** CBACT04C is executable in the Docker estate (GnuCOBOL 3, `-std=ibm -fsign=EBCDIC`, frozen clock 2022-07-05 10:00:00.00). The fixture-backed rules match the committed golden files. Rules marked `source-only` have no golden fixture. Several of them (missing account, missing XREF, missing DEFAULT group, empty input, zero-rate account break, inactive account, overflow and the negative tie) were also confirmed by off-host runs of the legacy estate on scratch inputs under the git-ignored `work/`. Those runs are evidence only and are not golden fixtures; no tracked file was changed. The open, close, read and rewrite I/O-error paths other than status 23 are analysis-only.

**Rule count by category:** calculation 4, control-flow 9, data-mapping 10, edge-case 8, return-code 6, rounding 2, state-change 3, sysout 3, validation 2 (total 47).

### BR-INT-001 INTCALC STEP15 runs CBACT04C with PARM='2022071800'

**Rule:** The INTCALC job has one step, STEP15, which runs CBACT04C with PARM='2022071800'. The program receives the z/OS parameter as EXTERNAL-PARMS (halfword PARM-LENGTH followed by PARM-DATE X(10)). Only PARM-DATE is used. PARM-LENGTH is never checked, so a short or missing PARM is not validated. Off-host, the PARMDRV stand-in rebuilds this structure from the command line.

**Source:** `app/jcl/INTCALC.jcl:22-22`, `app/cbl/CBACT04C.cbl:175-180`, `tools/stubs/PARMDRV.cbl:19-29`

**Fields:** `PARM-LENGTH`, `PARM-DATE`

**Category:** control-flow

**Test:** Given INTCALC.jcl as shipped (PARM='2022071800'); When the job runs; Then CBACT04C is called with PARM-LENGTH = 10 and PARM-DATE = '2022071800'; the first TRAN-ID starts with '2022071800'.

**Proven by:** `fixtures/interest/i01-baseline`

### BR-INT-002 Start and end banners on SYSOUT

**Rule:** The program writes 'START OF EXECUTION OF PROGRAM CBACT04C' to SYSOUT first. On a normal finish, after all files are closed, it writes 'END OF EXECUTION OF PROGRAM CBACT04C' and ends with GOBACK. An abending run never prints the end banner.

**Source:** `app/cbl/CBACT04C.cbl:181-181`, `app/cbl/CBACT04C.cbl:230-232`

**Fields:** none

**Category:** sysout

**Test:** Given any valid input set; When CBACT04C completes normally; Then SYSOUT line 1 is 'START OF EXECUTION OF PROGRAM CBACT04C' and the last line is 'END OF EXECUTION OF PROGRAM CBACT04C'.

**Proven by:** `fixtures/interest/i01-baseline`

### BR-INT-003 Setup jobs load the four KSDS masters and define the SYSTRAN GDG

**Rule:** Before INTCALC, the setup jobs run in this order: DEFGDGB, ACCTFILE, XREFFILE, TCATBALF, DISCGRP. DEFGDGB defines the GDG base AWS.M2.CARDDEMO.SYSTRAN (LIMIT 5). Each of the other four jobs deletes, defines and REPROs a KSDS from its .PS file. The keys are: ACCTDATA 11 bytes at offset 0; CARDXREF 16 bytes at offset 0, plus a non-unique AIX on bytes 25-35 (account id) with a PATH; TCATBALF 17 bytes at offset 0; DISCGRP 16 bytes at offset 0. All setup steps end RC 0.

**Source:** `app/jcl/DEFGDGB.jcl:48-53`, `app/jcl/ACCTFILE.jcl:36-62`, `app/jcl/XREFFILE.jcl:39-103`, `app/jcl/TCATBALF.jcl:36-62`, `app/jcl/DISCGRP.jcl:36-62`

**Fields:** `ACCT-ID`, `XREF-CARD-NUM`, `XREF-ACCT-ID`, `TRAN-CAT-KEY`, `DIS-GROUP-KEY`

**Category:** control-flow

**Test:** Given the i01 input .PS files; When the setup chain runs; Then the KSDS files are loaded, and rc.json lists STEP05..STEP30 with RC 0 next to INTCALC STEP15.

**Proven by:** `fixtures/interest/i01-baseline`

### BR-INT-004 File open order and access modes

**Rule:** Files are opened in this order: TCATBALF (INPUT, sequential), XREFFILE (INPUT, random, read through the alternate key), DISCGRP (INPUT, random), ACCTFILE (I-O, random) and TRANSACT (OUTPUT, sequential). Each open moves 8 to APPL-RESULT first, then 0 if the status is '00' and 12 otherwise.

**Source:** `app/cbl/CBACT04C.cbl:27-56`, `app/cbl/CBACT04C.cbl:182-186`, `app/cbl/CBACT04C.cbl:234-323`

**Fields:** `TCATBALF-STATUS`, `XREFFILE-STATUS`, `DISCGRP-STATUS`, `ACCTFILE-STATUS`, `TRANFILE-STATUS`, `APPL-RESULT`

**Category:** control-flow

**Test:** Given all five DDs are allocated; When the program starts; Then all five files open with status '00' and processing begins.

**Proven by:** `fixtures/interest/i01-baseline`

### BR-INT-005 Open failures abend with file-specific messages

**Rule:** If an open returns a status other than '00', the program displays a message and then the file status (9910-DISPLAY-IO-STATUS) and abends (9999-ABEND-PROGRAM). The messages are: TCATBALF 'ERROR OPENING TRANSACTION CATEGORY BALANCE'; XREFFILE 'ERROR OPENING CROSS REF FILE' followed directly by the raw 2-byte status; DISCGRP 'ERROR OPENING DALY REJECTS FILE'; ACCTFILE 'ERROR OPENING ACCOUNT MASTER FILE'; TRANSACT 'ERROR OPENING TRANSACTION FILE'.

**Source:** `app/cbl/CBACT04C.cbl:234-323`

**Fields:** `TCATBALF-STATUS`, `XREFFILE-STATUS`, `DISCGRP-STATUS`, `ACCTFILE-STATUS`, `TRANFILE-STATUS`

**Category:** return-code

**Test:** Given the DISCGRP KSDS is missing (open status '35'); When 0200-DISCGRP-OPEN runs; Then SYSOUT shows 'ERROR OPENING DALY REJECTS FILE', 'FILE STATUS IS: NNNN0035', 'ABENDING PROGRAM', and the step abends U0999.

**Proven by:** source-only

**Finding:** The DISCGRP open-failure message says 'DALY REJECTS FILE', a copy-paste error from another program. Operators and parity tests depend on this exact text, so it must be reproduced.

### BR-INT-006 TCATBALF processed in ascending key order

**Rule:** TRAN-CAT-BAL records are read sequentially from the TCATBALF KSDS. They therefore arrive in ascending key order: TRANCAT-ACCT-ID (9(11)), then TRANCAT-TYPE-CD (X(02)), then TRANCAT-CD (9(04)). All records for one account are adjacent, which the account-break logic relies on.

**Source:** `app/cbl/CBACT04C.cbl:28-32`, `app/cbl/CBACT04C.cbl:61-67`, `app/cbl/CBACT04C.cbl:325-326`, `app/cpy/CVTRA01Y.cpy:4-10`, `app/jcl/TCATBALF.jcl:40-40`

**Fields:** `TRANCAT-ACCT-ID`, `TRANCAT-TYPE-CD`, `TRANCAT-CD`

**Category:** control-flow

**Test:** Given account 2007 has records 01/0001 (1000.00) and 01/0002 (500.00); When TCATBALF is read; Then 01/0001 is processed first (TRAN-ID ...000001, 10.00), then 01/0002 (TRAN-ID ...000002, 7.50).

**Proven by:** `fixtures/interest/i05-multi-category`

### BR-INT-007 TCATBALF read status handling

**Rule:** After each READ of TCATBALF: status '00' sets APPL-RESULT 0 and the record is processed. Status '10' sets APPL-RESULT 16 (APPL-EOF), which sets END-OF-FILE to 'Y'. Any other status displays 'ERROR READING TRANSACTION CATEGORY FILE' and the file status, then abends.

**Source:** `app/cbl/CBACT04C.cbl:325-348`

**Fields:** `TCATBALF-STATUS`, `APPL-RESULT`, `END-OF-FILE`

**Category:** control-flow

**Test:** Given TCATBALF has 2 records; When the third READ returns status '10'; Then END-OF-FILE = 'Y'; nothing is displayed for the EOF read and the main loop ends.

**Proven by:** `fixtures/interest/i01-baseline`

### BR-INT-008 Every TCATBALF record is echoed to SYSOUT

**Rule:** For each record read (not on EOF), WS-RECORD-COUNT is incremented. The whole 50-byte TRAN-CAT-BAL-RECORD is DISPLAYed as raw text: the 17-byte key, the zoned TRAN-CAT-BAL with its overpunch sign character, and 22 trailing FILLER spaces. WS-RECORD-COUNT is never displayed or used anywhere else.

**Source:** `app/cbl/CBACT04C.cbl:172-172`, `app/cbl/CBACT04C.cbl:191-193`

**Fields:** `TRAN-CAT-BAL-RECORD`, `WS-RECORD-COUNT`

**Category:** sysout

**Test:** Given a TCATBALF record for account 2008, type 01, category 0001, balance -300.00; When it is read; Then SYSOUT shows '000000020080100010000003000}' followed by 22 spaces.

**Proven by:** `fixtures/interest/i06-negative-balance`, `fixtures/interest/i01-baseline`

### BR-INT-009 Account break detection

**Rule:** A new account starts when TRANCAT-ACCT-ID differs from WS-LAST-ACCT-NUM (X(11), initially SPACES). On a break: if WS-FIRST-TIME is not 'Y', the previous account is rewritten (1050-UPDATE-ACCOUNT); otherwise WS-FIRST-TIME is set to 'N' and nothing is rewritten. Then WS-TOTAL-INT is set to 0, the new account id is saved, and the account and XREF records are read.

**Source:** `app/cbl/CBACT04C.cbl:166-170`, `app/cbl/CBACT04C.cbl:194-206`

**Fields:** `TRANCAT-ACCT-ID`, `WS-LAST-ACCT-NUM`, `WS-FIRST-TIME`, `WS-TOTAL-INT`

**Category:** control-flow

**Test:** Given TCATBALF holds accounts 2001 then 2002; When the 2002 record is read; Then account 2001 is rewritten with its 10.00 interest before 2002 is read; nothing is rewritten when the first record (2001) arrives.

**Proven by:** `fixtures/interest/i01-baseline`

### BR-INT-010 Account master read at account break

**Rule:** At each account break, ACCOUNT-FILE is read at random with key FD-ACCT-ID = TRANCAT-ACCT-ID, INTO ACCOUNT-RECORD. The record (ACCT-GROUP-ID, ACCT-CURR-BAL, ...) stays in working storage for all categories of that account, and is the record rewritten at the next break.

**Source:** `app/cbl/CBACT04C.cbl:202-203`, `app/cbl/CBACT04C.cbl:372-391`, `app/cpy/CVACT01Y.cpy:4-17`

**Fields:** `FD-ACCT-ID`, `ACCT-ID`, `ACCT-GROUP-ID`, `ACCT-CURR-BAL`

**Category:** data-mapping

**Test:** Given TCATBALF account 2002 and ACCTDATA account 2002 in group STANDARD; When the break for 2002 happens; Then ACCOUNT-RECORD holds account 2002, and its rate lookup uses group 'STANDARD' (18.50 %).

**Proven by:** `fixtures/interest/i01-baseline`

### BR-INT-011 Missing account abends

**Rule:** If the account read gets INVALID KEY (status '23'), the program displays 'ACCOUNT NOT FOUND: ' with the 11-digit FD-ACCT-ID, then 'ERROR READING ACCOUNT FILE' and the file status, and abends. Any other non-'00' status takes the same path without the NOT FOUND line. There is no skip-and-continue. Confirmed by an off-host scratch run of the legacy estate (not a golden fixture).

**Source:** `app/cbl/CBACT04C.cbl:372-391`

**Fields:** `FD-ACCT-ID`, `ACCTFILE-STATUS`

**Category:** edge-case

**Test:** Given TCATBALF has a record for account 3001, which is not on ACCTDATA; When the break for 3001 happens; Then SYSOUT shows 'ACCOUNT NOT FOUND: 00000003001', 'ERROR READING ACCOUNT FILE', 'FILE STATUS IS: NNNN0023', 'ABENDING PROGRAM'; STEP15 RC 12 off-host (U0999 on z/OS); no SYSTRAN records.

**Proven by:** source-only

### BR-INT-012 Card number taken from XREF by account id

**Rule:** At each account break, XREF-FILE is read at random through the alternate key FD-XREF-ACCT-ID = TRANCAT-ACCT-ID, INTO CARD-XREF-RECORD. The AIX is defined NONUNIQUEKEY, so an account with several cards gets the first card returned for that account id. XREF-CARD-NUM becomes TRAN-CARD-NUM on every interest transaction for that account.

**Source:** `app/cbl/CBACT04C.cbl:34-39`, `app/cbl/CBACT04C.cbl:204-205`, `app/cbl/CBACT04C.cbl:393-398`, `app/cbl/CBACT04C.cbl:495-495`, `app/jcl/XREFFILE.jcl:72-80`, `app/cpy/CVACT03Y.cpy:4-8`

**Fields:** `FD-XREF-ACCT-ID`, `XREF-CARD-NUM`, `TRAN-CARD-NUM`

**Category:** data-mapping

**Test:** Given CARDXREF maps card 4000000000002001 to account 2001; When interest is written for account 2001; Then TRAN-CARD-NUM = '4000000000002001'.

**Proven by:** `fixtures/interest/i01-baseline`

### BR-INT-013 Missing XREF abends with an 'ACCOUNT NOT FOUND' message

**Rule:** If the XREF read by account id gets INVALID KEY, the program displays 'ACCOUNT NOT FOUND: ' with FD-XREF-ACCT-ID, then 'ERROR READING XREF FILE' and the file status, and abends. This happens even though the account record itself exists. Confirmed by an off-host scratch run of the legacy estate (not a golden fixture).

**Source:** `app/cbl/CBACT04C.cbl:393-413`

**Fields:** `FD-XREF-ACCT-ID`, `XREFFILE-STATUS`

**Category:** edge-case

**Test:** Given account 2001 is on ACCTDATA and TCATBALF but has no CARDXREF row; When the break for 2001 happens; Then SYSOUT shows 'ACCOUNT NOT FOUND: 00000002001', 'ERROR READING XREF FILE', 'FILE STATUS IS: NNNN0023', 'ABENDING PROGRAM'; STEP15 RC 12 off-host.

**Proven by:** source-only

**Finding:** When the card cross-reference is missing, the program reports 'ACCOUNT NOT FOUND', which misleads operators because the account does exist. Reproduce the exact text.

### BR-INT-014 Disclosure-group key built from account group, type and category

**Rule:** For every TCATBALF record, the DISCGRP key is built as FD-DIS-ACCT-GROUP-ID = ACCT-GROUP-ID (X(10)), FD-DIS-TRAN-TYPE-CD = TRANCAT-TYPE-CD and FD-DIS-TRAN-CAT-CD = TRANCAT-CD. The lookup is repeated for each category record, not once per account.

**Source:** `app/cbl/CBACT04C.cbl:76-82`, `app/cbl/CBACT04C.cbl:210-213`, `app/cpy/CVTRA02Y.cpy:4-10`

**Fields:** `ACCT-GROUP-ID`, `TRANCAT-TYPE-CD`, `TRANCAT-CD`, `FD-DIS-ACCT-GROUP-ID`, `FD-DIS-TRAN-TYPE-CD`, `FD-DIS-TRAN-CAT-CD`

**Category:** data-mapping

**Test:** Given account 2002 in group 'STANDARD  ' with a TCATBALF record of type 01, category 0001; When the rate is looked up; Then the key read is 'STANDARD  010001', giving DIS-INT-RATE 18.50.

**Proven by:** `fixtures/interest/i01-baseline`

### BR-INT-015 Interest rate differs by account group

**Rule:** Two accounts with the same type and category but in different groups get different rates. GOLD 01/0001 is 12.00 % and STANDARD 01/0001 is 18.50 %.

**Source:** `app/cbl/CBACT04C.cbl:210-215`, `app/cbl/CBACT04C.cbl:415-416`

**Fields:** `ACCT-GROUP-ID`, `DIS-INT-RATE`

**Category:** calculation

**Test:** Given account 2001 GOLD with balance 1000.00 and account 2002 STANDARD with balance 2400.00, both 01/0001; When interest is computed; Then TRAN-AMT is 10.00 for 2001 and 37.00 for 2002.

**Proven by:** `fixtures/interest/i01-baseline`

### BR-INT-016 Interest rate differs by transaction category

**Rule:** Each TCATBALF category record gets its own rate. For GOLD, type 01, category 0001 (purchases) is 12.00 % and category 0002 (cash advances) is 18.00 %.

**Source:** `app/cbl/CBACT04C.cbl:210-215`, `app/cbl/CBACT04C.cbl:415-416`

**Fields:** `TRANCAT-CD`, `DIS-INT-RATE`

**Category:** calculation

**Test:** Given account 2007 GOLD with 01/0001 = 1000.00 and 01/0002 = 500.00; When interest is computed; Then two transactions are written: 10.00 and 7.50.

**Proven by:** `fixtures/interest/i05-multi-category`

### BR-INT-017 DEFAULT disclosure-group fallback

**Rule:** If the DISCGRP read gets INVALID KEY (status '23'), the program displays 'DISCLOSURE GROUP RECORD MISSING' and 'TRY WITH DEFAULT GROUP CODE'. Status '23' still counts as success (APPL-RESULT 0). FD-DIS-ACCT-GROUP-ID is set to 'DEFAULT' (same type and category) and the record is read again (1200-A-GET-DEFAULT-INT-RATE). The fallback happens for each category record that misses.

**Source:** `app/cbl/CBACT04C.cbl:415-440`, `app/cbl/CBACT04C.cbl:443-446`

**Fields:** `FD-DIS-ACCT-GROUP-ID`, `DISCGRP-STATUS`, `DIS-INT-RATE`

**Category:** control-flow

**Test:** Given account 2006 in group PLATINUM (no DISCGRP rows) with 01/0001 = 1000.00; DEFAULT 01/0001 = 21.00 %; When the rate is looked up; Then SYSOUT shows the two fallback lines after the record echo, and TRAN-AMT = 17.50.

**Proven by:** `fixtures/interest/i04-default-group-fallback`

### BR-INT-018 Accounts in group DEFAULT match directly

**Rule:** 'DEFAULT' is an ordinary group key. An account whose ACCT-GROUP-ID is 'DEFAULT' finds its row on the first read: no fallback messages, and the DEFAULT rate applies.

**Source:** `app/cbl/CBACT04C.cbl:210-213`, `app/cbl/CBACT04C.cbl:415-440`

**Fields:** `ACCT-GROUP-ID`, `DIS-INT-RATE`

**Category:** control-flow

**Test:** Given account 2010 in group DEFAULT with 01/0001 = 777.77; DEFAULT 01/0001 = 21.00 %; When the rate is looked up; Then no 'DISCLOSURE GROUP RECORD MISSING' line is printed and TRAN-AMT = 13.61.

**Proven by:** `fixtures/interest/i07-nontie-rounding`

### BR-INT-019 Disclosure-group read failures abend

**Rule:** A first DISCGRP read with a status other than '00' or '23' displays 'ERROR READING DISCLOSURE GROUP FILE' and abends. If the DEFAULT row is also missing (the second read does not return '00'), the program displays 'ERROR READING DEFAULT DISCLOSURE GROUP' and the file status, and abends. Confirmed by an off-host scratch run of the legacy estate (not a golden fixture).

**Source:** `app/cbl/CBACT04C.cbl:422-435`, `app/cbl/CBACT04C.cbl:443-460`

**Fields:** `DISCGRP-STATUS`

**Category:** return-code

**Test:** Given account 2001 in group PLATINUM with a TCATBALF record 01/0003; there is no PLATINUM or DEFAULT 01/0003 row; When the rate is looked up; Then SYSOUT shows 'DISCLOSURE GROUP RECORD MISSING', 'TRY WITH DEFAULT GROUP CODE', 'ERROR READING DEFAULT DISCLOSURE GROUP', 'FILE STATUS IS: NNNN0023', 'ABENDING PROGRAM'; STEP15 RC 12 off-host.

**Proven by:** source-only

### BR-INT-020 Zero-rate categories are skipped

**Rule:** If DIS-INT-RATE = 0, the program skips 1300-COMPUTE-INTEREST and 1400-COMPUTE-FEES. No transaction is written, WS-TRANID-SUFFIX does not advance, and WS-TOTAL-INT does not change. The record is still echoed and still counts as part of its account.

**Source:** `app/cbl/CBACT04C.cbl:214-217`

**Fields:** `DIS-INT-RATE`, `WS-TRANID-SUFFIX`

**Category:** validation

**Test:** Given account 2005 GOLD with 01/0001 = 1000.00 (12.00 %) and 02/0001 = -300.00 (0.00 %); When the run completes; Then exactly one SYSTRAN record (TRAN-ID 2022071800000001, 10.00) is written.

**Proven by:** `fixtures/interest/i03-zero-rate-skipped`

### BR-INT-021 Monthly interest formula

**Rule:** For each category with a non-zero rate: WS-MONTHLY-INT = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200. This is the annual percentage rate divided by 12 months and by 100. TRAN-CAT-BAL is S9(09)V99, DIS-INT-RATE is S9(04)V99 and the result is S9(09)V99.

**Source:** `app/cbl/CBACT04C.cbl:168-168`, `app/cbl/CBACT04C.cbl:462-466`, `app/cpy/CVTRA01Y.cpy:9-9`, `app/cpy/CVTRA02Y.cpy:9-9`

**Fields:** `TRAN-CAT-BAL`, `DIS-INT-RATE`, `WS-MONTHLY-INT`

**Category:** calculation

**Test:** Given TRAN-CAT-BAL = 2400.00 and DIS-INT-RATE = 18.50; When 1300-COMPUTE-INTEREST runs; Then WS-MONTHLY-INT = 37.00.

**Proven by:** `fixtures/interest/i01-baseline`

### BR-INT-022 Interest is truncated, not rounded (half cent)

**Rule:** The COMPUTE has no ROUNDED phrase, so digits beyond 2 decimals are dropped (truncated toward zero). An exact half cent is truncated down. A half-even reference (tools/parity/naive_ref.py) gives a different result only on such ties.

**Source:** `app/cbl/CBACT04C.cbl:464-465`

**Fields:** `WS-MONTHLY-INT`, `TRAN-AMT`

**Category:** rounding

**Test:** Given account 2003 balance 1235.50 at 12.00 % (12.355) and account 2004 balance 1234.50 at 12.00 % (12.345); When interest is computed; Then TRAN-AMT = 12.35 for 2003 (half-even would give 12.36) and 12.34 for 2004.

**Proven by:** `fixtures/interest/i02-tie-half-cent`

**Finding:** Interest is truncated on every computation, so the bank under-charges up to 0.01 per category per month. This differs from both half-up and banker's rounding. Reproduce it with RoundingMode.DOWN.

### BR-INT-023 Non-tie sub-cent remainders are truncated

**Rule:** Remainders that are not ties are also dropped. In these cases truncation agrees with half-even and half-up rounding.

**Source:** `app/cbl/CBACT04C.cbl:464-465`

**Fields:** `WS-MONTHLY-INT`, `TRAN-AMT`

**Category:** rounding

**Test:** Given account 2009 1000.33 at 18.50 % (15.42175) and account 2010 777.77 at 21.00 % (13.610975); When interest is computed; Then TRAN-AMT = 15.42 and 13.61.

**Proven by:** `fixtures/interest/i07-nontie-rounding`

### BR-INT-024 Negative balances produce negative interest

**Rule:** A credit (negative) TRAN-CAT-BAL is not filtered out. It gives a negative WS-MONTHLY-INT, a negative interest transaction, and a lower total for the account. Truncation is toward zero, so a negative half cent -12.355 becomes -12.35 (not -12.36). The negative-tie case was confirmed by an off-host scratch run.

**Source:** `app/cbl/CBACT04C.cbl:214-215`, `app/cbl/CBACT04C.cbl:464-467`, `app/cbl/CBACT04C.cbl:490-490`

**Fields:** `TRAN-CAT-BAL`, `WS-MONTHLY-INT`, `TRAN-AMT`

**Category:** edge-case

**Test:** Given account 2008 GOLD with 01/0001 = -300.00 at 12.00 %; When interest is computed; Then a SYSTRAN record with TRAN-AMT = -3.00 (zoned '0000000030}') is written.

**Proven by:** `fixtures/interest/i06-negative-balance`

**Finding:** The program charges negative interest (a credit to the customer) on credit balances, and nothing in the program prevents it. This must be reproduced.

### BR-INT-025 Zoned-decimal signs, not COMP-3

**Rule:** All amounts in this slice (TRAN-CAT-BAL, DIS-INT-RATE, ACCT-CURR-BAL, ACCT-CURR-CYC-CREDIT/DEBIT, TRAN-AMT) are signed zoned DISPLAY fields with a trailing overpunch sign. No COMP-3 is used. The build uses -fsign=EBCDIC, so the last byte reads '{' = +0, 'A'..'I' = +1..+9, '}' = -0, 'J'..'R' = -1..-9. A Java port must encode and decode these signs byte for byte.

**Source:** `app/cpy/CVTRA01Y.cpy:9-9`, `app/cpy/CVTRA02Y.cpy:9-9`, `app/cpy/CVACT01Y.cpy:7-14`, `app/cpy/CVTRA05Y.cpy:10-10`, `tools/build.sh:16-16`

**Fields:** `TRAN-CAT-BAL`, `DIS-INT-RATE`, `ACCT-CURR-BAL`, `TRAN-AMT`

**Category:** data-mapping

**Test:** Given an account balance of 1247.85 and an interest amount of 15.42; When the records are written; Then ACCT-CURR-BAL is stored as '00000012478E' and TRAN-AMT as '0000000154B'.

**Proven by:** `fixtures/interest/i02-tie-half-cent`, `fixtures/interest/i07-nontie-rounding`, `fixtures/interest/i06-negative-balance`

### BR-INT-026 Account interest total is the sum of truncated category amounts

**Rule:** WS-TOTAL-INT is set to 0 at each account break. Each category's already-truncated WS-MONTHLY-INT is added to it. The total is therefore a sum of truncated values, not one truncation of the combined amount.

**Source:** `app/cbl/CBACT04C.cbl:169-169`, `app/cbl/CBACT04C.cbl:200-200`, `app/cbl/CBACT04C.cbl:467-467`

**Fields:** `WS-TOTAL-INT`, `WS-MONTHLY-INT`

**Category:** calculation

**Test:** Given account 2009 (15.42175 -> 15.42) followed by account 2010; When the break for 2010 happens; Then WS-TOTAL-INT for 2009 is 15.42 and ACCT-CURR-BAL becomes 1000.33 + 15.42 = 1015.75.

**Proven by:** `fixtures/interest/i07-nontie-rounding`, `fixtures/interest/i05-multi-category`

### BR-INT-027 Arithmetic overflow drops high-order digits silently

**Rule:** The COMPUTE into WS-MONTHLY-INT (S9(09)V99) and the ADD into ACCT-CURR-BAL (S9(10)V99) have no ON SIZE ERROR. A result too large for the field loses its leading digits without any message, and the return code stays 0. Confirmed by an off-host scratch run of the legacy estate (not a golden fixture).

**Source:** `app/cbl/CBACT04C.cbl:168-169`, `app/cbl/CBACT04C.cbl:352-352`, `app/cbl/CBACT04C.cbl:464-467`

**Fields:** `WS-MONTHLY-INT`, `WS-TOTAL-INT`, `ACCT-CURR-BAL`

**Category:** edge-case

**Test:** Given TRAN-CAT-BAL = 999999999.99, DIS-INT-RATE = 9999.99 (true result 8333324999.91), ACCT-CURR-BAL = 9999999999.99, and the account is not the last one; When the run completes; Then TRAN-AMT = 333324999.91 and ACCT-CURR-BAL = 333324999.90 (high-order digits lost); STEP15 RC 0.

**Proven by:** source-only

**Finding:** Overflow is silent and corrupts both the transaction amount and the balance. Realistic rates cannot reach it, but a converted program must truncate the same way and must not raise an error.

### BR-INT-028 1050-UPDATE-ACCOUNT adds total interest to ACCT-CURR-BAL

**Rule:** When an account is closed off, WS-TOTAL-INT is added to ACCT-CURR-BAL in ACCOUNT-RECORD, and the record is rewritten to ACCTFILE.

**Source:** `app/cbl/CBACT04C.cbl:350-356`

**Fields:** `ACCT-CURR-BAL`, `WS-TOTAL-INT`

**Category:** state-change

**Test:** Given account 2003 with ACCT-CURR-BAL 1235.50 and interest 12.35, followed by account 2004; When the break for 2004 happens; Then account 2003 is rewritten with ACCT-CURR-BAL = 1247.85.

**Proven by:** `fixtures/interest/i02-tie-half-cent`, `fixtures/interest/i01-baseline`

### BR-INT-029 1050-UPDATE-ACCOUNT zeroes the cycle credit and debit

**Rule:** The same rewrite sets ACCT-CURR-CYC-CREDIT and ACCT-CURR-CYC-DEBIT to 0, which closes the billing cycle.

**Source:** `app/cbl/CBACT04C.cbl:353-354`

**Fields:** `ACCT-CURR-CYC-CREDIT`, `ACCT-CURR-CYC-DEBIT`

**Category:** state-change

**Test:** Given account 2001 with ACCT-CURR-CYC-CREDIT 1000.00, followed by account 2002; When the break for 2002 happens; Then account 2001 is rewritten with ACCT-CURR-CYC-CREDIT = 0.00 and ACCT-CURR-CYC-DEBIT = 0.00.

**Proven by:** `fixtures/interest/i01-baseline`, `fixtures/interest/i07-nontie-rounding`

### BR-INT-030 Accounts with only zero-rate categories are still rewritten

**Rule:** An account whose categories are all zero-rate still goes through 1050-UPDATE-ACCOUNT at the next break, as long as it is not the last account. 0.00 is added to the balance (no change), the cycle fields are zeroed, and the record is rewritten. No SYSTRAN record is written for it. Confirmed by an off-host scratch run of the legacy estate (not a golden fixture).

**Source:** `app/cbl/CBACT04C.cbl:194-200`, `app/cbl/CBACT04C.cbl:214-217`, `app/cbl/CBACT04C.cbl:350-356`

**Fields:** `ACCT-CURR-BAL`, `ACCT-CURR-CYC-CREDIT`, `ACCT-CURR-CYC-DEBIT`

**Category:** edge-case

**Test:** Given account 2001 with only 02/0001 = 1000.00 (0.00 %), cycle credit 1000.00 and cycle debit 300.00, followed by account 2002; When the run completes; Then account 2001 keeps ACCT-CURR-BAL 1000.00 but has cycle credit and debit 0.00; the first SYSTRAN record belongs to account 2002.

**Proven by:** source-only

### BR-INT-031 Other account fields are preserved on rewrite; untouched accounts unchanged

**Rule:** REWRITE FD-ACCTFILE-REC FROM ACCOUNT-RECORD writes back the full 300-byte record as read. ACCT-ID, ACCT-ACTIVE-STATUS, the credit limits, the open, expiry and reissue dates, ACCT-ADDR-ZIP, ACCT-GROUP-ID and FILLER are unchanged. Accounts with no TCATBALF records are never read or rewritten.

**Source:** `app/cbl/CBACT04C.cbl:84-87`, `app/cbl/CBACT04C.cbl:356-356`, `app/cpy/CVACT01Y.cpy:4-17`

**Fields:** `ACCOUNT-RECORD`, `ACCT-CREDIT-LIMIT`, `ACCT-OPEN-DATE`, `ACCT-GROUP-ID`

**Category:** data-mapping

**Test:** Given account 2009 with credit limit 5000.00, open date 2019-03-15, zip 10115, group STANDARD; When it is rewritten with interest; Then only ACCT-CURR-BAL and the two cycle fields differ from the input record.

**Proven by:** `fixtures/interest/i07-nontie-rounding`

### BR-INT-032 No eligibility checks on account status or dates

**Rule:** The program never reads ACCT-ACTIVE-STATUS, ACCT-EXPIRAION-DATE or ACCT-REISSUE-DATE, and it does not validate dates (blank or zero dates pass through untouched). Inactive and expired accounts are charged interest like any other account. Confirmed by an off-host scratch run of the legacy estate (not a golden fixture).

**Source:** `app/cbl/CBACT04C.cbl:188-222`, `app/cpy/CVACT01Y.cpy:6-12`

**Fields:** `ACCT-ACTIVE-STATUS`, `ACCT-EXPIRAION-DATE`

**Category:** validation

**Test:** Given account 2002 with ACCT-ACTIVE-STATUS 'N', balance 1000.00, 01/0001 = -300.00 at 12.00 %, followed by account 2003; When the run completes; Then account 2002 gets a -3.00 transaction and is rewritten with ACCT-CURR-BAL 997.00 and cycle fields 0.00.

**Proven by:** source-only

**Finding:** Inactive and expired accounts accrue interest, because no status or date check exists. This must be reproduced.

### BR-INT-033 Rewrite failure abends

**Rule:** If REWRITE of ACCTFILE returns a status other than '00', the program displays 'ERROR RE-WRITING ACCOUNT FILE' and the file status, and abends.

**Source:** `app/cbl/CBACT04C.cbl:356-369`

**Fields:** `ACCTFILE-STATUS`

**Category:** return-code

**Test:** Given the ACCTFILE REWRITE returns status '22'; When 1050-UPDATE-ACCOUNT runs; Then SYSOUT shows 'ERROR RE-WRITING ACCOUNT FILE', 'FILE STATUS IS: NNNN0022', 'ABENDING PROGRAM'; the step abends.

**Proven by:** source-only

### BR-INT-034 End-of-file quirk: the last account on TCATBALF is never updated

**Rule:** END-OF-FILE is set to 'Y' inside the loop body, on the READ that hits EOF. The inner IF then fails, and PERFORM UNTIL END-OF-FILE = 'Y' ends the loop before the outer ELSE branch (PERFORM 1050-UPDATE-ACCOUNT) can run. The highest account on TCATBALF therefore gets its interest transactions written, but its ACCT-CURR-BAL and cycle fields are never rewritten.

**Source:** `app/cbl/CBACT04C.cbl:188-222`

**Fields:** `END-OF-FILE`, `ACCT-CURR-BAL`, `ACCT-CURR-CYC-CREDIT`, `ACCT-CURR-CYC-DEBIT`

**Category:** control-flow

**Test:** Given accounts 2001 and 2002 with interest 10.00 and 37.00; When the run completes; Then 2001 becomes 1010.00 with cycle fields 0.00; 2002 stays at 2400.00 with cycle credit 2400.00, even though its 37.00 transaction is on SYSTRAN.

**Proven by:** `fixtures/interest/i01-baseline`, `fixtures/interest/i02-tie-half-cent`, `fixtures/interest/i07-nontie-rounding`

**Finding:** Interest is posted to SYSTRAN for the last account but never added to its balance, which leaves the books out of balance. This is the documented legacy quirk (docs/BASELINE.md) and must be reproduced. A Java port must not add a final update.

### BR-INT-035 Single-account runs update no account at all

**Rule:** Because of the end-of-file quirk, when TCATBALF holds one account only, ACCTDATA comes out byte-identical to the input, while SYSTRAN still holds the interest transactions. The fixture READMEs for i05 and i06 say the balance grows or shrinks. The golden expected/ files show it unchanged, and the golden files are authoritative.

**Source:** `app/cbl/CBACT04C.cbl:188-222`

**Fields:** `ACCT-CURR-BAL`

**Category:** edge-case

**Test:** Given account 2007 alone, with interest 10.00 + 7.50; When the run completes; Then two SYSTRAN records are written, but ACCT-CURR-BAL stays 1500.00 and cycle credit stays 1500.00.

**Proven by:** `fixtures/interest/i03-zero-rate-skipped`, `fixtures/interest/i04-default-group-fallback`, `fixtures/interest/i05-multi-category`, `fixtures/interest/i06-negative-balance`

**Finding:** The i05 and i06 READMEs say the account balance changes, but the golden files (and the legacy code) leave it unchanged. Reproduce the golden behaviour.

### BR-INT-036 TRAN-ID = PARM-DATE + run-wide 6-digit sequence

**Rule:** Before each interest transaction, WS-TRANID-SUFFIX (9(06), initially 0) is incremented. TRAN-ID (X(16)) is then built with STRING from PARM-DATE (10 bytes) and WS-TRANID-SUFFIX (6 digits), DELIMITED BY SIZE. The suffix runs across the whole job: it is not reset per account and skips nothing, because zero-rate categories never increment it.

**Source:** `app/cbl/CBACT04C.cbl:173-173`, `app/cbl/CBACT04C.cbl:474-480`

**Fields:** `TRAN-ID`, `PARM-DATE`, `WS-TRANID-SUFFIX`

**Category:** data-mapping

**Test:** Given PARM '2022071800' with interest for accounts 2001 and 2002; When transactions are written; Then the TRAN-IDs are '2022071800000001' (2001) and '2022071800000002' (2002).

**Proven by:** `fixtures/interest/i01-baseline`, `fixtures/interest/i03-zero-rate-skipped`

### BR-INT-037 Fixed SYSTRAN codes and blank merchant fields

**Rule:** Every interest transaction has TRAN-TYPE-CD '01', TRAN-CAT-CD '05' (moved into 9(04), so stored as 0005), TRAN-SOURCE 'System' (space-padded to 10), TRAN-MERCHANT-ID 0, and TRAN-MERCHANT-NAME, CITY and ZIP all spaces.

**Source:** `app/cbl/CBACT04C.cbl:482-484`, `app/cbl/CBACT04C.cbl:491-494`, `app/cpy/CVTRA05Y.cpy:4-18`

**Fields:** `TRAN-TYPE-CD`, `TRAN-CAT-CD`, `TRAN-SOURCE`, `TRAN-MERCHANT-ID`, `TRAN-MERCHANT-NAME`, `TRAN-MERCHANT-CITY`, `TRAN-MERCHANT-ZIP`

**Category:** data-mapping

**Test:** Given any interest transaction; When it is written; Then bytes 17-32 are '010005System    ' and TRAN-MERCHANT-ID is '000000000'.

**Proven by:** `fixtures/interest/i01-baseline`

### BR-INT-038 TRAN-DESC = 'Int. for a/c ' + 11-digit account id

**Rule:** TRAN-DESC (X(100)) is built with STRING from the literal 'Int. for a/c ' (with its trailing space) and ACCT-ID (11 zero-padded digits from the current ACCOUNT-RECORD), and is space-padded.

**Source:** `app/cbl/CBACT04C.cbl:485-489`

**Fields:** `TRAN-DESC`, `ACCT-ID`

**Category:** data-mapping

**Test:** Given account 2001; When its interest transaction is written; Then TRAN-DESC = 'Int. for a/c 00000002001' followed by spaces.

**Proven by:** `fixtures/interest/i01-baseline`

### BR-INT-039 TRAN-AMT is the per-category monthly interest

**Rule:** TRAN-AMT is WS-MONTHLY-INT for that one category, not the account total. An account with several charged categories gets one transaction per category.

**Source:** `app/cbl/CBACT04C.cbl:467-468`, `app/cbl/CBACT04C.cbl:490-490`

**Fields:** `TRAN-AMT`, `WS-MONTHLY-INT`

**Category:** data-mapping

**Test:** Given account 2007 with category interests 10.00 and 7.50; When transactions are written; Then there are two SYSTRAN records, with TRAN-AMT 10.00 and 7.50 (not one record of 17.50).

**Proven by:** `fixtures/interest/i05-multi-category`

### BR-INT-040 PROC-TS and ORIG-TS come from the frozen clock

**Rule:** Z-GET-DB2-FORMAT-TIMESTAMP formats FUNCTION CURRENT-DATE as 'YYYY-MM-DD-HH.MM.SS.hh0000': the hundredths of a second followed by the literal '0000'. Both TRAN-ORIG-TS and TRAN-PROC-TS get the same value. Off-host the chain clock is frozen at 2022-07-05 10:00:00.00. The timestamp does not come from PARM-DATE.

**Source:** `app/cbl/CBACT04C.cbl:140-165`, `app/cbl/CBACT04C.cbl:496-498`, `app/cbl/CBACT04C.cbl:613-626`, `tools/runjcl/chains.json:45-45`

**Fields:** `TRAN-ORIG-TS`, `TRAN-PROC-TS`, `DB2-FORMAT-TS`

**Category:** data-mapping

**Test:** Given the frozen clock 2022-07-05 10:00:00.00; When any interest transaction is written; Then TRAN-ORIG-TS = TRAN-PROC-TS = '2022-07-05-10.00.00.000000'.

**Proven by:** `fixtures/interest/i01-baseline`

### BR-INT-041 SYSTRAN is a new 350-byte GDG generation

**Rule:** TRANSACT is opened OUTPUT on AWS.M2.CARDDEMO.SYSTRAN(+1) (RECFM=F, LRECL=350, DISP=(NEW,CATLG,DELETE)). Each WRITE FD-TRANFILE-REC FROM TRAN-RECORD must return '00'; otherwise the program displays 'ERROR WRITING TRANSACTION RECORD' and the file status, and abends. Off-host the first generation is G0001V00.

**Source:** `app/jcl/INTCALC.jcl:37-41`, `app/cbl/CBACT04C.cbl:89-92`, `app/cbl/CBACT04C.cbl:500-515`

**Fields:** `TRAN-RECORD`, `TRANFILE-STATUS`

**Category:** state-change

**Test:** Given the i01 inputs; When the run completes; Then AWS.M2.CARDDEMO.SYSTRAN.G0001V00 holds 2 records of 350 bytes (manifest lrecl 350).

**Proven by:** `fixtures/interest/i01-baseline`

### BR-INT-042 Fee computation is an empty stub

**Rule:** 1400-COMPUTE-FEES is performed for every charged category, but its body is only the comment '* To be implemented' followed by EXIT. No fee transactions are written and no balance changes come from fees.

**Source:** `app/cbl/CBACT04C.cbl:216-216`, `app/cbl/CBACT04C.cbl:518-520`, `app/jcl/INTCALC.jcl:20-20`

**Fields:** none

**Category:** edge-case

**Test:** Given any category with a non-zero rate; When 1400-COMPUTE-FEES runs; Then nothing happens: SYSTRAN holds only interest records (type 01, category 0005).

**Proven by:** `fixtures/interest/i05-multi-category`

**Finding:** The JCL comment says the step will 'compute interest and fees', but fees are never charged. A converted program must not add fee logic.

### BR-INT-043 Empty TCATBALF produces an empty run with RC 0

**Rule:** If the first TCATBALF READ returns EOF, no record is echoed, no account is read or rewritten, and SYSTRAN is created with 0 records. SYSOUT contains only the start and end banners, and the step ends RC 0. Confirmed by an off-host scratch run of the legacy estate (not a golden fixture).

**Source:** `app/cbl/CBACT04C.cbl:188-222`

**Fields:** `END-OF-FILE`

**Category:** edge-case

**Test:** Given an empty TCATBALF.PS and account 2001 on ACCTDATA; When the run completes; Then SYSOUT is exactly the 2 banner lines; ACCTDATA is unchanged; SYSTRAN.G0001V00 has 0 records; rc.json STEP15 = 0.

**Proven by:** source-only

### BR-INT-044 Close order and close failures

**Rule:** After the loop, files are closed in the same order they were opened (TCATBALF, XREF, DISCGRP, ACCTFILE, TRANSACT). A close status other than '00' displays one of: 'ERROR CLOSING TRANSACTION BALANCE FILE', 'ERROR CLOSING CROSS REF FILE', 'ERROR CLOSING DISCLOSURE GROUP FILE', 'ERROR CLOSING ACCOUNT FILE' or 'ERROR CLOSING TRANSACTION FILE', then the file status, and abends.

**Source:** `app/cbl/CBACT04C.cbl:224-228`, `app/cbl/CBACT04C.cbl:522-611`

**Fields:** `TCATBALF-STATUS`, `XREFFILE-STATUS`, `DISCGRP-STATUS`, `ACCTFILE-STATUS`, `TRANFILE-STATUS`

**Category:** return-code

**Test:** Given a normal run; When the closes run; Then all five closes return '00' and the end banner follows.

**Proven by:** `fixtures/interest/i01-baseline`

### BR-INT-045 File-status display format (9910-DISPLAY-IO-STATUS)

**Rule:** Every error path displays 'FILE STATUS IS: NNNN' followed by a 4-character IO-STATUS-04. If the status is numeric and does not start with '9', the value is '00' plus the 2-digit status (e.g. '0023'). If the status is not numeric or starts with '9', the value is the first status byte plus the binary value of the second byte as 3 digits.

**Source:** `app/cbl/CBACT04C.cbl:122-131`, `app/cbl/CBACT04C.cbl:635-648`

**Fields:** `IO-STATUS`, `IO-STATUS-04`, `TWO-BYTES-BINARY`

**Category:** sysout

**Test:** Given an account read that fails with status '23'; When 9910-DISPLAY-IO-STATUS runs; Then SYSOUT shows 'FILE STATUS IS: NNNN0023' (the literal 'NNNN' is printed as-is).

**Proven by:** source-only

### BR-INT-046 Abend path: U0999 via CEE3ABD; no rollback

**Rule:** 9999-ABEND-PROGRAM displays 'ABENDING PROGRAM' and calls CEE3ABD with ABCODE 999 and TIMING 0: user abend U0999 on z/OS. Off-host, the CEE3ABD stand-in also displays 'CEE3ABD: USER ABEND U+0000000999' and ends the step with RC 12. GnuCOBOL then adds 'implicit CLOSE' warnings, which are runtime noise and not program output. Accounts rewritten before the abend stay rewritten; nothing is rolled back. On z/OS, DISP=(NEW,CATLG,DELETE) deletes the new SYSTRAN generation on abend.

**Source:** `app/cbl/CBACT04C.cbl:138-139`, `app/cbl/CBACT04C.cbl:628-632`, `tools/stubs/CEE3ABD.cbl:15-18`, `app/jcl/INTCALC.jcl:37-37`

**Fields:** `ABCODE`, `TIMING`, `RETURN-CODE`

**Category:** return-code

**Test:** Given a missing account 3001 on TCATBALF; When the account read fails; Then SYSOUT ends 'ABENDING PROGRAM', 'CEE3ABD: USER ABEND U+0000000999'; rc.json INTCALC maxrc = 12, STEP15 = 12; no END banner.

**Proven by:** source-only

### BR-INT-047 Normal completion returns RC 0

**Rule:** On the normal path, RETURN-CODE is never set, so GOBACK ends STEP15 with RC 0. rc.json records INTCALC maxrc 0, with the setup step names STEP05..STEP30 merged in, all RC 0. No path in the program produces RC 4 or 8.

**Source:** `app/cbl/CBACT04C.cbl:232-232`

**Fields:** `RETURN-CODE`

**Category:** return-code

**Test:** Given any fixture whose lookups all succeed (including the DEFAULT fallback); When the run completes; Then rc.json shows INTCALC maxrc 0 and STEP15 0.

**Proven by:** `fixtures/interest/i01-baseline`, `fixtures/interest/i02-tie-half-cent`, `fixtures/interest/i03-zero-rate-skipped`, `fixtures/interest/i04-default-group-fallback`, `fixtures/interest/i05-multi-category`, `fixtures/interest/i06-negative-balance`, `fixtures/interest/i07-nontie-rounding`

## Machine-readable rules

```json
{
 "slice": "interest",
 "rules": [
  {
   "id": "BR-INT-001",
   "title": "INTCALC STEP15 runs CBACT04C with PARM='2022071800'",
   "rule": "The INTCALC job has one step, STEP15, which runs CBACT04C with PARM='2022071800'. The program receives the z/OS parameter as EXTERNAL-PARMS (halfword PARM-LENGTH followed by PARM-DATE X(10)). Only PARM-DATE is used. PARM-LENGTH is never checked, so a short or missing PARM is not validated. Off-host, the PARMDRV stand-in rebuilds this structure from the command line.",
   "category": "control-flow",
   "sources": [
    {
     "file": "app/jcl/INTCALC.jcl",
     "startLine": 22,
     "endLine": 22
    },
    {
     "file": "app/cbl/CBACT04C.cbl",
     "startLine": 175,
     "endLine": 180
    },
    {
     "file": "tools/stubs/PARMDRV.cbl",
     "startLine": 19,
     "endLine": 29
    }
   ],
   "fields": [
    "PARM-LENGTH",
    "PARM-DATE"
   ],
   "given": "INTCALC.jcl as shipped (PARM='2022071800')",
   "when": "the job runs",
   "then": "CBACT04C is called with PARM-LENGTH = 10 and PARM-DATE = '2022071800'; the first TRAN-ID starts with '2022071800'",
   "fixtures": [
    "fixtures/interest/i01-baseline"
   ],
   "finding": null
  },
  {
   "id": "BR-INT-002",
   "title": "Start and end banners on SYSOUT",
   "rule": "The program writes 'START OF EXECUTION OF PROGRAM CBACT04C' to SYSOUT first. On a normal finish, after all files are closed, it writes 'END OF EXECUTION OF PROGRAM CBACT04C' and ends with GOBACK. An abending run never prints the end banner.",
   "category": "sysout",
   "sources": [
    {
     "file": "app/cbl/CBACT04C.cbl",
     "startLine": 181,
     "endLine": 181
    },
    {
     "file": "app/cbl/CBACT04C.cbl",
     "startLine": 230,
     "endLine": 232
    }
   ],
   "fields": [],
   "given": "any valid input set",
   "when": "CBACT04C completes normally",
   "then": "SYSOUT line 1 is 'START OF EXECUTION OF PROGRAM CBACT04C' and the last line is 'END OF EXECUTION OF PROGRAM CBACT04C'",
   "fixtures": [
    "fixtures/interest/i01-baseline"
   ],
   "finding": null
  },
  {
   "id": "BR-INT-003",
   "title": "Setup jobs load the four KSDS masters and define the SYSTRAN GDG",
   "rule": "Before INTCALC, the setup jobs run in this order: DEFGDGB, ACCTFILE, XREFFILE, TCATBALF, DISCGRP. DEFGDGB defines the GDG base AWS.M2.CARDDEMO.SYSTRAN (LIMIT 5). Each of the other four jobs deletes, defines and REPROs a KSDS from its .PS file. The keys are: ACCTDATA 11 bytes at offset 0; CARDXREF 16 bytes at offset 0, plus a non-unique AIX on bytes 25-35 (account id) with a PATH; TCATBALF 17 bytes at offset 0; DISCGRP 16 bytes at offset 0. All setup steps end RC 0.",
   "category": "control-flow",
   "sources": [
    {
     "file": "app/jcl/DEFGDGB.jcl",
     "startLine": 48,
     "endLine": 53
    },
    {
     "file": "app/jcl/ACCTFILE.jcl",
     "startLine": 36,
     "endLine": 62
    },
    {
     "file": "app/jcl/XREFFILE.jcl",
     "startLine": 39,
     "endLine": 103
    },
    {
     "file": "app/jcl/TCATBALF.jcl",
     "startLine": 36,
     "endLine": 62
    },
    {
     "file": "app/jcl/DISCGRP.jcl",
     "startLine": 36,
     "endLine": 62
    }
   ],
   "fields": [
    "ACCT-ID",
    "XREF-CARD-NUM",
    "XREF-ACCT-ID",
    "TRAN-CAT-KEY",
    "DIS-GROUP-KEY"
   ],
   "given": "the i01 input .PS files",
   "when": "the setup chain runs",
   "then": "the KSDS files are loaded, and rc.json lists STEP05..STEP30 with RC 0 next to INTCALC STEP15",
   "fixtures": [
    "fixtures/interest/i01-baseline"
   ],
   "finding": null
  },
  {
   "id": "BR-INT-004",
   "title": "File open order and access modes",
   "rule": "Files are opened in this order: TCATBALF (INPUT, sequential), XREFFILE (INPUT, random, read through the alternate key), DISCGRP (INPUT, random), ACCTFILE (I-O, random) and TRANSACT (OUTPUT, sequential). Each open moves 8 to APPL-RESULT first, then 0 if the status is '00' and 12 otherwise.",
   "category": "control-flow",
   "sources": [
    {
     "file": "app/cbl/CBACT04C.cbl",
     "startLine": 27,
     "endLine": 56
    },
    {
     "file": "app/cbl/CBACT04C.cbl",
     "startLine": 182,
     "endLine": 186
    },
    {
     "file": "app/cbl/CBACT04C.cbl",
     "startLine": 234,
     "endLine": 323
    }
   ],
   "fields": [
    "TCATBALF-STATUS",
    "XREFFILE-STATUS",
    "DISCGRP-STATUS",
    "ACCTFILE-STATUS",
    "TRANFILE-STATUS",
    "APPL-RESULT"
   ],
   "given": "all five DDs are allocated",
   "when": "the program starts",
   "then": "all five files open with status '00' and processing begins",
   "fixtures": [
    "fixtures/interest/i01-baseline"
   ],
   "finding": null
  },
  {
   "id": "BR-INT-005",
   "title": "Open failures abend with file-specific messages",
   "rule": "If an open returns a status other than '00', the program displays a message and then the file status (9910-DISPLAY-IO-STATUS) and abends (9999-ABEND-PROGRAM). The messages are: TCATBALF 'ERROR OPENING TRANSACTION CATEGORY BALANCE'; XREFFILE 'ERROR OPENING CROSS REF FILE' followed directly by the raw 2-byte status; DISCGRP 'ERROR OPENING DALY REJECTS FILE'; ACCTFILE 'ERROR OPENING ACCOUNT MASTER FILE'; TRANSACT 'ERROR OPENING TRANSACTION FILE'.",
   "category": "return-code",
   "sources": [
    {
     "file": "app/cbl/CBACT04C.cbl",
     "startLine": 234,
     "endLine": 323
    }
   ],
   "fields": [
    "TCATBALF-STATUS",
    "XREFFILE-STATUS",
    "DISCGRP-STATUS",
    "ACCTFILE-STATUS",
    "TRANFILE-STATUS"
   ],
   "given": "the DISCGRP KSDS is missing (open status '35')",
   "when": "0200-DISCGRP-OPEN runs",
   "then": "SYSOUT shows 'ERROR OPENING DALY REJECTS FILE', 'FILE STATUS IS: NNNN0035', 'ABENDING PROGRAM', and the step abends U0999",
   "fixtures": [],
   "finding": "The DISCGRP open-failure message says 'DALY REJECTS FILE', a copy-paste error from another program. Operators and parity tests depend on this exact text, so it must be reproduced."
  },
  {
   "id": "BR-INT-006",
   "title": "TCATBALF processed in ascending key order",
   "rule": "TRAN-CAT-BAL records are read sequentially from the TCATBALF KSDS. They therefore arrive in ascending key order: TRANCAT-ACCT-ID (9(11)), then TRANCAT-TYPE-CD (X(02)), then TRANCAT-CD (9(04)). All records for one account are adjacent, which the account-break logic relies on.",
   "category": "control-flow",
   "sources": [
    {
     "file": "app/cbl/CBACT04C.cbl",
     "startLine": 28,
     "endLine": 32
    },
    {
     "file": "app/cbl/CBACT04C.cbl",
     "startLine": 61,
     "endLine": 67
    },
    {
     "file": "app/cbl/CBACT04C.cbl",
     "startLine": 325,
     "endLine": 326
    },
    {
     "file": "app/cpy/CVTRA01Y.cpy",
     "startLine": 4,
     "endLine": 10
    },
    {
     "file": "app/jcl/TCATBALF.jcl",
     "startLine": 40,
     "endLine": 40
    }
   ],
   "fields": [
    "TRANCAT-ACCT-ID",
    "TRANCAT-TYPE-CD",
    "TRANCAT-CD"
   ],
   "given": "account 2007 has records 01/0001 (1000.00) and 01/0002 (500.00)",
   "when": "TCATBALF is read",
   "then": "01/0001 is processed first (TRAN-ID ...000001, 10.00), then 01/0002 (TRAN-ID ...000002, 7.50)",
   "fixtures": [
    "fixtures/interest/i05-multi-category"
   ],
   "finding": null
  },
  {
   "id": "BR-INT-007",
   "title": "TCATBALF read status handling",
   "rule": "After each READ of TCATBALF: status '00' sets APPL-RESULT 0 and the record is processed. Status '10' sets APPL-RESULT 16 (APPL-EOF), which sets END-OF-FILE to 'Y'. Any other status displays 'ERROR READING TRANSACTION CATEGORY FILE' and the file status, then abends.",
   "category": "control-flow",
   "sources": [
    {
     "file": "app/cbl/CBACT04C.cbl",
     "startLine": 325,
     "endLine": 348
    }
   ],
   "fields": [
    "TCATBALF-STATUS",
    "APPL-RESULT",
    "END-OF-FILE"
   ],
   "given": "TCATBALF has 2 records",
   "when": "the third READ returns status '10'",
   "then": "END-OF-FILE = 'Y'; nothing is displayed for the EOF read and the main loop ends",
   "fixtures": [
    "fixtures/interest/i01-baseline"
   ],
   "finding": null
  },
  {
   "id": "BR-INT-008",
   "title": "Every TCATBALF record is echoed to SYSOUT",
   "rule": "For each record read (not on EOF), WS-RECORD-COUNT is incremented. The whole 50-byte TRAN-CAT-BAL-RECORD is DISPLAYed as raw text: the 17-byte key, the zoned TRAN-CAT-BAL with its overpunch sign character, and 22 trailing FILLER spaces. WS-RECORD-COUNT is never displayed or used anywhere else.",
   "category": "sysout",
   "sources": [
    {
     "file": "app/cbl/CBACT04C.cbl",
     "startLine": 172,
     "endLine": 172
    },
    {
     "file": "app/cbl/CBACT04C.cbl",
     "startLine": 191,
     "endLine": 193
    }
   ],
   "fields": [
    "TRAN-CAT-BAL-RECORD",
    "WS-RECORD-COUNT"
   ],
   "given": "a TCATBALF record for account 2008, type 01, category 0001, balance -300.00",
   "when": "it is read",
   "then": "SYSOUT shows '000000020080100010000003000}' followed by 22 spaces",
   "fixtures": [
    "fixtures/interest/i06-negative-balance",
    "fixtures/interest/i01-baseline"
   ],
   "finding": null
  },
  {
   "id": "BR-INT-009",
   "title": "Account break detection",
   "rule": "A new account starts when TRANCAT-ACCT-ID differs from WS-LAST-ACCT-NUM (X(11), initially SPACES). On a break: if WS-FIRST-TIME is not 'Y', the previous account is rewritten (1050-UPDATE-ACCOUNT); otherwise WS-FIRST-TIME is set to 'N' and nothing is rewritten. Then WS-TOTAL-INT is set to 0, the new account id is saved, and the account and XREF records are read.",
   "category": "control-flow",
   "sources": [
    {
     "file": "app/cbl/CBACT04C.cbl",
     "startLine": 166,
     "endLine": 170
    },
    {
     "file": "app/cbl/CBACT04C.cbl",
     "startLine": 194,
     "endLine": 206
    }
   ],
   "fields": [
    "TRANCAT-ACCT-ID",
    "WS-LAST-ACCT-NUM",
    "WS-FIRST-TIME",
    "WS-TOTAL-INT"
   ],
   "given": "TCATBALF holds accounts 2001 then 2002",
   "when": "the 2002 record is read",
   "then": "account 2001 is rewritten with its 10.00 interest before 2002 is read; nothing is rewritten when the first record (2001) arrives",
   "fixtures": [
    "fixtures/interest/i01-baseline"
   ],
   "finding": null
  },
  {
   "id": "BR-INT-010",
   "title": "Account master read at account break",
   "rule": "At each account break, ACCOUNT-FILE is read at random with key FD-ACCT-ID = TRANCAT-ACCT-ID, INTO ACCOUNT-RECORD. The record (ACCT-GROUP-ID, ACCT-CURR-BAL, ...) stays in working storage for all categories of that account, and is the record rewritten at the next break.",
   "category": "data-mapping",
   "sources": [
    {
     "file": "app/cbl/CBACT04C.cbl",
     "startLine": 202,
     "endLine": 203
    },
    {
     "file": "app/cbl/CBACT04C.cbl",
     "startLine": 372,
     "endLine": 391
    },
    {
     "file": "app/cpy/CVACT01Y.cpy",
     "startLine": 4,
     "endLine": 17
    }
   ],
   "fields": [
    "FD-ACCT-ID",
    "ACCT-ID",
    "ACCT-GROUP-ID",
    "ACCT-CURR-BAL"
   ],
   "given": "TCATBALF account 2002 and ACCTDATA account 2002 in group STANDARD",
   "when": "the break for 2002 happens",
   "then": "ACCOUNT-RECORD holds account 2002, and its rate lookup uses group 'STANDARD' (18.50 %)",
   "fixtures": [
    "fixtures/interest/i01-baseline"
   ],
   "finding": null
  },
  {
   "id": "BR-INT-011",
   "title": "Missing account abends",
   "rule": "If the account read gets INVALID KEY (status '23'), the program displays 'ACCOUNT NOT FOUND: ' with the 11-digit FD-ACCT-ID, then 'ERROR READING ACCOUNT FILE' and the file status, and abends. Any other non-'00' status takes the same path without the NOT FOUND line. There is no skip-and-continue. Confirmed by an off-host scratch run of the legacy estate (not a golden fixture).",
   "category": "edge-case",
   "sources": [
    {
     "file": "app/cbl/CBACT04C.cbl",
     "startLine": 372,
     "endLine": 391
    }
   ],
   "fields": [
    "FD-ACCT-ID",
    "ACCTFILE-STATUS"
   ],
   "given": "TCATBALF has a record for account 3001, which is not on ACCTDATA",
   "when": "the break for 3001 happens",
   "then": "SYSOUT shows 'ACCOUNT NOT FOUND: 00000003001', 'ERROR READING ACCOUNT FILE', 'FILE STATUS IS: NNNN0023', 'ABENDING PROGRAM'; STEP15 RC 12 off-host (U0999 on z/OS); no SYSTRAN records",
   "fixtures": [],
   "finding": null
  },
  {
   "id": "BR-INT-012",
   "title": "Card number taken from XREF by account id",
   "rule": "At each account break, XREF-FILE is read at random through the alternate key FD-XREF-ACCT-ID = TRANCAT-ACCT-ID, INTO CARD-XREF-RECORD. The AIX is defined NONUNIQUEKEY, so an account with several cards gets the first card returned for that account id. XREF-CARD-NUM becomes TRAN-CARD-NUM on every interest transaction for that account.",
   "category": "data-mapping",
   "sources": [
    {
     "file": "app/cbl/CBACT04C.cbl",
     "startLine": 34,
     "endLine": 39
    },
    {
     "file": "app/cbl/CBACT04C.cbl",
     "startLine": 204,
     "endLine": 205
    },
    {
     "file": "app/cbl/CBACT04C.cbl",
     "startLine": 393,
     "endLine": 398
    },
    {
     "file": "app/cbl/CBACT04C.cbl",
     "startLine": 495,
     "endLine": 495
    },
    {
     "file": "app/jcl/XREFFILE.jcl",
     "startLine": 72,
     "endLine": 80
    },
    {
     "file": "app/cpy/CVACT03Y.cpy",
     "startLine": 4,
     "endLine": 8
    }
   ],
   "fields": [
    "FD-XREF-ACCT-ID",
    "XREF-CARD-NUM",
    "TRAN-CARD-NUM"
   ],
   "given": "CARDXREF maps card 4000000000002001 to account 2001",
   "when": "interest is written for account 2001",
   "then": "TRAN-CARD-NUM = '4000000000002001'",
   "fixtures": [
    "fixtures/interest/i01-baseline"
   ],
   "finding": null
  },
  {
   "id": "BR-INT-013",
   "title": "Missing XREF abends with an 'ACCOUNT NOT FOUND' message",
   "rule": "If the XREF read by account id gets INVALID KEY, the program displays 'ACCOUNT NOT FOUND: ' with FD-XREF-ACCT-ID, then 'ERROR READING XREF FILE' and the file status, and abends. This happens even though the account record itself exists. Confirmed by an off-host scratch run of the legacy estate (not a golden fixture).",
   "category": "edge-case",
   "sources": [
    {
     "file": "app/cbl/CBACT04C.cbl",
     "startLine": 393,
     "endLine": 413
    }
   ],
   "fields": [
    "FD-XREF-ACCT-ID",
    "XREFFILE-STATUS"
   ],
   "given": "account 2001 is on ACCTDATA and TCATBALF but has no CARDXREF row",
   "when": "the break for 2001 happens",
   "then": "SYSOUT shows 'ACCOUNT NOT FOUND: 00000002001', 'ERROR READING XREF FILE', 'FILE STATUS IS: NNNN0023', 'ABENDING PROGRAM'; STEP15 RC 12 off-host",
   "fixtures": [],
   "finding": "When the card cross-reference is missing, the program reports 'ACCOUNT NOT FOUND', which misleads operators because the account does exist. Reproduce the exact text."
  },
  {
   "id": "BR-INT-014",
   "title": "Disclosure-group key built from account group, type and category",
   "rule": "For every TCATBALF record, the DISCGRP key is built as FD-DIS-ACCT-GROUP-ID = ACCT-GROUP-ID (X(10)), FD-DIS-TRAN-TYPE-CD = TRANCAT-TYPE-CD and FD-DIS-TRAN-CAT-CD = TRANCAT-CD. The lookup is repeated for each category record, not once per account.",
   "category": "data-mapping",
   "sources": [
    {
     "file": "app/cbl/CBACT04C.cbl",
     "startLine": 76,
     "endLine": 82
    },
    {
     "file": "app/cbl/CBACT04C.cbl",
     "startLine": 210,
     "endLine": 213
    },
    {
     "file": "app/cpy/CVTRA02Y.cpy",
     "startLine": 4,
     "endLine": 10
    }
   ],
   "fields": [
    "ACCT-GROUP-ID",
    "TRANCAT-TYPE-CD",
    "TRANCAT-CD",
    "FD-DIS-ACCT-GROUP-ID",
    "FD-DIS-TRAN-TYPE-CD",
    "FD-DIS-TRAN-CAT-CD"
   ],
   "given": "account 2002 in group 'STANDARD  ' with a TCATBALF record of type 01, category 0001",
   "when": "the rate is looked up",
   "then": "the key read is 'STANDARD  010001', giving DIS-INT-RATE 18.50",
   "fixtures": [
    "fixtures/interest/i01-baseline"
   ],
   "finding": null
  },
  {
   "id": "BR-INT-015",
   "title": "Interest rate differs by account group",
   "rule": "Two accounts with the same type and category but in different groups get different rates. GOLD 01/0001 is 12.00 % and STANDARD 01/0001 is 18.50 %.",
   "category": "calculation",
   "sources": [
    {
     "file": "app/cbl/CBACT04C.cbl",
     "startLine": 210,
     "endLine": 215
    },
    {
     "file": "app/cbl/CBACT04C.cbl",
     "startLine": 415,
     "endLine": 416
    }
   ],
   "fields": [
    "ACCT-GROUP-ID",
    "DIS-INT-RATE"
   ],
   "given": "account 2001 GOLD with balance 1000.00 and account 2002 STANDARD with balance 2400.00, both 01/0001",
   "when": "interest is computed",
   "then": "TRAN-AMT is 10.00 for 2001 and 37.00 for 2002",
   "fixtures": [
    "fixtures/interest/i01-baseline"
   ],
   "finding": null
  },
  {
   "id": "BR-INT-016",
   "title": "Interest rate differs by transaction category",
   "rule": "Each TCATBALF category record gets its own rate. For GOLD, type 01, category 0001 (purchases) is 12.00 % and category 0002 (cash advances) is 18.00 %.",
   "category": "calculation",
   "sources": [
    {
     "file": "app/cbl/CBACT04C.cbl",
     "startLine": 210,
     "endLine": 215
    },
    {
     "file": "app/cbl/CBACT04C.cbl",
     "startLine": 415,
     "endLine": 416
    }
   ],
   "fields": [
    "TRANCAT-CD",
    "DIS-INT-RATE"
   ],
   "given": "account 2007 GOLD with 01/0001 = 1000.00 and 01/0002 = 500.00",
   "when": "interest is computed",
   "then": "two transactions are written: 10.00 and 7.50",
   "fixtures": [
    "fixtures/interest/i05-multi-category"
   ],
   "finding": null
  },
  {
   "id": "BR-INT-017",
   "title": "DEFAULT disclosure-group fallback",
   "rule": "If the DISCGRP read gets INVALID KEY (status '23'), the program displays 'DISCLOSURE GROUP RECORD MISSING' and 'TRY WITH DEFAULT GROUP CODE'. Status '23' still counts as success (APPL-RESULT 0). FD-DIS-ACCT-GROUP-ID is set to 'DEFAULT' (same type and category) and the record is read again (1200-A-GET-DEFAULT-INT-RATE). The fallback happens for each category record that misses.",
   "category": "control-flow",
   "sources": [
    {
     "file": "app/cbl/CBACT04C.cbl",
     "startLine": 415,
     "endLine": 440
    },
    {
     "file": "app/cbl/CBACT04C.cbl",
     "startLine": 443,
     "endLine": 446
    }
   ],
   "fields": [
    "FD-DIS-ACCT-GROUP-ID",
    "DISCGRP-STATUS",
    "DIS-INT-RATE"
   ],
   "given": "account 2006 in group PLATINUM (no DISCGRP rows) with 01/0001 = 1000.00; DEFAULT 01/0001 = 21.00 %",
   "when": "the rate is looked up",
   "then": "SYSOUT shows the two fallback lines after the record echo, and TRAN-AMT = 17.50",
   "fixtures": [
    "fixtures/interest/i04-default-group-fallback"
   ],
   "finding": null
  },
  {
   "id": "BR-INT-018",
   "title": "Accounts in group DEFAULT match directly",
   "rule": "'DEFAULT' is an ordinary group key. An account whose ACCT-GROUP-ID is 'DEFAULT' finds its row on the first read: no fallback messages, and the DEFAULT rate applies.",
   "category": "control-flow",
   "sources": [
    {
     "file": "app/cbl/CBACT04C.cbl",
     "startLine": 210,
     "endLine": 213
    },
    {
     "file": "app/cbl/CBACT04C.cbl",
     "startLine": 415,
     "endLine": 440
    }
   ],
   "fields": [
    "ACCT-GROUP-ID",
    "DIS-INT-RATE"
   ],
   "given": "account 2010 in group DEFAULT with 01/0001 = 777.77; DEFAULT 01/0001 = 21.00 %",
   "when": "the rate is looked up",
   "then": "no 'DISCLOSURE GROUP RECORD MISSING' line is printed and TRAN-AMT = 13.61",
   "fixtures": [
    "fixtures/interest/i07-nontie-rounding"
   ],
   "finding": null
  },
  {
   "id": "BR-INT-019",
   "title": "Disclosure-group read failures abend",
   "rule": "A first DISCGRP read with a status other than '00' or '23' displays 'ERROR READING DISCLOSURE GROUP FILE' and abends. If the DEFAULT row is also missing (the second read does not return '00'), the program displays 'ERROR READING DEFAULT DISCLOSURE GROUP' and the file status, and abends. Confirmed by an off-host scratch run of the legacy estate (not a golden fixture).",
   "category": "return-code",
   "sources": [
    {
     "file": "app/cbl/CBACT04C.cbl",
     "startLine": 422,
     "endLine": 435
    },
    {
     "file": "app/cbl/CBACT04C.cbl",
     "startLine": 443,
     "endLine": 460
    }
   ],
   "fields": [
    "DISCGRP-STATUS"
   ],
   "given": "account 2001 in group PLATINUM with a TCATBALF record 01/0003; there is no PLATINUM or DEFAULT 01/0003 row",
   "when": "the rate is looked up",
   "then": "SYSOUT shows 'DISCLOSURE GROUP RECORD MISSING', 'TRY WITH DEFAULT GROUP CODE', 'ERROR READING DEFAULT DISCLOSURE GROUP', 'FILE STATUS IS: NNNN0023', 'ABENDING PROGRAM'; STEP15 RC 12 off-host",
   "fixtures": [],
   "finding": null
  },
  {
   "id": "BR-INT-020",
   "title": "Zero-rate categories are skipped",
   "rule": "If DIS-INT-RATE = 0, the program skips 1300-COMPUTE-INTEREST and 1400-COMPUTE-FEES. No transaction is written, WS-TRANID-SUFFIX does not advance, and WS-TOTAL-INT does not change. The record is still echoed and still counts as part of its account.",
   "category": "validation",
   "sources": [
    {
     "file": "app/cbl/CBACT04C.cbl",
     "startLine": 214,
     "endLine": 217
    }
   ],
   "fields": [
    "DIS-INT-RATE",
    "WS-TRANID-SUFFIX"
   ],
   "given": "account 2005 GOLD with 01/0001 = 1000.00 (12.00 %) and 02/0001 = -300.00 (0.00 %)",
   "when": "the run completes",
   "then": "exactly one SYSTRAN record (TRAN-ID 2022071800000001, 10.00) is written",
   "fixtures": [
    "fixtures/interest/i03-zero-rate-skipped"
   ],
   "finding": null
  },
  {
   "id": "BR-INT-021",
   "title": "Monthly interest formula",
   "rule": "For each category with a non-zero rate: WS-MONTHLY-INT = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200. This is the annual percentage rate divided by 12 months and by 100. TRAN-CAT-BAL is S9(09)V99, DIS-INT-RATE is S9(04)V99 and the result is S9(09)V99.",
   "category": "calculation",
   "sources": [
    {
     "file": "app/cbl/CBACT04C.cbl",
     "startLine": 168,
     "endLine": 168
    },
    {
     "file": "app/cbl/CBACT04C.cbl",
     "startLine": 462,
     "endLine": 466
    },
    {
     "file": "app/cpy/CVTRA01Y.cpy",
     "startLine": 9,
     "endLine": 9
    },
    {
     "file": "app/cpy/CVTRA02Y.cpy",
     "startLine": 9,
     "endLine": 9
    }
   ],
   "fields": [
    "TRAN-CAT-BAL",
    "DIS-INT-RATE",
    "WS-MONTHLY-INT"
   ],
   "given": "TRAN-CAT-BAL = 2400.00 and DIS-INT-RATE = 18.50",
   "when": "1300-COMPUTE-INTEREST runs",
   "then": "WS-MONTHLY-INT = 37.00",
   "fixtures": [
    "fixtures/interest/i01-baseline"
   ],
   "finding": null
  },
  {
   "id": "BR-INT-022",
   "title": "Interest is truncated, not rounded (half cent)",
   "rule": "The COMPUTE has no ROUNDED phrase, so digits beyond 2 decimals are dropped (truncated toward zero). An exact half cent is truncated down. A half-even reference (tools/parity/naive_ref.py) gives a different result only on such ties.",
   "category": "rounding",
   "sources": [
    {
     "file": "app/cbl/CBACT04C.cbl",
     "startLine": 464,
     "endLine": 465
    }
   ],
   "fields": [
    "WS-MONTHLY-INT",
    "TRAN-AMT"
   ],
   "given": "account 2003 balance 1235.50 at 12.00 % (12.355) and account 2004 balance 1234.50 at 12.00 % (12.345)",
   "when": "interest is computed",
   "then": "TRAN-AMT = 12.35 for 2003 (half-even would give 12.36) and 12.34 for 2004",
   "fixtures": [
    "fixtures/interest/i02-tie-half-cent"
   ],
   "finding": "Interest is truncated on every computation, so the bank under-charges up to 0.01 per category per month. This differs from both half-up and banker's rounding. Reproduce it with RoundingMode.DOWN."
  },
  {
   "id": "BR-INT-023",
   "title": "Non-tie sub-cent remainders are truncated",
   "rule": "Remainders that are not ties are also dropped. In these cases truncation agrees with half-even and half-up rounding.",
   "category": "rounding",
   "sources": [
    {
     "file": "app/cbl/CBACT04C.cbl",
     "startLine": 464,
     "endLine": 465
    }
   ],
   "fields": [
    "WS-MONTHLY-INT",
    "TRAN-AMT"
   ],
   "given": "account 2009 1000.33 at 18.50 % (15.42175) and account 2010 777.77 at 21.00 % (13.610975)",
   "when": "interest is computed",
   "then": "TRAN-AMT = 15.42 and 13.61",
   "fixtures": [
    "fixtures/interest/i07-nontie-rounding"
   ],
   "finding": null
  },
  {
   "id": "BR-INT-024",
   "title": "Negative balances produce negative interest",
   "rule": "A credit (negative) TRAN-CAT-BAL is not filtered out. It gives a negative WS-MONTHLY-INT, a negative interest transaction, and a lower total for the account. Truncation is toward zero, so a negative half cent -12.355 becomes -12.35 (not -12.36). The negative-tie case was confirmed by an off-host scratch run.",
   "category": "edge-case",
   "sources": [
    {
     "file": "app/cbl/CBACT04C.cbl",
     "startLine": 214,
     "endLine": 215
    },
    {
     "file": "app/cbl/CBACT04C.cbl",
     "startLine": 464,
     "endLine": 467
    },
    {
     "file": "app/cbl/CBACT04C.cbl",
     "startLine": 490,
     "endLine": 490
    }
   ],
   "fields": [
    "TRAN-CAT-BAL",
    "WS-MONTHLY-INT",
    "TRAN-AMT"
   ],
   "given": "account 2008 GOLD with 01/0001 = -300.00 at 12.00 %",
   "when": "interest is computed",
   "then": "a SYSTRAN record with TRAN-AMT = -3.00 (zoned '0000000030}') is written",
   "fixtures": [
    "fixtures/interest/i06-negative-balance"
   ],
   "finding": "The program charges negative interest (a credit to the customer) on credit balances, and nothing in the program prevents it. This must be reproduced."
  },
  {
   "id": "BR-INT-025",
   "title": "Zoned-decimal signs, not COMP-3",
   "rule": "All amounts in this slice (TRAN-CAT-BAL, DIS-INT-RATE, ACCT-CURR-BAL, ACCT-CURR-CYC-CREDIT/DEBIT, TRAN-AMT) are signed zoned DISPLAY fields with a trailing overpunch sign. No COMP-3 is used. The build uses -fsign=EBCDIC, so the last byte reads '{' = +0, 'A'..'I' = +1..+9, '}' = -0, 'J'..'R' = -1..-9. A Java port must encode and decode these signs byte for byte.",
   "category": "data-mapping",
   "sources": [
    {
     "file": "app/cpy/CVTRA01Y.cpy",
     "startLine": 9,
     "endLine": 9
    },
    {
     "file": "app/cpy/CVTRA02Y.cpy",
     "startLine": 9,
     "endLine": 9
    },
    {
     "file": "app/cpy/CVACT01Y.cpy",
     "startLine": 7,
     "endLine": 14
    },
    {
     "file": "app/cpy/CVTRA05Y.cpy",
     "startLine": 10,
     "endLine": 10
    },
    {
     "file": "tools/build.sh",
     "startLine": 16,
     "endLine": 16
    }
   ],
   "fields": [
    "TRAN-CAT-BAL",
    "DIS-INT-RATE",
    "ACCT-CURR-BAL",
    "TRAN-AMT"
   ],
   "given": "an account balance of 1247.85 and an interest amount of 15.42",
   "when": "the records are written",
   "then": "ACCT-CURR-BAL is stored as '00000012478E' and TRAN-AMT as '0000000154B'",
   "fixtures": [
    "fixtures/interest/i02-tie-half-cent",
    "fixtures/interest/i07-nontie-rounding",
    "fixtures/interest/i06-negative-balance"
   ],
   "finding": null
  },
  {
   "id": "BR-INT-026",
   "title": "Account interest total is the sum of truncated category amounts",
   "rule": "WS-TOTAL-INT is set to 0 at each account break. Each category's already-truncated WS-MONTHLY-INT is added to it. The total is therefore a sum of truncated values, not one truncation of the combined amount.",
   "category": "calculation",
   "sources": [
    {
     "file": "app/cbl/CBACT04C.cbl",
     "startLine": 169,
     "endLine": 169
    },
    {
     "file": "app/cbl/CBACT04C.cbl",
     "startLine": 200,
     "endLine": 200
    },
    {
     "file": "app/cbl/CBACT04C.cbl",
     "startLine": 467,
     "endLine": 467
    }
   ],
   "fields": [
    "WS-TOTAL-INT",
    "WS-MONTHLY-INT"
   ],
   "given": "account 2009 (15.42175 -> 15.42) followed by account 2010",
   "when": "the break for 2010 happens",
   "then": "WS-TOTAL-INT for 2009 is 15.42 and ACCT-CURR-BAL becomes 1000.33 + 15.42 = 1015.75",
   "fixtures": [
    "fixtures/interest/i07-nontie-rounding",
    "fixtures/interest/i05-multi-category"
   ],
   "finding": null
  },
  {
   "id": "BR-INT-027",
   "title": "Arithmetic overflow drops high-order digits silently",
   "rule": "The COMPUTE into WS-MONTHLY-INT (S9(09)V99) and the ADD into ACCT-CURR-BAL (S9(10)V99) have no ON SIZE ERROR. A result too large for the field loses its leading digits without any message, and the return code stays 0. Confirmed by an off-host scratch run of the legacy estate (not a golden fixture).",
   "category": "edge-case",
   "sources": [
    {
     "file": "app/cbl/CBACT04C.cbl",
     "startLine": 168,
     "endLine": 169
    },
    {
     "file": "app/cbl/CBACT04C.cbl",
     "startLine": 352,
     "endLine": 352
    },
    {
     "file": "app/cbl/CBACT04C.cbl",
     "startLine": 464,
     "endLine": 467
    }
   ],
   "fields": [
    "WS-MONTHLY-INT",
    "WS-TOTAL-INT",
    "ACCT-CURR-BAL"
   ],
   "given": "TRAN-CAT-BAL = 999999999.99, DIS-INT-RATE = 9999.99 (true result 8333324999.91), ACCT-CURR-BAL = 9999999999.99, and the account is not the last one",
   "when": "the run completes",
   "then": "TRAN-AMT = 333324999.91 and ACCT-CURR-BAL = 333324999.90 (high-order digits lost); STEP15 RC 0",
   "fixtures": [],
   "finding": "Overflow is silent and corrupts both the transaction amount and the balance. Realistic rates cannot reach it, but a converted program must truncate the same way and must not raise an error."
  },
  {
   "id": "BR-INT-028",
   "title": "1050-UPDATE-ACCOUNT adds total interest to ACCT-CURR-BAL",
   "rule": "When an account is closed off, WS-TOTAL-INT is added to ACCT-CURR-BAL in ACCOUNT-RECORD, and the record is rewritten to ACCTFILE.",
   "category": "state-change",
   "sources": [
    {
     "file": "app/cbl/CBACT04C.cbl",
     "startLine": 350,
     "endLine": 356
    }
   ],
   "fields": [
    "ACCT-CURR-BAL",
    "WS-TOTAL-INT"
   ],
   "given": "account 2003 with ACCT-CURR-BAL 1235.50 and interest 12.35, followed by account 2004",
   "when": "the break for 2004 happens",
   "then": "account 2003 is rewritten with ACCT-CURR-BAL = 1247.85",
   "fixtures": [
    "fixtures/interest/i02-tie-half-cent",
    "fixtures/interest/i01-baseline"
   ],
   "finding": null
  },
  {
   "id": "BR-INT-029",
   "title": "1050-UPDATE-ACCOUNT zeroes the cycle credit and debit",
   "rule": "The same rewrite sets ACCT-CURR-CYC-CREDIT and ACCT-CURR-CYC-DEBIT to 0, which closes the billing cycle.",
   "category": "state-change",
   "sources": [
    {
     "file": "app/cbl/CBACT04C.cbl",
     "startLine": 353,
     "endLine": 354
    }
   ],
   "fields": [
    "ACCT-CURR-CYC-CREDIT",
    "ACCT-CURR-CYC-DEBIT"
   ],
   "given": "account 2001 with ACCT-CURR-CYC-CREDIT 1000.00, followed by account 2002",
   "when": "the break for 2002 happens",
   "then": "account 2001 is rewritten with ACCT-CURR-CYC-CREDIT = 0.00 and ACCT-CURR-CYC-DEBIT = 0.00",
   "fixtures": [
    "fixtures/interest/i01-baseline",
    "fixtures/interest/i07-nontie-rounding"
   ],
   "finding": null
  },
  {
   "id": "BR-INT-030",
   "title": "Accounts with only zero-rate categories are still rewritten",
   "rule": "An account whose categories are all zero-rate still goes through 1050-UPDATE-ACCOUNT at the next break, as long as it is not the last account. 0.00 is added to the balance (no change), the cycle fields are zeroed, and the record is rewritten. No SYSTRAN record is written for it. Confirmed by an off-host scratch run of the legacy estate (not a golden fixture).",
   "category": "edge-case",
   "sources": [
    {
     "file": "app/cbl/CBACT04C.cbl",
     "startLine": 194,
     "endLine": 200
    },
    {
     "file": "app/cbl/CBACT04C.cbl",
     "startLine": 214,
     "endLine": 217
    },
    {
     "file": "app/cbl/CBACT04C.cbl",
     "startLine": 350,
     "endLine": 356
    }
   ],
   "fields": [
    "ACCT-CURR-BAL",
    "ACCT-CURR-CYC-CREDIT",
    "ACCT-CURR-CYC-DEBIT"
   ],
   "given": "account 2001 with only 02/0001 = 1000.00 (0.00 %), cycle credit 1000.00 and cycle debit 300.00, followed by account 2002",
   "when": "the run completes",
   "then": "account 2001 keeps ACCT-CURR-BAL 1000.00 but has cycle credit and debit 0.00; the first SYSTRAN record belongs to account 2002",
   "fixtures": [],
   "finding": null
  },
  {
   "id": "BR-INT-031",
   "title": "Other account fields are preserved on rewrite; untouched accounts unchanged",
   "rule": "REWRITE FD-ACCTFILE-REC FROM ACCOUNT-RECORD writes back the full 300-byte record as read. ACCT-ID, ACCT-ACTIVE-STATUS, the credit limits, the open, expiry and reissue dates, ACCT-ADDR-ZIP, ACCT-GROUP-ID and FILLER are unchanged. Accounts with no TCATBALF records are never read or rewritten.",
   "category": "data-mapping",
   "sources": [
    {
     "file": "app/cbl/CBACT04C.cbl",
     "startLine": 84,
     "endLine": 87
    },
    {
     "file": "app/cbl/CBACT04C.cbl",
     "startLine": 356,
     "endLine": 356
    },
    {
     "file": "app/cpy/CVACT01Y.cpy",
     "startLine": 4,
     "endLine": 17
    }
   ],
   "fields": [
    "ACCOUNT-RECORD",
    "ACCT-CREDIT-LIMIT",
    "ACCT-OPEN-DATE",
    "ACCT-GROUP-ID"
   ],
   "given": "account 2009 with credit limit 5000.00, open date 2019-03-15, zip 10115, group STANDARD",
   "when": "it is rewritten with interest",
   "then": "only ACCT-CURR-BAL and the two cycle fields differ from the input record",
   "fixtures": [
    "fixtures/interest/i07-nontie-rounding"
   ],
   "finding": null
  },
  {
   "id": "BR-INT-032",
   "title": "No eligibility checks on account status or dates",
   "rule": "The program never reads ACCT-ACTIVE-STATUS, ACCT-EXPIRAION-DATE or ACCT-REISSUE-DATE, and it does not validate dates (blank or zero dates pass through untouched). Inactive and expired accounts are charged interest like any other account. Confirmed by an off-host scratch run of the legacy estate (not a golden fixture).",
   "category": "validation",
   "sources": [
    {
     "file": "app/cbl/CBACT04C.cbl",
     "startLine": 188,
     "endLine": 222
    },
    {
     "file": "app/cpy/CVACT01Y.cpy",
     "startLine": 6,
     "endLine": 12
    }
   ],
   "fields": [
    "ACCT-ACTIVE-STATUS",
    "ACCT-EXPIRAION-DATE"
   ],
   "given": "account 2002 with ACCT-ACTIVE-STATUS 'N', balance 1000.00, 01/0001 = -300.00 at 12.00 %, followed by account 2003",
   "when": "the run completes",
   "then": "account 2002 gets a -3.00 transaction and is rewritten with ACCT-CURR-BAL 997.00 and cycle fields 0.00",
   "fixtures": [],
   "finding": "Inactive and expired accounts accrue interest, because no status or date check exists. This must be reproduced."
  },
  {
   "id": "BR-INT-033",
   "title": "Rewrite failure abends",
   "rule": "If REWRITE of ACCTFILE returns a status other than '00', the program displays 'ERROR RE-WRITING ACCOUNT FILE' and the file status, and abends.",
   "category": "return-code",
   "sources": [
    {
     "file": "app/cbl/CBACT04C.cbl",
     "startLine": 356,
     "endLine": 369
    }
   ],
   "fields": [
    "ACCTFILE-STATUS"
   ],
   "given": "the ACCTFILE REWRITE returns status '22'",
   "when": "1050-UPDATE-ACCOUNT runs",
   "then": "SYSOUT shows 'ERROR RE-WRITING ACCOUNT FILE', 'FILE STATUS IS: NNNN0022', 'ABENDING PROGRAM'; the step abends",
   "fixtures": [],
   "finding": null
  },
  {
   "id": "BR-INT-034",
   "title": "End-of-file quirk: the last account on TCATBALF is never updated",
   "rule": "END-OF-FILE is set to 'Y' inside the loop body, on the READ that hits EOF. The inner IF then fails, and PERFORM UNTIL END-OF-FILE = 'Y' ends the loop before the outer ELSE branch (PERFORM 1050-UPDATE-ACCOUNT) can run. The highest account on TCATBALF therefore gets its interest transactions written, but its ACCT-CURR-BAL and cycle fields are never rewritten.",
   "category": "control-flow",
   "sources": [
    {
     "file": "app/cbl/CBACT04C.cbl",
     "startLine": 188,
     "endLine": 222
    }
   ],
   "fields": [
    "END-OF-FILE",
    "ACCT-CURR-BAL",
    "ACCT-CURR-CYC-CREDIT",
    "ACCT-CURR-CYC-DEBIT"
   ],
   "given": "accounts 2001 and 2002 with interest 10.00 and 37.00",
   "when": "the run completes",
   "then": "2001 becomes 1010.00 with cycle fields 0.00; 2002 stays at 2400.00 with cycle credit 2400.00, even though its 37.00 transaction is on SYSTRAN",
   "fixtures": [
    "fixtures/interest/i01-baseline",
    "fixtures/interest/i02-tie-half-cent",
    "fixtures/interest/i07-nontie-rounding"
   ],
   "finding": "Interest is posted to SYSTRAN for the last account but never added to its balance, which leaves the books out of balance. This is the documented legacy quirk (docs/BASELINE.md) and must be reproduced. A Java port must not add a final update."
  },
  {
   "id": "BR-INT-035",
   "title": "Single-account runs update no account at all",
   "rule": "Because of the end-of-file quirk, when TCATBALF holds one account only, ACCTDATA comes out byte-identical to the input, while SYSTRAN still holds the interest transactions. The fixture READMEs for i05 and i06 say the balance grows or shrinks. The golden expected/ files show it unchanged, and the golden files are authoritative.",
   "category": "edge-case",
   "sources": [
    {
     "file": "app/cbl/CBACT04C.cbl",
     "startLine": 188,
     "endLine": 222
    }
   ],
   "fields": [
    "ACCT-CURR-BAL"
   ],
   "given": "account 2007 alone, with interest 10.00 + 7.50",
   "when": "the run completes",
   "then": "two SYSTRAN records are written, but ACCT-CURR-BAL stays 1500.00 and cycle credit stays 1500.00",
   "fixtures": [
    "fixtures/interest/i03-zero-rate-skipped",
    "fixtures/interest/i04-default-group-fallback",
    "fixtures/interest/i05-multi-category",
    "fixtures/interest/i06-negative-balance"
   ],
   "finding": "The i05 and i06 READMEs say the account balance changes, but the golden files (and the legacy code) leave it unchanged. Reproduce the golden behaviour."
  },
  {
   "id": "BR-INT-036",
   "title": "TRAN-ID = PARM-DATE + run-wide 6-digit sequence",
   "rule": "Before each interest transaction, WS-TRANID-SUFFIX (9(06), initially 0) is incremented. TRAN-ID (X(16)) is then built with STRING from PARM-DATE (10 bytes) and WS-TRANID-SUFFIX (6 digits), DELIMITED BY SIZE. The suffix runs across the whole job: it is not reset per account and skips nothing, because zero-rate categories never increment it.",
   "category": "data-mapping",
   "sources": [
    {
     "file": "app/cbl/CBACT04C.cbl",
     "startLine": 173,
     "endLine": 173
    },
    {
     "file": "app/cbl/CBACT04C.cbl",
     "startLine": 474,
     "endLine": 480
    }
   ],
   "fields": [
    "TRAN-ID",
    "PARM-DATE",
    "WS-TRANID-SUFFIX"
   ],
   "given": "PARM '2022071800' with interest for accounts 2001 and 2002",
   "when": "transactions are written",
   "then": "the TRAN-IDs are '2022071800000001' (2001) and '2022071800000002' (2002)",
   "fixtures": [
    "fixtures/interest/i01-baseline",
    "fixtures/interest/i03-zero-rate-skipped"
   ],
   "finding": null
  },
  {
   "id": "BR-INT-037",
   "title": "Fixed SYSTRAN codes and blank merchant fields",
   "rule": "Every interest transaction has TRAN-TYPE-CD '01', TRAN-CAT-CD '05' (moved into 9(04), so stored as 0005), TRAN-SOURCE 'System' (space-padded to 10), TRAN-MERCHANT-ID 0, and TRAN-MERCHANT-NAME, CITY and ZIP all spaces.",
   "category": "data-mapping",
   "sources": [
    {
     "file": "app/cbl/CBACT04C.cbl",
     "startLine": 482,
     "endLine": 484
    },
    {
     "file": "app/cbl/CBACT04C.cbl",
     "startLine": 491,
     "endLine": 494
    },
    {
     "file": "app/cpy/CVTRA05Y.cpy",
     "startLine": 4,
     "endLine": 18
    }
   ],
   "fields": [
    "TRAN-TYPE-CD",
    "TRAN-CAT-CD",
    "TRAN-SOURCE",
    "TRAN-MERCHANT-ID",
    "TRAN-MERCHANT-NAME",
    "TRAN-MERCHANT-CITY",
    "TRAN-MERCHANT-ZIP"
   ],
   "given": "any interest transaction",
   "when": "it is written",
   "then": "bytes 17-32 are '010005System    ' and TRAN-MERCHANT-ID is '000000000'",
   "fixtures": [
    "fixtures/interest/i01-baseline"
   ],
   "finding": null
  },
  {
   "id": "BR-INT-038",
   "title": "TRAN-DESC = 'Int. for a/c ' + 11-digit account id",
   "rule": "TRAN-DESC (X(100)) is built with STRING from the literal 'Int. for a/c ' (with its trailing space) and ACCT-ID (11 zero-padded digits from the current ACCOUNT-RECORD), and is space-padded.",
   "category": "data-mapping",
   "sources": [
    {
     "file": "app/cbl/CBACT04C.cbl",
     "startLine": 485,
     "endLine": 489
    }
   ],
   "fields": [
    "TRAN-DESC",
    "ACCT-ID"
   ],
   "given": "account 2001",
   "when": "its interest transaction is written",
   "then": "TRAN-DESC = 'Int. for a/c 00000002001' followed by spaces",
   "fixtures": [
    "fixtures/interest/i01-baseline"
   ],
   "finding": null
  },
  {
   "id": "BR-INT-039",
   "title": "TRAN-AMT is the per-category monthly interest",
   "rule": "TRAN-AMT is WS-MONTHLY-INT for that one category, not the account total. An account with several charged categories gets one transaction per category.",
   "category": "data-mapping",
   "sources": [
    {
     "file": "app/cbl/CBACT04C.cbl",
     "startLine": 467,
     "endLine": 468
    },
    {
     "file": "app/cbl/CBACT04C.cbl",
     "startLine": 490,
     "endLine": 490
    }
   ],
   "fields": [
    "TRAN-AMT",
    "WS-MONTHLY-INT"
   ],
   "given": "account 2007 with category interests 10.00 and 7.50",
   "when": "transactions are written",
   "then": "there are two SYSTRAN records, with TRAN-AMT 10.00 and 7.50 (not one record of 17.50)",
   "fixtures": [
    "fixtures/interest/i05-multi-category"
   ],
   "finding": null
  },
  {
   "id": "BR-INT-040",
   "title": "PROC-TS and ORIG-TS come from the frozen clock",
   "rule": "Z-GET-DB2-FORMAT-TIMESTAMP formats FUNCTION CURRENT-DATE as 'YYYY-MM-DD-HH.MM.SS.hh0000': the hundredths of a second followed by the literal '0000'. Both TRAN-ORIG-TS and TRAN-PROC-TS get the same value. Off-host the chain clock is frozen at 2022-07-05 10:00:00.00. The timestamp does not come from PARM-DATE.",
   "category": "data-mapping",
   "sources": [
    {
     "file": "app/cbl/CBACT04C.cbl",
     "startLine": 140,
     "endLine": 165
    },
    {
     "file": "app/cbl/CBACT04C.cbl",
     "startLine": 496,
     "endLine": 498
    },
    {
     "file": "app/cbl/CBACT04C.cbl",
     "startLine": 613,
     "endLine": 626
    },
    {
     "file": "tools/runjcl/chains.json",
     "startLine": 45,
     "endLine": 45
    }
   ],
   "fields": [
    "TRAN-ORIG-TS",
    "TRAN-PROC-TS",
    "DB2-FORMAT-TS"
   ],
   "given": "the frozen clock 2022-07-05 10:00:00.00",
   "when": "any interest transaction is written",
   "then": "TRAN-ORIG-TS = TRAN-PROC-TS = '2022-07-05-10.00.00.000000'",
   "fixtures": [
    "fixtures/interest/i01-baseline"
   ],
   "finding": null
  },
  {
   "id": "BR-INT-041",
   "title": "SYSTRAN is a new 350-byte GDG generation",
   "rule": "TRANSACT is opened OUTPUT on AWS.M2.CARDDEMO.SYSTRAN(+1) (RECFM=F, LRECL=350, DISP=(NEW,CATLG,DELETE)). Each WRITE FD-TRANFILE-REC FROM TRAN-RECORD must return '00'; otherwise the program displays 'ERROR WRITING TRANSACTION RECORD' and the file status, and abends. Off-host the first generation is G0001V00.",
   "category": "state-change",
   "sources": [
    {
     "file": "app/jcl/INTCALC.jcl",
     "startLine": 37,
     "endLine": 41
    },
    {
     "file": "app/cbl/CBACT04C.cbl",
     "startLine": 89,
     "endLine": 92
    },
    {
     "file": "app/cbl/CBACT04C.cbl",
     "startLine": 500,
     "endLine": 515
    }
   ],
   "fields": [
    "TRAN-RECORD",
    "TRANFILE-STATUS"
   ],
   "given": "the i01 inputs",
   "when": "the run completes",
   "then": "AWS.M2.CARDDEMO.SYSTRAN.G0001V00 holds 2 records of 350 bytes (manifest lrecl 350)",
   "fixtures": [
    "fixtures/interest/i01-baseline"
   ],
   "finding": null
  },
  {
   "id": "BR-INT-042",
   "title": "Fee computation is an empty stub",
   "rule": "1400-COMPUTE-FEES is performed for every charged category, but its body is only the comment '* To be implemented' followed by EXIT. No fee transactions are written and no balance changes come from fees.",
   "category": "edge-case",
   "sources": [
    {
     "file": "app/cbl/CBACT04C.cbl",
     "startLine": 216,
     "endLine": 216
    },
    {
     "file": "app/cbl/CBACT04C.cbl",
     "startLine": 518,
     "endLine": 520
    },
    {
     "file": "app/jcl/INTCALC.jcl",
     "startLine": 20,
     "endLine": 20
    }
   ],
   "fields": [],
   "given": "any category with a non-zero rate",
   "when": "1400-COMPUTE-FEES runs",
   "then": "nothing happens: SYSTRAN holds only interest records (type 01, category 0005)",
   "fixtures": [
    "fixtures/interest/i05-multi-category"
   ],
   "finding": "The JCL comment says the step will 'compute interest and fees', but fees are never charged. A converted program must not add fee logic."
  },
  {
   "id": "BR-INT-043",
   "title": "Empty TCATBALF produces an empty run with RC 0",
   "rule": "If the first TCATBALF READ returns EOF, no record is echoed, no account is read or rewritten, and SYSTRAN is created with 0 records. SYSOUT contains only the start and end banners, and the step ends RC 0. Confirmed by an off-host scratch run of the legacy estate (not a golden fixture).",
   "category": "edge-case",
   "sources": [
    {
     "file": "app/cbl/CBACT04C.cbl",
     "startLine": 188,
     "endLine": 222
    }
   ],
   "fields": [
    "END-OF-FILE"
   ],
   "given": "an empty TCATBALF.PS and account 2001 on ACCTDATA",
   "when": "the run completes",
   "then": "SYSOUT is exactly the 2 banner lines; ACCTDATA is unchanged; SYSTRAN.G0001V00 has 0 records; rc.json STEP15 = 0",
   "fixtures": [],
   "finding": null
  },
  {
   "id": "BR-INT-044",
   "title": "Close order and close failures",
   "rule": "After the loop, files are closed in the same order they were opened (TCATBALF, XREF, DISCGRP, ACCTFILE, TRANSACT). A close status other than '00' displays one of: 'ERROR CLOSING TRANSACTION BALANCE FILE', 'ERROR CLOSING CROSS REF FILE', 'ERROR CLOSING DISCLOSURE GROUP FILE', 'ERROR CLOSING ACCOUNT FILE' or 'ERROR CLOSING TRANSACTION FILE', then the file status, and abends.",
   "category": "return-code",
   "sources": [
    {
     "file": "app/cbl/CBACT04C.cbl",
     "startLine": 224,
     "endLine": 228
    },
    {
     "file": "app/cbl/CBACT04C.cbl",
     "startLine": 522,
     "endLine": 611
    }
   ],
   "fields": [
    "TCATBALF-STATUS",
    "XREFFILE-STATUS",
    "DISCGRP-STATUS",
    "ACCTFILE-STATUS",
    "TRANFILE-STATUS"
   ],
   "given": "a normal run",
   "when": "the closes run",
   "then": "all five closes return '00' and the end banner follows",
   "fixtures": [
    "fixtures/interest/i01-baseline"
   ],
   "finding": null
  },
  {
   "id": "BR-INT-045",
   "title": "File-status display format (9910-DISPLAY-IO-STATUS)",
   "rule": "Every error path displays 'FILE STATUS IS: NNNN' followed by a 4-character IO-STATUS-04. If the status is numeric and does not start with '9', the value is '00' plus the 2-digit status (e.g. '0023'). If the status is not numeric or starts with '9', the value is the first status byte plus the binary value of the second byte as 3 digits.",
   "category": "sysout",
   "sources": [
    {
     "file": "app/cbl/CBACT04C.cbl",
     "startLine": 122,
     "endLine": 131
    },
    {
     "file": "app/cbl/CBACT04C.cbl",
     "startLine": 635,
     "endLine": 648
    }
   ],
   "fields": [
    "IO-STATUS",
    "IO-STATUS-04",
    "TWO-BYTES-BINARY"
   ],
   "given": "an account read that fails with status '23'",
   "when": "9910-DISPLAY-IO-STATUS runs",
   "then": "SYSOUT shows 'FILE STATUS IS: NNNN0023' (the literal 'NNNN' is printed as-is)",
   "fixtures": [],
   "finding": null
  },
  {
   "id": "BR-INT-046",
   "title": "Abend path: U0999 via CEE3ABD; no rollback",
   "rule": "9999-ABEND-PROGRAM displays 'ABENDING PROGRAM' and calls CEE3ABD with ABCODE 999 and TIMING 0: user abend U0999 on z/OS. Off-host, the CEE3ABD stand-in also displays 'CEE3ABD: USER ABEND U+0000000999' and ends the step with RC 12. GnuCOBOL then adds 'implicit CLOSE' warnings, which are runtime noise and not program output. Accounts rewritten before the abend stay rewritten; nothing is rolled back. On z/OS, DISP=(NEW,CATLG,DELETE) deletes the new SYSTRAN generation on abend.",
   "category": "return-code",
   "sources": [
    {
     "file": "app/cbl/CBACT04C.cbl",
     "startLine": 138,
     "endLine": 139
    },
    {
     "file": "app/cbl/CBACT04C.cbl",
     "startLine": 628,
     "endLine": 632
    },
    {
     "file": "tools/stubs/CEE3ABD.cbl",
     "startLine": 15,
     "endLine": 18
    },
    {
     "file": "app/jcl/INTCALC.jcl",
     "startLine": 37,
     "endLine": 37
    }
   ],
   "fields": [
    "ABCODE",
    "TIMING",
    "RETURN-CODE"
   ],
   "given": "a missing account 3001 on TCATBALF",
   "when": "the account read fails",
   "then": "SYSOUT ends 'ABENDING PROGRAM', 'CEE3ABD: USER ABEND U+0000000999'; rc.json INTCALC maxrc = 12, STEP15 = 12; no END banner",
   "fixtures": [],
   "finding": null
  },
  {
   "id": "BR-INT-047",
   "title": "Normal completion returns RC 0",
   "rule": "On the normal path, RETURN-CODE is never set, so GOBACK ends STEP15 with RC 0. rc.json records INTCALC maxrc 0, with the setup step names STEP05..STEP30 merged in, all RC 0. No path in the program produces RC 4 or 8.",
   "category": "return-code",
   "sources": [
    {
     "file": "app/cbl/CBACT04C.cbl",
     "startLine": 232,
     "endLine": 232
    }
   ],
   "fields": [
    "RETURN-CODE"
   ],
   "given": "any fixture whose lookups all succeed (including the DEFAULT fallback)",
   "when": "the run completes",
   "then": "rc.json shows INTCALC maxrc 0 and STEP15 0",
   "fixtures": [
    "fixtures/interest/i01-baseline",
    "fixtures/interest/i02-tie-half-cent",
    "fixtures/interest/i03-zero-rate-skipped",
    "fixtures/interest/i04-default-group-fallback",
    "fixtures/interest/i05-multi-category",
    "fixtures/interest/i06-negative-balance",
    "fixtures/interest/i07-nontie-rounding"
   ],
   "finding": null
  }
 ]
}
```
