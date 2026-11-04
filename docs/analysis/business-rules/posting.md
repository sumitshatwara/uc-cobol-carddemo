# Business rules: posting slice (BR-POST-*)

**Scope.** The daily posting chain `posting` from `tools/runjcl/chains.json`: job `app/jcl/POSTTRAN.jcl` (STEP15, program `app/cbl/CBTRN02C.cbl`) and job `app/jcl/TRANREPT.jcl` (IDCAMS REPRO through `app/proc/REPROC.prc` / `app/ctl/REPROCT.ctl`, DFSORT with the JCL `SYMNAMES` window, then STEP10R, program `app/cbl/CBTRN03C.cbl`). Record layouts: copybooks `CVTRA06Y` (DALYTRAN), `CVTRA05Y` (TRANSACT), `CVACT01Y` (ACCTDATA), `CVACT03Y` (CARDXREF), `CVTRA01Y` (TCATBALF), `CVTRA03Y` (TRANTYPE), `CVTRA04Y` (TRANCATG), `CVTRA07Y` (133-byte report) and the 430-byte reject layout `tools/parity/layouts/REJTRAN.cpy`. The chain's setup jobs (ACCTFILE, XREFFILE, TCATBALF, DISCGRP, TRANTYPE, TRANCATG, TRANFILE, DEFGDGB, DALYREJS) only define and load the input datasets.

**Method.** Rules were reverse-engineered by reading the JCL, procs, control cards, COBOL and copybooks on the integration branch, with every line range checked using `nl -ba`, and then compared with the golden fixtures `fixtures/posting/p01`..`p07` (`expected/` datasets, SYSOUT and `rc.json`). Where a fixture README disagrees with `expected/`, the golden files were treated as authoritative. **Finding** marks legacy behaviour that looks wrong but must be reproduced exactly by the Java 21 conversion.

**Run status.** The posting chain can be run in the Docker estate (GnuCOBOL 3, frozen clock `2022-07-05 10:00:00.00`). `make up && make build && make parity CHAIN=posting` was run on the legacy estate, and all seven cases p01..p07 passed with 0 differences. Rules marked `source-only` (I/O errors, abends, paging past 20 lines, field overflow) have no golden case and were derived from the source alone. CEE3ABD is an LE service; off-host it is replaced by the stand-in `tools/stubs/CEE3ABD.cbl`.

### BR-POST-001 CBTRN02C SYSOUT sequence and counters

**Rule**: CBTRN02C writes exactly these SYSOUT lines in order: 'START OF EXECUTION OF PROGRAM CBTRN02C', any per-record TCATBAL 'Creating' lines, then after all files are closed 'TRANSACTIONS PROCESSED :' and 'TRANSACTIONS REJECTED  :' (two spaces before the colon) each followed by the unsigned 9-digit zero-padded counter (PIC 9(09)), then 'END OF EXECUTION OF PROGRAM CBTRN02C'. The processed count includes rejected records.

**Source**: `app/cbl/CBTRN02C.cbl:184-186`; `app/cbl/CBTRN02C.cbl:194-194`; `app/cbl/CBTRN02C.cbl:227-228`; `app/cbl/CBTRN02C.cbl:232-232`

**Fields**: `WS-TRANSACTION-COUNT`, `WS-REJECT-COUNT`

**Category**: sysout

**Test**: Given DALYTRAN with 2 records, one of which is overlimit (p04 input); When POSTTRAN STEP15 runs; Then SYSOUT is START OF EXECUTION OF PROGRAM CBTRN02C / TRANSACTIONS PROCESSED :000000002 / TRANSACTIONS REJECTED  :000000001 / END OF EXECUTION OF PROGRAM CBTRN02C.

**Proven by**: fixtures/posting/p04-overlimit-reject, fixtures/posting/p01-baseline-mixed

### BR-POST-002 Sequential read loop over DALYTRAN

**Rule**: DALYTRAN is read sequentially; status '00' processes the record and adds 1 to WS-TRANSACTION-COUNT before validation, status '10' sets END-OF-FILE='Y' and ends the loop, any other status is an I/O error (see BR-POST-025). Each record's validation trailer is reset to reason 0 / spaces before validation.

**Source**: `app/cbl/CBTRN02C.cbl:202-219`; `app/cbl/CBTRN02C.cbl:345-369`

**Fields**: `DALYTRAN-RECORD`, `DALYTRAN-STATUS`, `END-OF-FILE`, `WS-VALIDATION-FAIL-REASON`, `WS-VALIDATION-FAIL-REASON-DESC`

**Category**: control-flow

**Test**: Given DALYTRAN with 4 valid records; When CBTRN02C reads to end of file; Then 4 records are validated and posted in input order; TRANSACTIONS PROCESSED :000000004.

**Proven by**: fixtures/posting/p01-baseline-mixed

### BR-POST-003 Validation order: card cross-reference first, then account

**Rule**: 1500-VALIDATE-TRAN looks up the card in XREFFILE first; the account lookup and the limit/expiry checks run only if the card lookup left reason 0. No other validation exists (the source carries the comment 'ADD MORE VALIDATIONS HERE').

**Source**: `app/cbl/CBTRN02C.cbl:370-378`

**Fields**: `DALYTRAN-CARD-NUM`, `XREF-ACCT-ID`, `WS-VALIDATION-FAIL-REASON`

**Category**: validation

**Test**: Given a transaction whose card 4000999999999999 has no CARDXREF record; When it is validated; Then it is rejected with reason 0100 and ACCTFILE is never read for it.

**Proven by**: fixtures/posting/p02-invalid-card

### BR-POST-004 Reject reason 100: card number not in CARDXREF

**Rule**: A random READ of XREFFILE by DALYTRAN-CARD-NUM that raises INVALID KEY sets reason 100 and description 'INVALID CARD NUMBER FOUND'. On success CARD-XREF-RECORD (including XREF-ACCT-ID) is loaded for the later steps.

**Source**: `app/cbl/CBTRN02C.cbl:380-392`

**Fields**: `DALYTRAN-CARD-NUM`, `FD-XREF-CARD-NUM`, `CARD-XREF-RECORD`, `XREF-ACCT-ID`

**Category**: validation

**Test**: Given DALYTRAN record 0000000000000002 with card 4000999999999999 and no matching CARDXREF row; When CBTRN02C validates it; Then one DALYREJS record with trailer '0100INVALID CARD NUMBER FOUND'; STEP15 RC 4.

**Proven by**: fixtures/posting/p02-invalid-card

### BR-POST-005 Reject reason 101: account record not found

**Rule**: With a valid cross-reference, ACCTFILE is read by XREF-ACCT-ID; INVALID KEY sets reason 101 'ACCOUNT RECORD NOT FOUND' and neither the limit nor the expiry check runs.

**Source**: `app/cbl/CBTRN02C.cbl:393-399`

**Fields**: `XREF-ACCT-ID`, `FD-ACCT-ID`, `ACCOUNT-RECORD`

**Category**: validation

**Test**: Given card 4000000000001099 maps to account 00000001099 which is absent from ACCTDATA; When the transaction is validated; Then DALYREJS trailer '0101ACCOUNT RECORD NOT FOUND'; TRANSACTIONS REJECTED  :000000001; RC 4.

**Proven by**: fixtures/posting/p03-account-missing

### BR-POST-006 Credit-limit check uses cycle amounts, not the current balance

**Rule**: WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT. The transaction passes when ACCT-CREDIT-LIMIT >= WS-TEMP-BAL (equality accepted); otherwise reason 102 'OVERLIMIT TRANSACTION'. ACCT-CURR-BAL plays no part. Because accepted transactions update the cycle fields immediately (BR-POST-020), later records in the same run see the new cycle totals.

**Source**: `app/cbl/CBTRN02C.cbl:403-413`

**Fields**: `ACCT-CURR-CYC-CREDIT`, `ACCT-CURR-CYC-DEBIT`, `DALYTRAN-AMT`, `ACCT-CREDIT-LIMIT`, `WS-TEMP-BAL`

**Category**: calculation

**Test**: Given account 1003 with ACCT-CREDIT-LIMIT 1000.00, ACCT-CURR-CYC-CREDIT 950.00, ACCT-CURR-CYC-DEBIT 0.00; purchases of 50.00 then 100.00; When both are posted in one run; Then 50.00 is accepted (950-0+50 = 1000 <= 1000, cycle credit becomes 1000.00); 100.00 is rejected with '0102OVERLIMIT TRANSACTION' (1000+100 = 1100 > 1000).

**Proven by**: fixtures/posting/p04-overlimit-reject

### BR-POST-007 Payments and credits make the limit check stricter

**Rule**: Negative amounts are added to ACCT-CURR-CYC-DEBIT (BR-POST-020), so the field holds a negative number; the formula then subtracts it, which adds the absolute value of every payment/credit to WS-TEMP-BAL. A payment therefore increases, rather than reduces, the exposure used by later limit checks.

**Source**: `app/cbl/CBTRN02C.cbl:403-407`; `app/cbl/CBTRN02C.cbl:548-552`

**Fields**: `ACCT-CURR-CYC-CREDIT`, `ACCT-CURR-CYC-DEBIT`, `WS-TEMP-BAL`, `ACCT-CREDIT-LIMIT`

**Category**: calculation

**Test**: Given limit 1000.00, cycle credit 900.00, cycle debit 0.00; a payment of -200.00 followed by a purchase of 150.00; When both are posted in one run; Then the payment posts (900-0-200 = 700) and cycle debit becomes -200.00; the purchase computes 900-(-200)+150 = 1250 > 1000 and is rejected with reason 102 although the net cycle spend would be 850.00. p06 proves the negative storage of ACCT-CURR-CYC-DEBIT (-325.50 after a -300.00 payment and -25.50 credit).

**Proven by**: fixtures/posting/p06-credits-new-category

**Finding**: Looks wrong (sign convention mismatch between 2800-UPDATE-ACCOUNT-REC and the limit formula) but must be reproduced exactly.

### BR-POST-008 WS-TEMP-BAL truncates the high-order digit

**Rule**: WS-TEMP-BAL is PIC S9(09)V99 while the cycle fields are S9(10)V99; COMPUTE has no ON SIZE ERROR, so a result of 1,000,000,000.00 or more silently loses its leading digit before the comparison.

**Source**: `app/cbl/CBTRN02C.cbl:187-187`; `app/cbl/CBTRN02C.cbl:403-407`

**Fields**: `WS-TEMP-BAL`, `ACCT-CURR-CYC-CREDIT`, `ACCT-CURR-CYC-DEBIT`, `DALYTRAN-AMT`

**Category**: edge-case

**Test**: Given ACCT-CREDIT-LIMIT 100.00, ACCT-CURR-CYC-CREDIT 1000000000.00, cycle debit 0.00, amount 50.00; When the transaction is validated; Then WS-TEMP-BAL holds 50.00 (1000000050.00 truncated), 100.00 >= 50.00, so the transaction is accepted instead of rejected.

**Proven by**: source-only

**Finding**: Silent high-order truncation can let an overlimit transaction through; reproduce it (do not widen the field).

### BR-POST-009 Expiry check: expiration date >= origin date, compared as text

**Rule**: ACCT-EXPIRAION-DATE (X(10)) is compared alphanumerically with DALYTRAN-ORIG-TS(1:10); the transaction passes when expiry >= origin date (same-day accepted), else reason 103 'TRANSACTION RECEIVED AFTER ACCT EXPIRATION'. Because it is a character comparison, a blank or '0000-00-00' expiry date always fails and non-ISO dates compare lexically.

**Source**: `app/cbl/CBTRN02C.cbl:414-420`; `app/cpy/CVACT01Y.cpy:11-11`; `app/cpy/CVTRA06Y.cpy:16-16`

**Fields**: `ACCT-EXPIRAION-DATE`, `DALYTRAN-ORIG-TS`

**Category**: validation

**Test**: Given account 1004 expires 2022-01-31 and account 1005 expires 2022-07-04; both receive a transaction with origin 2022-07-04-09.15.00.000000; When the transactions are validated; Then 1004's is rejected with '0103TRANSACTION RECEIVED AFTER ACCT EXPIRATION'; 1005's posts (2022-07-04 >= 2022-07-04).

**Proven by**: fixtures/posting/p05-expired-account

### BR-POST-010 Overlimit reason is overwritten by expiry reason

**Rule**: The limit and expiry checks are independent IFs; both run whenever the account exists. If both fail, the later MOVE wins, so the reject carries 103 only and the 102 condition is not reported.

**Source**: `app/cbl/CBTRN02C.cbl:407-420`

**Fields**: `WS-VALIDATION-FAIL-REASON`, `WS-VALIDATION-FAIL-REASON-DESC`

**Category**: validation

**Test**: Given an expired account (expiry 2022-01-31) that is also over its limit; When a 2022-07-04 transaction is validated; Then one reject with trailer '0103TRANSACTION RECEIVED AFTER ACCT EXPIRATION'; no 0102 record.

**Proven by**: source-only

**Finding**: Only the last failing reason is recorded; reproduce the 103-over-102 precedence.

### BR-POST-011 No active-status, cash-limit or type/category validation

**Rule**: Posting does not test ACCT-ACTIVE-STATUS, ACCT-CASH-CREDIT-LIMIT, or whether the type/category code exists: cash advances count against the general credit limit only, and inactive accounts are posted normally.

**Source**: `app/cbl/CBTRN02C.cbl:370-378`; `app/cpy/CVACT01Y.cpy:6-9`

**Fields**: `ACCT-ACTIVE-STATUS`, `ACCT-CASH-CREDIT-LIMIT`, `DALYTRAN-TYPE-CD`, `DALYTRAN-CAT-CD`

**Category**: validation

**Test**: Given account 1006 (cash credit limit 2000.00) and a type 01 / category 0002 'Cash advance at ATM' of 60.00; When CBTRN02C runs; Then the cash advance posts with no cash-limit test and a new TCATBAL record (1006,01,0002) is created.

**Proven by**: fixtures/posting/p06-credits-new-category

**Finding**: Missing validations (inactive accounts and cash limit are ignored) must be reproduced, not added.

### BR-POST-012 430-byte reject record

**Rule**: A rejected transaction is written to DALYREJS(+1) (RECFM=F, LRECL=430) as the 350-byte DALYTRAN-RECORD copied unchanged followed by the 80-byte trailer: reason PIC 9(04) zero-padded (0100..0103) and a 76-byte description padded with spaces.

**Source**: `app/cbl/CBTRN02C.cbl:81-84`; `app/cbl/CBTRN02C.cbl:176-182`; `app/cbl/CBTRN02C.cbl:446-451`; `app/jcl/POSTTRAN.jcl:34-38`; `tools/parity/layouts/REJTRAN.cpy:7-24`

**Fields**: `REJECT-TRAN-DATA`, `VALIDATION-TRAILER`, `WS-VALIDATION-FAIL-REASON`, `WS-VALIDATION-FAIL-REASON-DESC`, `REJ-FAIL-REASON`, `REJ-FAIL-REASON-DESC`

**Category**: data-mapping

**Test**: Given the invalid-card transaction of p02; When it is rejected; Then DALYREJS.G0001V00 holds one 430-byte record: the original 350 bytes (including its DALYTRAN-PROC-TS) then '0100INVALID CARD NUMBER FOUND' padded to 80.

**Proven by**: fixtures/posting/p02-invalid-card, fixtures/posting/p03-account-missing, fixtures/posting/p05-expired-account

### BR-POST-013 Rejected transactions change no master file

**Rule**: A record with a non-zero reason only increments WS-REJECT-COUNT and is written to DALYREJS; TCATBALF, ACCTDATA and TRANSACT are not touched for it.

**Source**: `app/cbl/CBTRN02C.cbl:211-216`

**Fields**: `WS-REJECT-COUNT`, `TRAN-CAT-BAL`, `ACCT-CURR-BAL`, `TRAN-RECORD`

**Category**: state-change

**Test**: Given p04: one accepted 50.00 and one rejected 100.00 purchase on account 1003 (balance 950.00, TCATBAL 950.00); When CBTRN02C runs; Then ACCT-CURR-BAL and TRAN-CAT-BAL both end at 1000.00 and TRANSACT holds 1 record.

**Proven by**: fixtures/posting/p04-overlimit-reject

### BR-POST-014 Return code 4 when any record is rejected

**Rule**: After closing all files, RETURN-CODE is set to 4 if WS-REJECT-COUNT > 0; otherwise it stays 0. Rejects never stop the run. TRANREPT still runs after RC 4 (no COND on the job).

**Source**: `app/cbl/CBTRN02C.cbl:229-231`; `app/cbl/CBTRN02C.cbl:234-234`

**Fields**: `WS-REJECT-COUNT`, `RETURN-CODE`

**Category**: return-code

**Test**: Given a daily file with 1 reject among 2 records; When POSTTRAN runs; Then rc.json POSTTRAN STEP15 = 4 (maxrc 4); with zero rejects (p01) STEP15 = 0.

**Proven by**: fixtures/posting/p02-invalid-card, fixtures/posting/p04-overlimit-reject, fixtures/posting/p01-baseline-mixed

### BR-POST-015 Posted transaction record mapping

**Rule**: For an accepted record, TRAN-RECORD gets ID, type, category, source, description, amount, merchant id/name/city/zip, card number and ORIG-TS copied field by field from DALYTRAN-RECORD; DALYTRAN-PROC-TS is ignored and replaced (BR-POST-016). The record is written to the TRANSACT KSDS keyed by TRAN-ID.

**Source**: `app/cbl/CBTRN02C.cbl:424-436`; `app/cpy/CVTRA05Y.cpy:4-18`; `app/cpy/CVTRA06Y.cpy:4-18`

**Fields**: `TRAN-ID`, `TRAN-TYPE-CD`, `TRAN-CAT-CD`, `TRAN-SOURCE`, `TRAN-DESC`, `TRAN-AMT`, `TRAN-MERCHANT-ID`, `TRAN-MERCHANT-NAME`, `TRAN-MERCHANT-CITY`, `TRAN-MERCHANT-ZIP`, `TRAN-CARD-NUM`, `TRAN-ORIG-TS`

**Category**: data-mapping

**Test**: Given DALYTRAN 0000000000000001 'Bookshop purchase' 125.00 card 4000000000001001 origin 2022-07-04-09.15.00.000000; When it is posted; Then TRANSACT record 0000000000000001 carries the same field values, ORIG-TS 2022-07-04-09.15.00.000000.

**Proven by**: fixtures/posting/p01-baseline-mixed

### BR-POST-016 PROC-TS from the frozen clock in DB2 timestamp format

**Rule**: TRAN-PROC-TS is built from FUNCTION CURRENT-DATE as YYYY-MM-DD-HH.MM.SS.hh0000 (hundredths of a second followed by literal '0000'). With the chain clock 2022-07-05 10:00:00.00 every posted record gets '2022-07-05-10.00.00.000000'.

**Source**: `app/cbl/CBTRN02C.cbl:149-174`; `app/cbl/CBTRN02C.cbl:437-438`; `app/cbl/CBTRN02C.cbl:692-705`

**Fields**: `TRAN-PROC-TS`, `COBOL-TS`, `DB2-FORMAT-TS`, `DB2-MIL`, `DB2-REST`

**Category**: data-mapping

**Test**: Given COB_CURRENT_DATE=2022-07-05 10:00:00.00; When any transaction is posted; Then its TRAN-PROC-TS is 2022-07-05-10.00.00.000000.

**Proven by**: fixtures/posting/p01-baseline-mixed, fixtures/posting/p06-credits-new-category

### BR-POST-017 Update order: TCATBAL, then account, then TRANSACT

**Rule**: 2000-POST-TRANSACTION performs 2700-UPDATE-TCATBAL, then 2800-UPDATE-ACCOUNT-REC, then 2900-WRITE-TRANSACTION-FILE. There is no rollback: a later failure leaves the earlier updates in place (BR-POST-024).

**Source**: `app/cbl/CBTRN02C.cbl:440-442`

**Fields**: `TRAN-CAT-BAL-RECORD`, `ACCOUNT-RECORD`, `TRAN-RECORD`

**Category**: control-flow

**Test**: Given a valid purchase of 125.00 on account 1001; When it is posted; Then TCATBAL (1001,01,0001) goes 500.00 -> 625.00, ACCT-CURR-BAL 500.00 -> 425.00 after the whole day, and the TRANSACT record is written last.

**Proven by**: fixtures/posting/p01-baseline-mixed

### BR-POST-018 TCATBAL update of an existing category balance

**Rule**: The TCATBAL key is XREF-ACCT-ID (11) + DALYTRAN-TYPE-CD (2) + DALYTRAN-CAT-CD (4). If the record exists, DALYTRAN-AMT is added to TRAN-CAT-BAL (signed, no rounding) and the record is REWRITten. A read status other than '00' or '23' displays 'ERROR READING TRANSACTION BALANCE FILE' and abends; a REWRITE failure displays 'ERROR REWRITING TRANSACTION BALANCE FILE' and abends.

**Source**: `app/cbl/CBTRN02C.cbl:467-501`; `app/cbl/CBTRN02C.cbl:526-542`; `app/cpy/CVTRA01Y.cpy:4-10`

**Fields**: `FD-TRANCAT-ACCT-ID`, `FD-TRANCAT-TYPE-CD`, `FD-TRANCAT-CD`, `TRAN-CAT-BAL`

**Category**: state-change

**Test**: Given TCATBAL (1001,02,0001) = 0.00 and a payment of -200.00 on card 4000000000001001; When it is posted; Then TRAN-CAT-BAL for 00000001001020001 becomes -200.00.

**Proven by**: fixtures/posting/p01-baseline-mixed

### BR-POST-019 TCATBAL creation for a new account/type/category

**Rule**: When the TCATBAL READ raises INVALID KEY (status '23'), CBTRN02C displays 'TCATBAL record not found for key : ' + the 17-character key + '.. Creating.', INITIALIZEs TRAN-CAT-BAL-RECORD, sets the key, adds the amount to the zeroed balance and WRITEs a new record. A WRITE failure displays 'ERROR WRITING TRANSACTION BALANCE FILE' and abends.

**Source**: `app/cbl/CBTRN02C.cbl:473-479`; `app/cbl/CBTRN02C.cbl:495-499`; `app/cbl/CBTRN02C.cbl:503-524`

**Fields**: `TRAN-CAT-KEY`, `TRANCAT-ACCT-ID`, `TRANCAT-TYPE-CD`, `TRANCAT-CD`, `TRAN-CAT-BAL`

**Category**: state-change

**Test**: Given account 1006 has only TCATBAL (1006,01,0001); transactions with type/cat 02/0001 (-300.00), 03/0001 (-25.50) and 01/0002 (60.00); When CBTRN02C runs; Then SYSOUT shows 'TCATBAL record not found for key : 00000001006020001.. Creating.' and the same for 030001 and 010002; TCATBALF gains records with balances -300.00, -25.50 and 60.00.

**Proven by**: fixtures/posting/p06-credits-new-category

### BR-POST-020 Account balance and cycle-bucket update

**Rule**: DALYTRAN-AMT is added to ACCT-CURR-BAL. If the amount is >= 0 (zero included) it is added to ACCT-CURR-CYC-CREDIT, otherwise the negative amount is added to ACCT-CURR-CYC-DEBIT, so that field accumulates negative values. The account record is then REWRITten.

**Source**: `app/cbl/CBTRN02C.cbl:545-554`; `app/cpy/CVACT01Y.cpy:7-7`; `app/cpy/CVACT01Y.cpy:13-14`

**Fields**: `ACCT-CURR-BAL`, `ACCT-CURR-CYC-CREDIT`, `ACCT-CURR-CYC-DEBIT`, `DALYTRAN-AMT`

**Category**: state-change

**Test**: Given account 1001 with balance 500.00, cycle credit 0.00, cycle debit 0.00; posts of +125.00 and -200.00; When CBTRN02C runs; Then ACCT-CURR-BAL 425.00, ACCT-CURR-CYC-CREDIT 125.00, ACCT-CURR-CYC-DEBIT -200.00.

**Proven by**: fixtures/posting/p01-baseline-mixed, fixtures/posting/p06-credits-new-category

### BR-POST-021 Account REWRITE failure reason 109 is never acted on

**Rule**: If the account REWRITE raises INVALID KEY, reason 109 'ACCOUNT RECORD NOT FOUND' is moved to the trailer, but nothing tests it afterwards: the TRANSACT record is still written, no reject record is produced, WS-REJECT-COUNT and the RC are unchanged, and the reason is reset for the next record.

**Source**: `app/cbl/CBTRN02C.cbl:554-559`; `app/cbl/CBTRN02C.cbl:208-216`

**Fields**: `WS-VALIDATION-FAIL-REASON`, `WS-VALIDATION-FAIL-REASON-DESC`

**Category**: edge-case

**Test**: Given an account REWRITE that returns INVALID KEY after a successful read; When the transaction is posted; Then the transaction is still written to TRANSACT, DALYREJS gets nothing, TRANSACTIONS REJECTED is unchanged and RC stays 0.

**Proven by**: source-only

**Finding**: Dead error handling (code 109 is set but ignored); reproduce it, do not turn it into a reject.

### BR-POST-022 Amounts are exact: no rounding anywhere in posting

**Rule**: All posting arithmetic (ADD to TRAN-CAT-BAL, ACCT-CURR-BAL, cycle fields) works on two-decimal zoned fields with no ROUNDED phrase and no division, so results are exact to the cent; signs travel as zoned overpunch (e.g. '0000000157N' = -15.75, '0000001250{' = +125.00). Half cents cannot occur because input amounts have 2 decimals.

**Source**: `app/cbl/CBTRN02C.cbl:508-508`; `app/cbl/CBTRN02C.cbl:527-527`; `app/cbl/CBTRN02C.cbl:547-552`; `app/cpy/CVTRA06Y.cpy:10-10`

**Fields**: `DALYTRAN-AMT`, `TRAN-AMT`, `TRAN-CAT-BAL`, `ACCT-CURR-BAL`

**Category**: rounding

**Test**: Given a refund of -15.75 (zoned '0000000157N') and a purchase of 80.25 ('0000000802E') on account 1002 with balance 120.05; When they are posted; Then ACCT-CURR-BAL becomes 126.50 ('00000012650{'), ACCT-CURR-CYC-DEBIT -15.75 ('00000000157N').

**Proven by**: fixtures/posting/p01-baseline-mixed

### BR-POST-023 TRANSACT is opened OUTPUT

**Rule**: CBTRN02C opens the TRANSACT KSDS with OPEN OUTPUT (not I-O/EXTEND), so the run creates the file contents afresh; any rows loaded by the TRANFILE setup job from DALYTRAN.PS.INIT are not carried forward off-host. After posting the file holds exactly the accepted transactions of the day, in TRAN-ID key order.

**Source**: `app/cbl/CBTRN02C.cbl:254-270`; `app/jcl/TRANFILE.jcl:67-74`

**Fields**: `TRAN-RECORD`, `TRANFILE-STATUS`

**Category**: state-change

**Test**: Given an empty DALYTRAN.PS.INIT and 4 valid daily transactions; When POSTTRAN runs; Then TRANSACT holds 4 records (manifest records: 4) keyed 0000000000000001..0000000000000004.

**Proven by**: fixtures/posting/p01-baseline-mixed, fixtures/posting/p07-empty-day

**Finding**: On z/OS OPEN OUTPUT on a loaded KSDS behaves differently unless the cluster is REUSE; the fixtures always start TRANSACT empty, so reproduce the off-host 'only today's postings' result.

### BR-POST-024 TRANSACT write failure abends after partial updates

**Rule**: If WRITE to TRANSACT fails (e.g. duplicate TRAN-ID, status '22'), CBTRN02C displays 'ERROR WRITING TO TRANSACTION FILE', the file status line and 'ABENDING PROGRAM', then abends. The TCATBAL and account updates for that record are already rewritten, files are not closed by the program and the counter/END lines are not displayed.

**Source**: `app/cbl/CBTRN02C.cbl:562-579`; `app/cbl/CBTRN02C.cbl:440-442`

**Fields**: `TRAN-ID`, `TRANFILE-STATUS`, `IO-STATUS`

**Category**: edge-case

**Test**: Given two daily records with the same TRAN-ID 0000000000000001; When the second is posted; Then SYSOUT ends with ERROR WRITING TO TRANSACTION FILE / FILE STATUS IS: NNNN0022 / ABENDING PROGRAM and the step ends abnormally (RC 12 via the off-host CEE3ABD stand-in); TCATBAL and ACCT already include the second amount.

**Proven by**: source-only

**Finding**: No duplicate check and no rollback: balances are updated for a transaction that is never written. Reproduce.

### BR-POST-025 CBTRN02C I/O error messages

**Rule**: Every open, read, write and close of the six files checks for status '00' (read also accepts '10' = EOF) and otherwise displays a file-specific message followed by the file-status line (BR-POST-027) and the abend (BR-POST-028). Messages: ERROR OPENING DALYTRAN, ERROR OPENING TRANSACTION FILE, ERROR OPENING CROSS REF FILE, ERROR OPENING DALY REJECTS FILE, ERROR OPENING ACCOUNT MASTER FILE, ERROR OPENING TRANSACTION BALANCE FILE, ERROR READING DALYTRAN FILE, ERROR WRITING TO REJECTS FILE, ERROR CLOSING DALYTRAN FILE, ERROR CLOSING TRANSACTION FILE, ERROR CLOSING CROSS REF FILE, ERROR CLOSING DAILY REJECTS FILE, ERROR CLOSING ACCOUNT FILE, ERROR CLOSING TRANSACTION BALANCE FILE.

**Source**: `app/cbl/CBTRN02C.cbl:236-343`; `app/cbl/CBTRN02C.cbl:357-368`; `app/cbl/CBTRN02C.cbl:457-464`; `app/cbl/CBTRN02C.cbl:582-690`

**Fields**: `DALYTRAN-STATUS`, `TRANFILE-STATUS`, `XREFFILE-STATUS`, `DALYREJS-STATUS`, `ACCTFILE-STATUS`, `TCATBALF-STATUS`

**Category**: sysout

**Test**: Given the ACCTFILE KSDS is missing (open status '35'); When CBTRN02C starts; Then SYSOUT: START OF EXECUTION OF PROGRAM CBTRN02C / ERROR OPENING ACCOUNT MASTER FILE / FILE STATUS IS: NNNN0035 / ABENDING PROGRAM.

**Proven by**: source-only

### BR-POST-026 DALYREJS close error reports the XREF file status

**Rule**: 9300-DALYREJS-CLOSE checks DALYREJS-STATUS but moves XREFFILE-STATUS into IO-STATUS before displaying it, so the printed file status belongs to the cross-reference file (normally '00').

**Source**: `app/cbl/CBTRN02C.cbl:637-653`

**Fields**: `DALYREJS-STATUS`, `XREFFILE-STATUS`, `IO-STATUS`

**Category**: sysout

**Test**: Given CLOSE of DALYREJS fails with status '30' after XREFFILE closed with '00'; When 9300-DALYREJS-CLOSE runs; Then SYSOUT: ERROR CLOSING DAILY REJECTS FILE / FILE STATUS IS: NNNN0000 / ABENDING PROGRAM.

**Proven by**: source-only

**Finding**: Copy-paste defect: wrong status variable displayed. Reproduce it.

### BR-POST-027 File-status display format

**Rule**: 9910-DISPLAY-IO-STATUS prints 'FILE STATUS IS: NNNN' followed by a 4-character code: for a numeric status not starting with '9' it is '00' + the 2-digit status (e.g. NNNN0023); for a non-numeric status or one starting with '9' it is the first status byte followed by the 3-digit binary value of the second byte (e.g. status '9' + X'04' -> NNNN9004). Same routine in both programs.

**Source**: `app/cbl/CBTRN02C.cbl:131-140`; `app/cbl/CBTRN02C.cbl:714-727`; `app/cbl/CBTRN03C.cbl:633-646`

**Fields**: `IO-STATUS`, `IO-STAT1`, `IO-STAT2`, `IO-STATUS-04`, `TWO-BYTES-BINARY`

**Category**: sysout

**Test**: Given IO-STATUS = '23'; When 9910-DISPLAY-IO-STATUS runs; Then SYSOUT line 'FILE STATUS IS: NNNN0023'.

**Proven by**: source-only

### BR-POST-028 Abend via CEE3ABD with code 999

**Rule**: 9999-ABEND-PROGRAM displays 'ABENDING PROGRAM' and calls CEE3ABD with ABCODE 999 and TIMING 0 (user abend U999 on z/OS). Off-host the CEE3ABD stand-in (tools/stubs/CEE3ABD.cbl) displays the abend code and stops the run with RC 12; GnuCOBOL may then add 'libcob: warning: implicit CLOSE' runtime lines, which are not program output. Identical in CBTRN02C and CBTRN03C.

**Source**: `app/cbl/CBTRN02C.cbl:147-148`; `app/cbl/CBTRN02C.cbl:707-711`; `app/cbl/CBTRN03C.cbl:626-630`

**Fields**: `ABCODE`, `TIMING`, `RETURN-CODE`

**Category**: return-code

**Test**: Given any unrecoverable I/O error; When 9999-ABEND-PROGRAM runs; Then SYSOUT 'ABENDING PROGRAM', the step ends with abend U999 (RC 12 off-host) and no END OF EXECUTION line.

**Proven by**: source-only

### BR-POST-029 Empty daily file posts nothing

**Rule**: If DALYTRAN is empty the first read returns '10'; counters stay zero, nothing is written to TRANSACT or DALYREJS, ACCTDATA and TCATBALF are unchanged, RC is 0 and DALYREJS(+1) is still catalogued as an empty generation.

**Source**: `app/cbl/CBTRN02C.cbl:202-234`; `app/jcl/POSTTRAN.jcl:34-38`

**Fields**: `WS-TRANSACTION-COUNT`, `WS-REJECT-COUNT`, `RETURN-CODE`

**Category**: edge-case

**Test**: Given an empty AWS.M2.CARDDEMO.DALYTRAN.PS; When POSTTRAN runs; Then SYSOUT TRANSACTIONS PROCESSED :000000000 / TRANSACTIONS REJECTED  :000000000; STEP15 RC 0; TRANSACT and DALYREJS.G0001V00 have 0 records.

**Proven by**: fixtures/posting/p07-empty-day

### BR-POST-030 TRANREPT unloads TRANSACT with IDCAMS REPRO

**Rule**: TRANREPT's first step (STEP05R, proc REPROC, proc step PRC001) runs IDCAMS 'REPRO INFILE(FILEIN) OUTFILE(FILEOUT)' copying the TRANSACT KSDS in key (TRAN-ID) order into a new generation TRANSACT.BKUP(+1), RECFM=FB LRECL=350. The SORT step that follows is also named STEP05R.

**Source**: `app/jcl/TRANREPT.jcl:19-33`; `app/proc/REPROC.prc:21-28`; `app/ctl/REPROCT.ctl:15-15`

**Fields**: `TRAN-RECORD`, `TRAN-ID`

**Category**: control-flow

**Test**: Given TRANSACT holds the 4 postings of p01; When TRANREPT runs; Then TRANSACT.BKUP.G0001V00 holds the same 4 records in TRAN-ID order; rc.json lists PRC001 = 0 and STEP05R = 0.

**Proven by**: fixtures/posting/p01-baseline-mixed

**Finding**: Two steps share the name STEP05R (the REPRO proc step and the SORT step), which makes step references ambiguous; keep the step names as recorded in rc.json.

### BR-POST-031 SORT window is hard-coded in JCL SYMNAMES

**Rule**: The DFSORT step keeps only records whose TRAN-PROC-DT (bytes 305-314 of TRAN-PROC-TS, CH) is >= '2022-01-01' and <= '2022-07-06' (inclusive). The bounds are literals in SYMNAMES, not taken from DATEPARM, so changing DATEPARM does not change which records reach CBTRN03C.

**Source**: `app/jcl/TRANREPT.jcl:40-48`

**Fields**: `TRAN-PROC-TS`

**Category**: validation

**Test**: Given postings with TRAN-PROC-TS 2022-07-05-10.00.00.000000 (frozen clock); When the SORT runs; Then all records are included (2022-07-05 is inside 2022-01-01..2022-07-06); a record with proc date 2022-07-07 would be dropped regardless of DATEPARM.

**Proven by**: fixtures/posting/p01-baseline-mixed

**Finding**: Report window is hard-coded in the JCL and duplicated in DATEPARM; reproduce the literal window.

### BR-POST-032 SORT by card number, stable

**Rule**: SORT FIELDS=(TRAN-CARD-NUM,A) with TRAN-CARD-NUM at position 263 length 16 (ZD) orders the daily file by card; records with the same card keep their incoming TRAN-ID order. Output goes to TRANSACT.DALY(+1) with the SORTIN DCB.

**Source**: `app/jcl/TRANREPT.jcl:37-46`; `app/jcl/TRANREPT.jcl:51-55`

**Fields**: `TRAN-CARD-NUM`, `TRAN-ID`

**Category**: control-flow

**Test**: Given p01 postings 1 and 3 on card ...1001 and 2 and 4 on card ...1002; When the SORT runs; Then TRANSACT.DALY order is 0000000000000001, 0000000000000003, 0000000000000002, 0000000000000004.

**Proven by**: fixtures/posting/p01-baseline-mixed

### BR-POST-033 CBTRN03C SYSOUT banners and DATEPARM echo

**Rule**: CBTRN03C displays 'START OF EXECUTION OF PROGRAM CBTRN03C', opens its six files, reads the single 80-byte DATEPARM record (start date bytes 1-10, one filler byte, end date bytes 12-21) and displays 'Reporting from ' start ' to ' end. At the end it displays 'END OF EXECUTION OF PROGRAM CBTRN03C'. STEP10R ends RC 0 unless it abends.

**Source**: `app/cbl/CBTRN03C.cbl:122-125`; `app/cbl/CBTRN03C.cbl:160-168`; `app/cbl/CBTRN03C.cbl:215-215`; `app/cbl/CBTRN03C.cbl:220-233`

**Fields**: `WS-START-DATE`, `WS-END-DATE`, `FD-DATEPARM-REC`

**Category**: sysout

**Test**: Given DATEPARM '2022-01-01 2022-07-06'; When STEP10R runs; Then SYSOUT line 2 is 'Reporting from 2022-01-01 to 2022-07-06'; rc.json STEP10R = 0.

**Proven by**: fixtures/posting/p01-baseline-mixed, fixtures/posting/p07-empty-day

### BR-POST-034 Empty DATEPARM ends the report before any record

**Rule**: If the DATEPARM read returns '10' (empty file), END-OF-FILE is set to 'Y', no 'Reporting from' line is displayed and the main loop never executes: no transaction is read and the report is empty. Any other non-'00' status displays 'ERROR READING DATEPARM FILE' and abends.

**Source**: `app/cbl/CBTRN03C.cbl:220-243`; `app/cbl/CBTRN03C.cbl:170-171`

**Fields**: `DATEPARM-STATUS`, `END-OF-FILE`

**Category**: edge-case

**Test**: Given an empty AWS.M2.CARDDEMO.DATEPARM and 4 sorted transactions; When STEP10R runs; Then SYSOUT has only the START and END lines; TRANREPT(+1) has 0 records; RC 0.

**Proven by**: source-only

### BR-POST-035 Every reported record is echoed to SYSOUT

**Rule**: Before formatting, CBTRN03C displays the full 350-byte TRAN-RECORD as raw text (zoned amounts with overpunch, e.g. '0000001250{') for every in-window record.

**Source**: `app/cbl/CBTRN03C.cbl:179-180`

**Fields**: `TRAN-RECORD`

**Category**: sysout

**Test**: Given p01's 4 sorted transactions; When STEP10R runs; Then SYSOUT lines 3-6 are the 4 raw 350-byte records in card order, the first starting '0000000000000001010001POS TERM  Bookshop purchase'.

**Proven by**: fixtures/posting/p01-baseline-mixed

### BR-POST-036 Out-of-window record ends the report via NEXT SENTENCE

**Rule**: CBTRN03C re-tests TRAN-PROC-TS(1:10) against the DATEPARM window. For a record outside it the code executes NEXT SENTENCE, which jumps past the period after END-PERFORM, so the read loop ends at once: that record and all later ones are skipped and no page, account or grand totals are written.

**Source**: `app/cbl/CBTRN03C.cbl:170-178`; `app/cbl/CBTRN03C.cbl:206-208`

**Fields**: `TRAN-PROC-TS`, `WS-START-DATE`, `WS-END-DATE`

**Category**: control-flow

**Test**: Given DATEPARM '2022-07-06 2022-07-06' and sorted postings with proc date 2022-07-05 (inside the SORT window); When STEP10R runs; Then the first record fails the test, the loop exits, TRANREPT(+1) has 0 records and SYSOUT shows only START, 'Reporting from 2022-07-06 to 2022-07-06' and END.

**Proven by**: source-only

**Finding**: NEXT SENTENCE inside an inline PERFORM acts as 'stop processing' rather than 'skip this record'; reproduce it.

### BR-POST-037 Empty day produces a zero-record report

**Rule**: When TRANSACT.DALY is empty, the first read hits EOF while TRAN-RECORD is still its initial blank value; the blank TRAN-PROC-TS fails the date test, NEXT SENTENCE leaves the loop and the EOF totals branch never runs. The report has no headers, no totals, and SYSOUT has no TRAN-AMT / WS-PAGE-TOTAL lines.

**Source**: `app/cbl/CBTRN03C.cbl:170-206`

**Fields**: `TRAN-RECORD`, `TRAN-PROC-TS`, `END-OF-FILE`

**Category**: edge-case

**Test**: Given no daily transactions (p07); When TRANREPT runs; Then TRANREPT.G0001V00 has 0 records (manifest records: 0); SYSOUT is START / Reporting from 2022-01-01 to 2022-07-06 / END; STEP10R RC 0.

**Proven by**: fixtures/posting/p07-empty-day

**Finding**: The fixture README says headers are produced, but the golden output has 0 records; the golden file wins and the empty report must be reproduced.

### BR-POST-038 Report header block and 133-byte records

**Rule**: Before the first detail line CBTRN03C writes REPORT-NAME-HEADER ('DALYREPT' in 38 bytes, 'Daily Transaction Report' in 41, 'Date Range: ', start, ' to ', end = 115 bytes), a blank line, TRANSACTION-HEADER-1 (column titles) and TRANSACTION-HEADER-2 (133 dashes), adding 4 to WS-LINE-COUNTER. Every report record is padded with spaces to LRECL 133 (RECFM=FB).

**Source**: `app/cbl/CBTRN03C.cbl:84-85`; `app/cbl/CBTRN03C.cbl:274-280`; `app/cbl/CBTRN03C.cbl:324-341`; `app/cpy/CVTRA07Y.cpy:4-13`; `app/cpy/CVTRA07Y.cpy:33-48`; `app/jcl/TRANREPT.jcl:76-80`

**Fields**: `REPORT-NAME-HEADER`, `REPT-START-DATE`, `REPT-END-DATE`, `TRANSACTION-HEADER-1`, `TRANSACTION-HEADER-2`, `WS-LINE-COUNTER`

**Category**: data-mapping

**Test**: Given DATEPARM 2022-01-01 / 2022-07-06 and at least one in-window transaction; When the first detail is written; Then report lines 1-4: 'DALYREPT ... Daily Transaction Report ... Date Range: 2022-01-01 to 2022-07-06', blank, 'Transaction ID   Account ID  Transaction Type   Tran Category                      Tran Source            Amount', 133 dashes.

**Proven by**: fixtures/posting/p01-baseline-mixed

### BR-POST-039 Detail line layout

**Rule**: Each detail line is TRAN-ID (16), space, account id from XREF-ACCT-ID (11 digits), space, type code '-' type description truncated to 15, space, category code (4) '-' category description truncated to 29, space, TRAN-SOURCE (10), 4 spaces, TRAN-AMT edited -ZZZ,ZZZ,ZZZ.ZZ (leading '-' only for negatives, blank otherwise), 2 spaces. Descriptions come from random reads of TRANTYPE (by type) and TRANCATG (by type+category).

**Source**: `app/cbl/CBTRN03C.cbl:189-196`; `app/cbl/CBTRN03C.cbl:361-374`; `app/cpy/CVTRA07Y.cpy:15-31`

**Fields**: `TRAN-REPORT-TRANS-ID`, `TRAN-REPORT-ACCOUNT-ID`, `TRAN-REPORT-TYPE-CD`, `TRAN-REPORT-TYPE-DESC`, `TRAN-REPORT-CAT-CD`, `TRAN-REPORT-CAT-DESC`, `TRAN-REPORT-SOURCE`, `TRAN-REPORT-AMT`, `TRAN-TYPE-DESC`, `TRAN-CAT-TYPE-DESC`

**Category**: data-mapping

**Test**: Given posting 0000000000000003, account 1001, type 02 'Payment', category 0001 'Payment - Bank Transfer', source OPERATOR, amount -200.00; When the detail is written; Then '0000000000000003 00000001001 02-Payment         0001-Payment - Bank Transfer       OPERATOR      -        200.00'.

**Proven by**: fixtures/posting/p01-baseline-mixed, fixtures/posting/p06-credits-new-category

### BR-POST-040 Card break writes the Account Total

**Rule**: When TRAN-CARD-NUM differs from WS-CURR-CARD-NUM and this is not the first record, CBTRN03C writes 'Account Total' + 84 dots + WS-ACCOUNT-TOTAL edited +ZZZ,ZZZ,ZZZ.ZZ ('+' or '-' sign), then a line of 133 dashes, resets the account total and adds 2 to the line counter. It then looks the new card up in CARDXREF (account id for the detail lines).

**Source**: `app/cbl/CBTRN03C.cbl:181-188`; `app/cbl/CBTRN03C.cbl:306-316`; `app/cpy/CVTRA07Y.cpy:56-60`

**Fields**: `WS-CURR-CARD-NUM`, `WS-ACCOUNT-TOTAL`, `REPT-ACCOUNT-TOTAL`, `WS-FIRST-TIME`

**Category**: calculation

**Test**: Given card ...1001 with 125.00 and -200.00 followed by card ...1002; When the first ...1002 record is processed; Then report line 'Account Total....................................................................................-         75.00' followed by 133 dashes.

**Proven by**: fixtures/posting/p01-baseline-mixed

### BR-POST-041 Page break every 20 lines; line counter never resets

**Rule**: Before each detail line, if MOD(WS-LINE-COUNTER, 20) = 0 the program writes the page total ('Page Total ' + 86 dots + total edited +ZZZ,ZZZ,ZZZ.ZZ) and a dash line, then the 4-line header block. WS-LINE-COUNTER is never reset and account-total breaks add 2 lines, so the counter can step over a multiple of 20 and the page then runs until the next multiple (e.g. 40).

**Source**: `app/cbl/CBTRN03C.cbl:128-132`; `app/cbl/CBTRN03C.cbl:282-285`; `app/cbl/CBTRN03C.cbl:293-304`; `app/cbl/CBTRN03C.cbl:306-316`; `app/cpy/CVTRA07Y.cpy:50-54`

**Fields**: `WS-LINE-COUNTER`, `WS-PAGE-SIZE`, `WS-PAGE-TOTAL`

**Category**: control-flow

**Test**: Given the line counter is 19 after a detail and the next record starts a new card; When the account total (+2 lines) is written; Then the counter reaches 21, MOD(21,20) <> 0, so no page break is taken at line 20; the next break happens when the counter is exactly 40.

**Proven by**: source-only

**Finding**: Page size is not enforced consistently (counter not reset, account-total lines can skip the break); reproduce the counter arithmetic exactly.

### BR-POST-042 Page total rolls into the grand total

**Rule**: Every detail adds TRAN-AMT to WS-PAGE-TOTAL and WS-ACCOUNT-TOTAL. Writing the page total adds WS-PAGE-TOTAL to WS-GRAND-TOTAL and resets the page total to 0; the grand total is only ever the sum of written page totals.

**Source**: `app/cbl/CBTRN03C.cbl:134-136`; `app/cbl/CBTRN03C.cbl:287-288`; `app/cbl/CBTRN03C.cbl:293-298`

**Fields**: `WS-PAGE-TOTAL`, `WS-ACCOUNT-TOTAL`, `WS-GRAND-TOTAL`

**Category**: calculation

**Test**: Given p06: -300.00, -25.50 and 60.00 on one card, no page break; When EOF totals are written; Then the page total -205.50 (with the BR-POST-043 double count) is added to the zero grand total and 'Grand Total' shows -205.50.

**Proven by**: fixtures/posting/p06-credits-new-category

### BR-POST-043 EOF totals double-count the last amount and skip the last Account Total

**Rule**: At end of file the READ INTO leaves the last record in TRAN-RECORD, so it passes the date test and the ELSE branch adds TRAN-AMT to the page and account totals a second time, then writes the page total (plus dash line) and the grand total. 1120-WRITE-ACCOUNT-TOTALS is not called, so the last card's Account Total never appears.

**Source**: `app/cbl/CBTRN03C.cbl:197-204`; `app/cbl/CBTRN03C.cbl:248-264`

**Fields**: `TRAN-AMT`, `WS-PAGE-TOTAL`, `WS-ACCOUNT-TOTAL`, `WS-GRAND-TOTAL`

**Category**: calculation

**Test**: Given p04: a single posted transaction of 50.00; When TRANREPT runs; Then 'Page Total' and 'Grand Total' show +100.00 instead of 50.00 and no Account Total line is written (8 report records); in p01 the totals are -26.25 instead of -10.50.

**Proven by**: fixtures/posting/p04-overlimit-reject, fixtures/posting/p01-baseline-mixed, fixtures/posting/p02-invalid-card

**Finding**: Last amount counted twice and last account total missing; the golden reports encode this, so reproduce it.

### BR-POST-044 EOF SYSOUT shows TRAN-AMT and the pre-adjustment page total

**Rule**: In the EOF branch CBTRN03C displays 'TRAN-AMT ' + TRAN-AMT and 'WS-PAGE-TOTAL' (no separator) + WS-PAGE-TOTAL, both as 11 digits followed by a trailing sign character, before the double-count addition.

**Source**: `app/cbl/CBTRN03C.cbl:198-199`

**Fields**: `TRAN-AMT`, `WS-PAGE-TOTAL`

**Category**: sysout

**Test**: Given p01, last record -15.75, running page total -10.50; When EOF is reached; Then SYSOUT lines 'TRAN-AMT 00000001575-' and 'WS-PAGE-TOTAL00000001050-'.

**Proven by**: fixtures/posting/p01-baseline-mixed, fixtures/posting/p06-credits-new-category

### BR-POST-045 Grand Total line

**Rule**: 1110-WRITE-GRAND-TOTALS writes 'Grand Total' + 86 dots + WS-GRAND-TOTAL edited +ZZZ,ZZZ,ZZZ.ZZ as the last report line; it does not add a dash line or update counters.

**Source**: `app/cbl/CBTRN03C.cbl:318-322`; `app/cpy/CVTRA07Y.cpy:62-66`

**Fields**: `WS-GRAND-TOTAL`, `REPT-GRAND-TOTAL`

**Category**: data-mapping

**Test**: Given p01 page total -26.25 at EOF; When grand totals are written; Then last report line 'Grand Total......................................................................................-         26.25'.

**Proven by**: fixtures/posting/p01-baseline-mixed

### BR-POST-046 CBTRN03C lookup failures abend

**Rule**: If the card is not in CARDXREF, the type not in TRANTYPE, or type+category not in TRANCATG, CBTRN03C displays 'INVALID CARD NUMBER : ' / 'INVALID TRANSACTION TYPE : ' / 'INVALID TRAN CATG KEY : ' with the key, forces IO-STATUS 23 ('FILE STATUS IS: NNNN0023') and abends (BR-POST-028); partial report output already written remains.

**Source**: `app/cbl/CBTRN03C.cbl:484-512`

**Fields**: `FD-XREF-CARD-NUM`, `FD-TRAN-TYPE`, `FD-TRAN-CAT-KEY`, `IO-STATUS`

**Category**: return-code

**Test**: Given a posted transaction with type 01 / category 0009 that has no TRANCATG record; When STEP10R reports it; Then SYSOUT 'INVALID TRAN CATG KEY : 010009' / 'FILE STATUS IS: NNNN0023' / 'ABENDING PROGRAM'; step ends abnormally (RC 12 off-host).

**Proven by**: source-only

### BR-POST-047 CBTRN03C I/O error messages

**Rule**: Open, read, write and close failures display a file-specific message, the file-status line and abend: ERROR OPENING TRANFILE / REPTFILE / CROSS REF FILE / TRANSACTION TYPE FILE / TRANSACTION CATG FILE / DATE PARM FILE, ERROR READING TRANSACTION FILE, ERROR READING DATEPARM FILE, ERROR WRITING REPTFILE, ERROR CLOSING POSTED TRANSACTION FILE / REPORT FILE / CROSS REF FILE / TRANSACTION TYPE FILE / TRANSACTION CATG FILE / DATE PARM FILE.

**Source**: `app/cbl/CBTRN03C.cbl:248-272`; `app/cbl/CBTRN03C.cbl:343-359`; `app/cbl/CBTRN03C.cbl:376-482`; `app/cbl/CBTRN03C.cbl:514-621`

**Fields**: `TRANFILE-STATUS`, `TRANREPT-STATUS`, `CARDXREF-STATUS`, `TRANTYPE-STATUS`, `TRANCATG-STATUS`, `DATEPARM-STATUS`

**Category**: sysout

**Test**: Given the DATEPARM dataset is not allocated (open status '35'); When STEP10R starts; Then SYSOUT: START OF EXECUTION OF PROGRAM CBTRN03C / ERROR OPENING DATE PARM FILE / FILE STATUS IS: NNNN0035 / ABENDING PROGRAM.

**Proven by**: source-only

```json
{
  "slice": "posting",
  "rules": [
    {
      "id": "BR-POST-001",
      "title": "CBTRN02C SYSOUT sequence and counters",
      "rule": "CBTRN02C writes exactly these SYSOUT lines in order: 'START OF EXECUTION OF PROGRAM CBTRN02C', any per-record TCATBAL 'Creating' lines, then after all files are closed 'TRANSACTIONS PROCESSED :' and 'TRANSACTIONS REJECTED  :' (two spaces before the colon) each followed by the unsigned 9-digit zero-padded counter (PIC 9(09)), then 'END OF EXECUTION OF PROGRAM CBTRN02C'. The processed count includes rejected records.",
      "category": "sysout",
      "sources": [
        {
          "file": "app/cbl/CBTRN02C.cbl",
          "startLine": 184,
          "endLine": 186
        },
        {
          "file": "app/cbl/CBTRN02C.cbl",
          "startLine": 194,
          "endLine": 194
        },
        {
          "file": "app/cbl/CBTRN02C.cbl",
          "startLine": 227,
          "endLine": 228
        },
        {
          "file": "app/cbl/CBTRN02C.cbl",
          "startLine": 232,
          "endLine": 232
        }
      ],
      "fields": [
        "WS-TRANSACTION-COUNT",
        "WS-REJECT-COUNT"
      ],
      "given": "DALYTRAN with 2 records, one of which is overlimit (p04 input)",
      "when": "POSTTRAN STEP15 runs",
      "then": "SYSOUT is START OF EXECUTION OF PROGRAM CBTRN02C / TRANSACTIONS PROCESSED :000000002 / TRANSACTIONS REJECTED  :000000001 / END OF EXECUTION OF PROGRAM CBTRN02C",
      "fixtures": [
        "fixtures/posting/p04-overlimit-reject",
        "fixtures/posting/p01-baseline-mixed"
      ],
      "finding": null
    },
    {
      "id": "BR-POST-002",
      "title": "Sequential read loop over DALYTRAN",
      "rule": "DALYTRAN is read sequentially; status '00' processes the record and adds 1 to WS-TRANSACTION-COUNT before validation, status '10' sets END-OF-FILE='Y' and ends the loop, any other status is an I/O error (see BR-POST-025). Each record's validation trailer is reset to reason 0 / spaces before validation.",
      "category": "control-flow",
      "sources": [
        {
          "file": "app/cbl/CBTRN02C.cbl",
          "startLine": 202,
          "endLine": 219
        },
        {
          "file": "app/cbl/CBTRN02C.cbl",
          "startLine": 345,
          "endLine": 369
        }
      ],
      "fields": [
        "DALYTRAN-RECORD",
        "DALYTRAN-STATUS",
        "END-OF-FILE",
        "WS-VALIDATION-FAIL-REASON",
        "WS-VALIDATION-FAIL-REASON-DESC"
      ],
      "given": "DALYTRAN with 4 valid records",
      "when": "CBTRN02C reads to end of file",
      "then": "4 records are validated and posted in input order; TRANSACTIONS PROCESSED :000000004",
      "fixtures": [
        "fixtures/posting/p01-baseline-mixed"
      ],
      "finding": null
    },
    {
      "id": "BR-POST-003",
      "title": "Validation order: card cross-reference first, then account",
      "rule": "1500-VALIDATE-TRAN looks up the card in XREFFILE first; the account lookup and the limit/expiry checks run only if the card lookup left reason 0. No other validation exists (the source carries the comment 'ADD MORE VALIDATIONS HERE').",
      "category": "validation",
      "sources": [
        {
          "file": "app/cbl/CBTRN02C.cbl",
          "startLine": 370,
          "endLine": 378
        }
      ],
      "fields": [
        "DALYTRAN-CARD-NUM",
        "XREF-ACCT-ID",
        "WS-VALIDATION-FAIL-REASON"
      ],
      "given": "a transaction whose card 4000999999999999 has no CARDXREF record",
      "when": "it is validated",
      "then": "it is rejected with reason 0100 and ACCTFILE is never read for it",
      "fixtures": [
        "fixtures/posting/p02-invalid-card"
      ],
      "finding": null
    },
    {
      "id": "BR-POST-004",
      "title": "Reject reason 100: card number not in CARDXREF",
      "rule": "A random READ of XREFFILE by DALYTRAN-CARD-NUM that raises INVALID KEY sets reason 100 and description 'INVALID CARD NUMBER FOUND'. On success CARD-XREF-RECORD (including XREF-ACCT-ID) is loaded for the later steps.",
      "category": "validation",
      "sources": [
        {
          "file": "app/cbl/CBTRN02C.cbl",
          "startLine": 380,
          "endLine": 392
        }
      ],
      "fields": [
        "DALYTRAN-CARD-NUM",
        "FD-XREF-CARD-NUM",
        "CARD-XREF-RECORD",
        "XREF-ACCT-ID"
      ],
      "given": "DALYTRAN record 0000000000000002 with card 4000999999999999 and no matching CARDXREF row",
      "when": "CBTRN02C validates it",
      "then": "one DALYREJS record with trailer '0100INVALID CARD NUMBER FOUND'; STEP15 RC 4",
      "fixtures": [
        "fixtures/posting/p02-invalid-card"
      ],
      "finding": null
    },
    {
      "id": "BR-POST-005",
      "title": "Reject reason 101: account record not found",
      "rule": "With a valid cross-reference, ACCTFILE is read by XREF-ACCT-ID; INVALID KEY sets reason 101 'ACCOUNT RECORD NOT FOUND' and neither the limit nor the expiry check runs.",
      "category": "validation",
      "sources": [
        {
          "file": "app/cbl/CBTRN02C.cbl",
          "startLine": 393,
          "endLine": 399
        }
      ],
      "fields": [
        "XREF-ACCT-ID",
        "FD-ACCT-ID",
        "ACCOUNT-RECORD"
      ],
      "given": "card 4000000000001099 maps to account 00000001099 which is absent from ACCTDATA",
      "when": "the transaction is validated",
      "then": "DALYREJS trailer '0101ACCOUNT RECORD NOT FOUND'; TRANSACTIONS REJECTED  :000000001; RC 4",
      "fixtures": [
        "fixtures/posting/p03-account-missing"
      ],
      "finding": null
    },
    {
      "id": "BR-POST-006",
      "title": "Credit-limit check uses cycle amounts, not the current balance",
      "rule": "WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT. The transaction passes when ACCT-CREDIT-LIMIT >= WS-TEMP-BAL (equality accepted); otherwise reason 102 'OVERLIMIT TRANSACTION'. ACCT-CURR-BAL plays no part. Because accepted transactions update the cycle fields immediately (BR-POST-020), later records in the same run see the new cycle totals.",
      "category": "calculation",
      "sources": [
        {
          "file": "app/cbl/CBTRN02C.cbl",
          "startLine": 403,
          "endLine": 413
        }
      ],
      "fields": [
        "ACCT-CURR-CYC-CREDIT",
        "ACCT-CURR-CYC-DEBIT",
        "DALYTRAN-AMT",
        "ACCT-CREDIT-LIMIT",
        "WS-TEMP-BAL"
      ],
      "given": "account 1003 with ACCT-CREDIT-LIMIT 1000.00, ACCT-CURR-CYC-CREDIT 950.00, ACCT-CURR-CYC-DEBIT 0.00; purchases of 50.00 then 100.00",
      "when": "both are posted in one run",
      "then": "50.00 is accepted (950-0+50 = 1000 <= 1000, cycle credit becomes 1000.00); 100.00 is rejected with '0102OVERLIMIT TRANSACTION' (1000+100 = 1100 > 1000)",
      "fixtures": [
        "fixtures/posting/p04-overlimit-reject"
      ],
      "finding": null
    },
    {
      "id": "BR-POST-007",
      "title": "Payments and credits make the limit check stricter",
      "rule": "Negative amounts are added to ACCT-CURR-CYC-DEBIT (BR-POST-020), so the field holds a negative number; the formula then subtracts it, which adds the absolute value of every payment/credit to WS-TEMP-BAL. A payment therefore increases, rather than reduces, the exposure used by later limit checks.",
      "category": "calculation",
      "sources": [
        {
          "file": "app/cbl/CBTRN02C.cbl",
          "startLine": 403,
          "endLine": 407
        },
        {
          "file": "app/cbl/CBTRN02C.cbl",
          "startLine": 548,
          "endLine": 552
        }
      ],
      "fields": [
        "ACCT-CURR-CYC-CREDIT",
        "ACCT-CURR-CYC-DEBIT",
        "WS-TEMP-BAL",
        "ACCT-CREDIT-LIMIT"
      ],
      "given": "limit 1000.00, cycle credit 900.00, cycle debit 0.00; a payment of -200.00 followed by a purchase of 150.00",
      "when": "both are posted in one run",
      "then": "the payment posts (900-0-200 = 700) and cycle debit becomes -200.00; the purchase computes 900-(-200)+150 = 1250 > 1000 and is rejected with reason 102 although the net cycle spend would be 850.00. p06 proves the negative storage of ACCT-CURR-CYC-DEBIT (-325.50 after a -300.00 payment and -25.50 credit)",
      "fixtures": [
        "fixtures/posting/p06-credits-new-category"
      ],
      "finding": "Looks wrong (sign convention mismatch between 2800-UPDATE-ACCOUNT-REC and the limit formula) but must be reproduced exactly."
    },
    {
      "id": "BR-POST-008",
      "title": "WS-TEMP-BAL truncates the high-order digit",
      "rule": "WS-TEMP-BAL is PIC S9(09)V99 while the cycle fields are S9(10)V99; COMPUTE has no ON SIZE ERROR, so a result of 1,000,000,000.00 or more silently loses its leading digit before the comparison.",
      "category": "edge-case",
      "sources": [
        {
          "file": "app/cbl/CBTRN02C.cbl",
          "startLine": 187,
          "endLine": 187
        },
        {
          "file": "app/cbl/CBTRN02C.cbl",
          "startLine": 403,
          "endLine": 407
        }
      ],
      "fields": [
        "WS-TEMP-BAL",
        "ACCT-CURR-CYC-CREDIT",
        "ACCT-CURR-CYC-DEBIT",
        "DALYTRAN-AMT"
      ],
      "given": "ACCT-CREDIT-LIMIT 100.00, ACCT-CURR-CYC-CREDIT 1000000000.00, cycle debit 0.00, amount 50.00",
      "when": "the transaction is validated",
      "then": "WS-TEMP-BAL holds 50.00 (1000000050.00 truncated), 100.00 >= 50.00, so the transaction is accepted instead of rejected",
      "fixtures": [],
      "finding": "Silent high-order truncation can let an overlimit transaction through; reproduce it (do not widen the field)."
    },
    {
      "id": "BR-POST-009",
      "title": "Expiry check: expiration date >= origin date, compared as text",
      "rule": "ACCT-EXPIRAION-DATE (X(10)) is compared alphanumerically with DALYTRAN-ORIG-TS(1:10); the transaction passes when expiry >= origin date (same-day accepted), else reason 103 'TRANSACTION RECEIVED AFTER ACCT EXPIRATION'. Because it is a character comparison, a blank or '0000-00-00' expiry date always fails and non-ISO dates compare lexically.",
      "category": "validation",
      "sources": [
        {
          "file": "app/cbl/CBTRN02C.cbl",
          "startLine": 414,
          "endLine": 420
        },
        {
          "file": "app/cpy/CVACT01Y.cpy",
          "startLine": 11,
          "endLine": 11
        },
        {
          "file": "app/cpy/CVTRA06Y.cpy",
          "startLine": 16,
          "endLine": 16
        }
      ],
      "fields": [
        "ACCT-EXPIRAION-DATE",
        "DALYTRAN-ORIG-TS"
      ],
      "given": "account 1004 expires 2022-01-31 and account 1005 expires 2022-07-04; both receive a transaction with origin 2022-07-04-09.15.00.000000",
      "when": "the transactions are validated",
      "then": "1004's is rejected with '0103TRANSACTION RECEIVED AFTER ACCT EXPIRATION'; 1005's posts (2022-07-04 >= 2022-07-04)",
      "fixtures": [
        "fixtures/posting/p05-expired-account"
      ],
      "finding": null
    },
    {
      "id": "BR-POST-010",
      "title": "Overlimit reason is overwritten by expiry reason",
      "rule": "The limit and expiry checks are independent IFs; both run whenever the account exists. If both fail, the later MOVE wins, so the reject carries 103 only and the 102 condition is not reported.",
      "category": "validation",
      "sources": [
        {
          "file": "app/cbl/CBTRN02C.cbl",
          "startLine": 407,
          "endLine": 420
        }
      ],
      "fields": [
        "WS-VALIDATION-FAIL-REASON",
        "WS-VALIDATION-FAIL-REASON-DESC"
      ],
      "given": "an expired account (expiry 2022-01-31) that is also over its limit",
      "when": "a 2022-07-04 transaction is validated",
      "then": "one reject with trailer '0103TRANSACTION RECEIVED AFTER ACCT EXPIRATION'; no 0102 record",
      "fixtures": [],
      "finding": "Only the last failing reason is recorded; reproduce the 103-over-102 precedence."
    },
    {
      "id": "BR-POST-011",
      "title": "No active-status, cash-limit or type/category validation",
      "rule": "Posting does not test ACCT-ACTIVE-STATUS, ACCT-CASH-CREDIT-LIMIT, or whether the type/category code exists: cash advances count against the general credit limit only, and inactive accounts are posted normally.",
      "category": "validation",
      "sources": [
        {
          "file": "app/cbl/CBTRN02C.cbl",
          "startLine": 370,
          "endLine": 378
        },
        {
          "file": "app/cpy/CVACT01Y.cpy",
          "startLine": 6,
          "endLine": 9
        }
      ],
      "fields": [
        "ACCT-ACTIVE-STATUS",
        "ACCT-CASH-CREDIT-LIMIT",
        "DALYTRAN-TYPE-CD",
        "DALYTRAN-CAT-CD"
      ],
      "given": "account 1006 (cash credit limit 2000.00) and a type 01 / category 0002 'Cash advance at ATM' of 60.00",
      "when": "CBTRN02C runs",
      "then": "the cash advance posts with no cash-limit test and a new TCATBAL record (1006,01,0002) is created",
      "fixtures": [
        "fixtures/posting/p06-credits-new-category"
      ],
      "finding": "Missing validations (inactive accounts and cash limit are ignored) must be reproduced, not added."
    },
    {
      "id": "BR-POST-012",
      "title": "430-byte reject record",
      "rule": "A rejected transaction is written to DALYREJS(+1) (RECFM=F, LRECL=430) as the 350-byte DALYTRAN-RECORD copied unchanged followed by the 80-byte trailer: reason PIC 9(04) zero-padded (0100..0103) and a 76-byte description padded with spaces.",
      "category": "data-mapping",
      "sources": [
        {
          "file": "app/cbl/CBTRN02C.cbl",
          "startLine": 81,
          "endLine": 84
        },
        {
          "file": "app/cbl/CBTRN02C.cbl",
          "startLine": 176,
          "endLine": 182
        },
        {
          "file": "app/cbl/CBTRN02C.cbl",
          "startLine": 446,
          "endLine": 451
        },
        {
          "file": "app/jcl/POSTTRAN.jcl",
          "startLine": 34,
          "endLine": 38
        },
        {
          "file": "tools/parity/layouts/REJTRAN.cpy",
          "startLine": 7,
          "endLine": 24
        }
      ],
      "fields": [
        "REJECT-TRAN-DATA",
        "VALIDATION-TRAILER",
        "WS-VALIDATION-FAIL-REASON",
        "WS-VALIDATION-FAIL-REASON-DESC",
        "REJ-FAIL-REASON",
        "REJ-FAIL-REASON-DESC"
      ],
      "given": "the invalid-card transaction of p02",
      "when": "it is rejected",
      "then": "DALYREJS.G0001V00 holds one 430-byte record: the original 350 bytes (including its DALYTRAN-PROC-TS) then '0100INVALID CARD NUMBER FOUND' padded to 80",
      "fixtures": [
        "fixtures/posting/p02-invalid-card",
        "fixtures/posting/p03-account-missing",
        "fixtures/posting/p05-expired-account"
      ],
      "finding": null
    },
    {
      "id": "BR-POST-013",
      "title": "Rejected transactions change no master file",
      "rule": "A record with a non-zero reason only increments WS-REJECT-COUNT and is written to DALYREJS; TCATBALF, ACCTDATA and TRANSACT are not touched for it.",
      "category": "state-change",
      "sources": [
        {
          "file": "app/cbl/CBTRN02C.cbl",
          "startLine": 211,
          "endLine": 216
        }
      ],
      "fields": [
        "WS-REJECT-COUNT",
        "TRAN-CAT-BAL",
        "ACCT-CURR-BAL",
        "TRAN-RECORD"
      ],
      "given": "p04: one accepted 50.00 and one rejected 100.00 purchase on account 1003 (balance 950.00, TCATBAL 950.00)",
      "when": "CBTRN02C runs",
      "then": "ACCT-CURR-BAL and TRAN-CAT-BAL both end at 1000.00 and TRANSACT holds 1 record",
      "fixtures": [
        "fixtures/posting/p04-overlimit-reject"
      ],
      "finding": null
    },
    {
      "id": "BR-POST-014",
      "title": "Return code 4 when any record is rejected",
      "rule": "After closing all files, RETURN-CODE is set to 4 if WS-REJECT-COUNT > 0; otherwise it stays 0. Rejects never stop the run. TRANREPT still runs after RC 4 (no COND on the job).",
      "category": "return-code",
      "sources": [
        {
          "file": "app/cbl/CBTRN02C.cbl",
          "startLine": 229,
          "endLine": 231
        },
        {
          "file": "app/cbl/CBTRN02C.cbl",
          "startLine": 234,
          "endLine": 234
        }
      ],
      "fields": [
        "WS-REJECT-COUNT",
        "RETURN-CODE"
      ],
      "given": "a daily file with 1 reject among 2 records",
      "when": "POSTTRAN runs",
      "then": "rc.json POSTTRAN STEP15 = 4 (maxrc 4); with zero rejects (p01) STEP15 = 0",
      "fixtures": [
        "fixtures/posting/p02-invalid-card",
        "fixtures/posting/p04-overlimit-reject",
        "fixtures/posting/p01-baseline-mixed"
      ],
      "finding": null
    },
    {
      "id": "BR-POST-015",
      "title": "Posted transaction record mapping",
      "rule": "For an accepted record, TRAN-RECORD gets ID, type, category, source, description, amount, merchant id/name/city/zip, card number and ORIG-TS copied field by field from DALYTRAN-RECORD; DALYTRAN-PROC-TS is ignored and replaced (BR-POST-016). The record is written to the TRANSACT KSDS keyed by TRAN-ID.",
      "category": "data-mapping",
      "sources": [
        {
          "file": "app/cbl/CBTRN02C.cbl",
          "startLine": 424,
          "endLine": 436
        },
        {
          "file": "app/cpy/CVTRA05Y.cpy",
          "startLine": 4,
          "endLine": 18
        },
        {
          "file": "app/cpy/CVTRA06Y.cpy",
          "startLine": 4,
          "endLine": 18
        }
      ],
      "fields": [
        "TRAN-ID",
        "TRAN-TYPE-CD",
        "TRAN-CAT-CD",
        "TRAN-SOURCE",
        "TRAN-DESC",
        "TRAN-AMT",
        "TRAN-MERCHANT-ID",
        "TRAN-MERCHANT-NAME",
        "TRAN-MERCHANT-CITY",
        "TRAN-MERCHANT-ZIP",
        "TRAN-CARD-NUM",
        "TRAN-ORIG-TS"
      ],
      "given": "DALYTRAN 0000000000000001 'Bookshop purchase' 125.00 card 4000000000001001 origin 2022-07-04-09.15.00.000000",
      "when": "it is posted",
      "then": "TRANSACT record 0000000000000001 carries the same field values, ORIG-TS 2022-07-04-09.15.00.000000",
      "fixtures": [
        "fixtures/posting/p01-baseline-mixed"
      ],
      "finding": null
    },
    {
      "id": "BR-POST-016",
      "title": "PROC-TS from the frozen clock in DB2 timestamp format",
      "rule": "TRAN-PROC-TS is built from FUNCTION CURRENT-DATE as YYYY-MM-DD-HH.MM.SS.hh0000 (hundredths of a second followed by literal '0000'). With the chain clock 2022-07-05 10:00:00.00 every posted record gets '2022-07-05-10.00.00.000000'.",
      "category": "data-mapping",
      "sources": [
        {
          "file": "app/cbl/CBTRN02C.cbl",
          "startLine": 149,
          "endLine": 174
        },
        {
          "file": "app/cbl/CBTRN02C.cbl",
          "startLine": 437,
          "endLine": 438
        },
        {
          "file": "app/cbl/CBTRN02C.cbl",
          "startLine": 692,
          "endLine": 705
        }
      ],
      "fields": [
        "TRAN-PROC-TS",
        "COBOL-TS",
        "DB2-FORMAT-TS",
        "DB2-MIL",
        "DB2-REST"
      ],
      "given": "COB_CURRENT_DATE=2022-07-05 10:00:00.00",
      "when": "any transaction is posted",
      "then": "its TRAN-PROC-TS is 2022-07-05-10.00.00.000000",
      "fixtures": [
        "fixtures/posting/p01-baseline-mixed",
        "fixtures/posting/p06-credits-new-category"
      ],
      "finding": null
    },
    {
      "id": "BR-POST-017",
      "title": "Update order: TCATBAL, then account, then TRANSACT",
      "rule": "2000-POST-TRANSACTION performs 2700-UPDATE-TCATBAL, then 2800-UPDATE-ACCOUNT-REC, then 2900-WRITE-TRANSACTION-FILE. There is no rollback: a later failure leaves the earlier updates in place (BR-POST-024).",
      "category": "control-flow",
      "sources": [
        {
          "file": "app/cbl/CBTRN02C.cbl",
          "startLine": 440,
          "endLine": 442
        }
      ],
      "fields": [
        "TRAN-CAT-BAL-RECORD",
        "ACCOUNT-RECORD",
        "TRAN-RECORD"
      ],
      "given": "a valid purchase of 125.00 on account 1001",
      "when": "it is posted",
      "then": "TCATBAL (1001,01,0001) goes 500.00 -> 625.00, ACCT-CURR-BAL 500.00 -> 425.00 after the whole day, and the TRANSACT record is written last",
      "fixtures": [
        "fixtures/posting/p01-baseline-mixed"
      ],
      "finding": null
    },
    {
      "id": "BR-POST-018",
      "title": "TCATBAL update of an existing category balance",
      "rule": "The TCATBAL key is XREF-ACCT-ID (11) + DALYTRAN-TYPE-CD (2) + DALYTRAN-CAT-CD (4). If the record exists, DALYTRAN-AMT is added to TRAN-CAT-BAL (signed, no rounding) and the record is REWRITten. A read status other than '00' or '23' displays 'ERROR READING TRANSACTION BALANCE FILE' and abends; a REWRITE failure displays 'ERROR REWRITING TRANSACTION BALANCE FILE' and abends.",
      "category": "state-change",
      "sources": [
        {
          "file": "app/cbl/CBTRN02C.cbl",
          "startLine": 467,
          "endLine": 501
        },
        {
          "file": "app/cbl/CBTRN02C.cbl",
          "startLine": 526,
          "endLine": 542
        },
        {
          "file": "app/cpy/CVTRA01Y.cpy",
          "startLine": 4,
          "endLine": 10
        }
      ],
      "fields": [
        "FD-TRANCAT-ACCT-ID",
        "FD-TRANCAT-TYPE-CD",
        "FD-TRANCAT-CD",
        "TRAN-CAT-BAL"
      ],
      "given": "TCATBAL (1001,02,0001) = 0.00 and a payment of -200.00 on card 4000000000001001",
      "when": "it is posted",
      "then": "TRAN-CAT-BAL for 00000001001020001 becomes -200.00",
      "fixtures": [
        "fixtures/posting/p01-baseline-mixed"
      ],
      "finding": null
    },
    {
      "id": "BR-POST-019",
      "title": "TCATBAL creation for a new account/type/category",
      "rule": "When the TCATBAL READ raises INVALID KEY (status '23'), CBTRN02C displays 'TCATBAL record not found for key : ' + the 17-character key + '.. Creating.', INITIALIZEs TRAN-CAT-BAL-RECORD, sets the key, adds the amount to the zeroed balance and WRITEs a new record. A WRITE failure displays 'ERROR WRITING TRANSACTION BALANCE FILE' and abends.",
      "category": "state-change",
      "sources": [
        {
          "file": "app/cbl/CBTRN02C.cbl",
          "startLine": 473,
          "endLine": 479
        },
        {
          "file": "app/cbl/CBTRN02C.cbl",
          "startLine": 495,
          "endLine": 499
        },
        {
          "file": "app/cbl/CBTRN02C.cbl",
          "startLine": 503,
          "endLine": 524
        }
      ],
      "fields": [
        "TRAN-CAT-KEY",
        "TRANCAT-ACCT-ID",
        "TRANCAT-TYPE-CD",
        "TRANCAT-CD",
        "TRAN-CAT-BAL"
      ],
      "given": "account 1006 has only TCATBAL (1006,01,0001); transactions with type/cat 02/0001 (-300.00), 03/0001 (-25.50) and 01/0002 (60.00)",
      "when": "CBTRN02C runs",
      "then": "SYSOUT shows 'TCATBAL record not found for key : 00000001006020001.. Creating.' and the same for 030001 and 010002; TCATBALF gains records with balances -300.00, -25.50 and 60.00",
      "fixtures": [
        "fixtures/posting/p06-credits-new-category"
      ],
      "finding": null
    },
    {
      "id": "BR-POST-020",
      "title": "Account balance and cycle-bucket update",
      "rule": "DALYTRAN-AMT is added to ACCT-CURR-BAL. If the amount is >= 0 (zero included) it is added to ACCT-CURR-CYC-CREDIT, otherwise the negative amount is added to ACCT-CURR-CYC-DEBIT, so that field accumulates negative values. The account record is then REWRITten.",
      "category": "state-change",
      "sources": [
        {
          "file": "app/cbl/CBTRN02C.cbl",
          "startLine": 545,
          "endLine": 554
        },
        {
          "file": "app/cpy/CVACT01Y.cpy",
          "startLine": 7,
          "endLine": 7
        },
        {
          "file": "app/cpy/CVACT01Y.cpy",
          "startLine": 13,
          "endLine": 14
        }
      ],
      "fields": [
        "ACCT-CURR-BAL",
        "ACCT-CURR-CYC-CREDIT",
        "ACCT-CURR-CYC-DEBIT",
        "DALYTRAN-AMT"
      ],
      "given": "account 1001 with balance 500.00, cycle credit 0.00, cycle debit 0.00; posts of +125.00 and -200.00",
      "when": "CBTRN02C runs",
      "then": "ACCT-CURR-BAL 425.00, ACCT-CURR-CYC-CREDIT 125.00, ACCT-CURR-CYC-DEBIT -200.00",
      "fixtures": [
        "fixtures/posting/p01-baseline-mixed",
        "fixtures/posting/p06-credits-new-category"
      ],
      "finding": null
    },
    {
      "id": "BR-POST-021",
      "title": "Account REWRITE failure reason 109 is never acted on",
      "rule": "If the account REWRITE raises INVALID KEY, reason 109 'ACCOUNT RECORD NOT FOUND' is moved to the trailer, but nothing tests it afterwards: the TRANSACT record is still written, no reject record is produced, WS-REJECT-COUNT and the RC are unchanged, and the reason is reset for the next record.",
      "category": "edge-case",
      "sources": [
        {
          "file": "app/cbl/CBTRN02C.cbl",
          "startLine": 554,
          "endLine": 559
        },
        {
          "file": "app/cbl/CBTRN02C.cbl",
          "startLine": 208,
          "endLine": 216
        }
      ],
      "fields": [
        "WS-VALIDATION-FAIL-REASON",
        "WS-VALIDATION-FAIL-REASON-DESC"
      ],
      "given": "an account REWRITE that returns INVALID KEY after a successful read",
      "when": "the transaction is posted",
      "then": "the transaction is still written to TRANSACT, DALYREJS gets nothing, TRANSACTIONS REJECTED is unchanged and RC stays 0",
      "fixtures": [],
      "finding": "Dead error handling (code 109 is set but ignored); reproduce it, do not turn it into a reject."
    },
    {
      "id": "BR-POST-022",
      "title": "Amounts are exact: no rounding anywhere in posting",
      "rule": "All posting arithmetic (ADD to TRAN-CAT-BAL, ACCT-CURR-BAL, cycle fields) works on two-decimal zoned fields with no ROUNDED phrase and no division, so results are exact to the cent; signs travel as zoned overpunch (e.g. '0000000157N' = -15.75, '0000001250{' = +125.00). Half cents cannot occur because input amounts have 2 decimals.",
      "category": "rounding",
      "sources": [
        {
          "file": "app/cbl/CBTRN02C.cbl",
          "startLine": 508,
          "endLine": 508
        },
        {
          "file": "app/cbl/CBTRN02C.cbl",
          "startLine": 527,
          "endLine": 527
        },
        {
          "file": "app/cbl/CBTRN02C.cbl",
          "startLine": 547,
          "endLine": 552
        },
        {
          "file": "app/cpy/CVTRA06Y.cpy",
          "startLine": 10,
          "endLine": 10
        }
      ],
      "fields": [
        "DALYTRAN-AMT",
        "TRAN-AMT",
        "TRAN-CAT-BAL",
        "ACCT-CURR-BAL"
      ],
      "given": "a refund of -15.75 (zoned '0000000157N') and a purchase of 80.25 ('0000000802E') on account 1002 with balance 120.05",
      "when": "they are posted",
      "then": "ACCT-CURR-BAL becomes 126.50 ('00000012650{'), ACCT-CURR-CYC-DEBIT -15.75 ('00000000157N')",
      "fixtures": [
        "fixtures/posting/p01-baseline-mixed"
      ],
      "finding": null
    },
    {
      "id": "BR-POST-023",
      "title": "TRANSACT is opened OUTPUT",
      "rule": "CBTRN02C opens the TRANSACT KSDS with OPEN OUTPUT (not I-O/EXTEND), so the run creates the file contents afresh; any rows loaded by the TRANFILE setup job from DALYTRAN.PS.INIT are not carried forward off-host. After posting the file holds exactly the accepted transactions of the day, in TRAN-ID key order.",
      "category": "state-change",
      "sources": [
        {
          "file": "app/cbl/CBTRN02C.cbl",
          "startLine": 254,
          "endLine": 270
        },
        {
          "file": "app/jcl/TRANFILE.jcl",
          "startLine": 67,
          "endLine": 74
        }
      ],
      "fields": [
        "TRAN-RECORD",
        "TRANFILE-STATUS"
      ],
      "given": "an empty DALYTRAN.PS.INIT and 4 valid daily transactions",
      "when": "POSTTRAN runs",
      "then": "TRANSACT holds 4 records (manifest records: 4) keyed 0000000000000001..0000000000000004",
      "fixtures": [
        "fixtures/posting/p01-baseline-mixed",
        "fixtures/posting/p07-empty-day"
      ],
      "finding": "On z/OS OPEN OUTPUT on a loaded KSDS behaves differently unless the cluster is REUSE; the fixtures always start TRANSACT empty, so reproduce the off-host 'only today's postings' result."
    },
    {
      "id": "BR-POST-024",
      "title": "TRANSACT write failure abends after partial updates",
      "rule": "If WRITE to TRANSACT fails (e.g. duplicate TRAN-ID, status '22'), CBTRN02C displays 'ERROR WRITING TO TRANSACTION FILE', the file status line and 'ABENDING PROGRAM', then abends. The TCATBAL and account updates for that record are already rewritten, files are not closed by the program and the counter/END lines are not displayed.",
      "category": "edge-case",
      "sources": [
        {
          "file": "app/cbl/CBTRN02C.cbl",
          "startLine": 562,
          "endLine": 579
        },
        {
          "file": "app/cbl/CBTRN02C.cbl",
          "startLine": 440,
          "endLine": 442
        }
      ],
      "fields": [
        "TRAN-ID",
        "TRANFILE-STATUS",
        "IO-STATUS"
      ],
      "given": "two daily records with the same TRAN-ID 0000000000000001",
      "when": "the second is posted",
      "then": "SYSOUT ends with ERROR WRITING TO TRANSACTION FILE / FILE STATUS IS: NNNN0022 / ABENDING PROGRAM and the step ends abnormally (RC 12 via the off-host CEE3ABD stand-in); TCATBAL and ACCT already include the second amount",
      "fixtures": [],
      "finding": "No duplicate check and no rollback: balances are updated for a transaction that is never written. Reproduce."
    },
    {
      "id": "BR-POST-025",
      "title": "CBTRN02C I/O error messages",
      "rule": "Every open, read, write and close of the six files checks for status '00' (read also accepts '10' = EOF) and otherwise displays a file-specific message followed by the file-status line (BR-POST-027) and the abend (BR-POST-028). Messages: ERROR OPENING DALYTRAN, ERROR OPENING TRANSACTION FILE, ERROR OPENING CROSS REF FILE, ERROR OPENING DALY REJECTS FILE, ERROR OPENING ACCOUNT MASTER FILE, ERROR OPENING TRANSACTION BALANCE FILE, ERROR READING DALYTRAN FILE, ERROR WRITING TO REJECTS FILE, ERROR CLOSING DALYTRAN FILE, ERROR CLOSING TRANSACTION FILE, ERROR CLOSING CROSS REF FILE, ERROR CLOSING DAILY REJECTS FILE, ERROR CLOSING ACCOUNT FILE, ERROR CLOSING TRANSACTION BALANCE FILE.",
      "category": "sysout",
      "sources": [
        {
          "file": "app/cbl/CBTRN02C.cbl",
          "startLine": 236,
          "endLine": 343
        },
        {
          "file": "app/cbl/CBTRN02C.cbl",
          "startLine": 357,
          "endLine": 368
        },
        {
          "file": "app/cbl/CBTRN02C.cbl",
          "startLine": 457,
          "endLine": 464
        },
        {
          "file": "app/cbl/CBTRN02C.cbl",
          "startLine": 582,
          "endLine": 690
        }
      ],
      "fields": [
        "DALYTRAN-STATUS",
        "TRANFILE-STATUS",
        "XREFFILE-STATUS",
        "DALYREJS-STATUS",
        "ACCTFILE-STATUS",
        "TCATBALF-STATUS"
      ],
      "given": "the ACCTFILE KSDS is missing (open status '35')",
      "when": "CBTRN02C starts",
      "then": "SYSOUT: START OF EXECUTION OF PROGRAM CBTRN02C / ERROR OPENING ACCOUNT MASTER FILE / FILE STATUS IS: NNNN0035 / ABENDING PROGRAM",
      "fixtures": [],
      "finding": null
    },
    {
      "id": "BR-POST-026",
      "title": "DALYREJS close error reports the XREF file status",
      "rule": "9300-DALYREJS-CLOSE checks DALYREJS-STATUS but moves XREFFILE-STATUS into IO-STATUS before displaying it, so the printed file status belongs to the cross-reference file (normally '00').",
      "category": "sysout",
      "sources": [
        {
          "file": "app/cbl/CBTRN02C.cbl",
          "startLine": 637,
          "endLine": 653
        }
      ],
      "fields": [
        "DALYREJS-STATUS",
        "XREFFILE-STATUS",
        "IO-STATUS"
      ],
      "given": "CLOSE of DALYREJS fails with status '30' after XREFFILE closed with '00'",
      "when": "9300-DALYREJS-CLOSE runs",
      "then": "SYSOUT: ERROR CLOSING DAILY REJECTS FILE / FILE STATUS IS: NNNN0000 / ABENDING PROGRAM",
      "fixtures": [],
      "finding": "Copy-paste defect: wrong status variable displayed. Reproduce it."
    },
    {
      "id": "BR-POST-027",
      "title": "File-status display format",
      "rule": "9910-DISPLAY-IO-STATUS prints 'FILE STATUS IS: NNNN' followed by a 4-character code: for a numeric status not starting with '9' it is '00' + the 2-digit status (e.g. NNNN0023); for a non-numeric status or one starting with '9' it is the first status byte followed by the 3-digit binary value of the second byte (e.g. status '9' + X'04' -> NNNN9004). Same routine in both programs.",
      "category": "sysout",
      "sources": [
        {
          "file": "app/cbl/CBTRN02C.cbl",
          "startLine": 131,
          "endLine": 140
        },
        {
          "file": "app/cbl/CBTRN02C.cbl",
          "startLine": 714,
          "endLine": 727
        },
        {
          "file": "app/cbl/CBTRN03C.cbl",
          "startLine": 633,
          "endLine": 646
        }
      ],
      "fields": [
        "IO-STATUS",
        "IO-STAT1",
        "IO-STAT2",
        "IO-STATUS-04",
        "TWO-BYTES-BINARY"
      ],
      "given": "IO-STATUS = '23'",
      "when": "9910-DISPLAY-IO-STATUS runs",
      "then": "SYSOUT line 'FILE STATUS IS: NNNN0023'",
      "fixtures": [],
      "finding": null
    },
    {
      "id": "BR-POST-028",
      "title": "Abend via CEE3ABD with code 999",
      "rule": "9999-ABEND-PROGRAM displays 'ABENDING PROGRAM' and calls CEE3ABD with ABCODE 999 and TIMING 0 (user abend U999 on z/OS). Off-host the CEE3ABD stand-in (tools/stubs/CEE3ABD.cbl) displays the abend code and stops the run with RC 12; GnuCOBOL may then add 'libcob: warning: implicit CLOSE' runtime lines, which are not program output. Identical in CBTRN02C and CBTRN03C.",
      "category": "return-code",
      "sources": [
        {
          "file": "app/cbl/CBTRN02C.cbl",
          "startLine": 147,
          "endLine": 148
        },
        {
          "file": "app/cbl/CBTRN02C.cbl",
          "startLine": 707,
          "endLine": 711
        },
        {
          "file": "app/cbl/CBTRN03C.cbl",
          "startLine": 626,
          "endLine": 630
        }
      ],
      "fields": [
        "ABCODE",
        "TIMING",
        "RETURN-CODE"
      ],
      "given": "any unrecoverable I/O error",
      "when": "9999-ABEND-PROGRAM runs",
      "then": "SYSOUT 'ABENDING PROGRAM', the step ends with abend U999 (RC 12 off-host) and no END OF EXECUTION line",
      "fixtures": [],
      "finding": null
    },
    {
      "id": "BR-POST-029",
      "title": "Empty daily file posts nothing",
      "rule": "If DALYTRAN is empty the first read returns '10'; counters stay zero, nothing is written to TRANSACT or DALYREJS, ACCTDATA and TCATBALF are unchanged, RC is 0 and DALYREJS(+1) is still catalogued as an empty generation.",
      "category": "edge-case",
      "sources": [
        {
          "file": "app/cbl/CBTRN02C.cbl",
          "startLine": 202,
          "endLine": 234
        },
        {
          "file": "app/jcl/POSTTRAN.jcl",
          "startLine": 34,
          "endLine": 38
        }
      ],
      "fields": [
        "WS-TRANSACTION-COUNT",
        "WS-REJECT-COUNT",
        "RETURN-CODE"
      ],
      "given": "an empty AWS.M2.CARDDEMO.DALYTRAN.PS",
      "when": "POSTTRAN runs",
      "then": "SYSOUT TRANSACTIONS PROCESSED :000000000 / TRANSACTIONS REJECTED  :000000000; STEP15 RC 0; TRANSACT and DALYREJS.G0001V00 have 0 records",
      "fixtures": [
        "fixtures/posting/p07-empty-day"
      ],
      "finding": null
    },
    {
      "id": "BR-POST-030",
      "title": "TRANREPT unloads TRANSACT with IDCAMS REPRO",
      "rule": "TRANREPT's first step (STEP05R, proc REPROC, proc step PRC001) runs IDCAMS 'REPRO INFILE(FILEIN) OUTFILE(FILEOUT)' copying the TRANSACT KSDS in key (TRAN-ID) order into a new generation TRANSACT.BKUP(+1), RECFM=FB LRECL=350. The SORT step that follows is also named STEP05R.",
      "category": "control-flow",
      "sources": [
        {
          "file": "app/jcl/TRANREPT.jcl",
          "startLine": 19,
          "endLine": 33
        },
        {
          "file": "app/proc/REPROC.prc",
          "startLine": 21,
          "endLine": 28
        },
        {
          "file": "app/ctl/REPROCT.ctl",
          "startLine": 15,
          "endLine": 15
        }
      ],
      "fields": [
        "TRAN-RECORD",
        "TRAN-ID"
      ],
      "given": "TRANSACT holds the 4 postings of p01",
      "when": "TRANREPT runs",
      "then": "TRANSACT.BKUP.G0001V00 holds the same 4 records in TRAN-ID order; rc.json lists PRC001 = 0 and STEP05R = 0",
      "fixtures": [
        "fixtures/posting/p01-baseline-mixed"
      ],
      "finding": "Two steps share the name STEP05R (the REPRO proc step and the SORT step), which makes step references ambiguous; keep the step names as recorded in rc.json."
    },
    {
      "id": "BR-POST-031",
      "title": "SORT window is hard-coded in JCL SYMNAMES",
      "rule": "The DFSORT step keeps only records whose TRAN-PROC-DT (bytes 305-314 of TRAN-PROC-TS, CH) is >= '2022-01-01' and <= '2022-07-06' (inclusive). The bounds are literals in SYMNAMES, not taken from DATEPARM, so changing DATEPARM does not change which records reach CBTRN03C.",
      "category": "validation",
      "sources": [
        {
          "file": "app/jcl/TRANREPT.jcl",
          "startLine": 40,
          "endLine": 48
        }
      ],
      "fields": [
        "TRAN-PROC-TS"
      ],
      "given": "postings with TRAN-PROC-TS 2022-07-05-10.00.00.000000 (frozen clock)",
      "when": "the SORT runs",
      "then": "all records are included (2022-07-05 is inside 2022-01-01..2022-07-06); a record with proc date 2022-07-07 would be dropped regardless of DATEPARM",
      "fixtures": [
        "fixtures/posting/p01-baseline-mixed"
      ],
      "finding": "Report window is hard-coded in the JCL and duplicated in DATEPARM; reproduce the literal window."
    },
    {
      "id": "BR-POST-032",
      "title": "SORT by card number, stable",
      "rule": "SORT FIELDS=(TRAN-CARD-NUM,A) with TRAN-CARD-NUM at position 263 length 16 (ZD) orders the daily file by card; records with the same card keep their incoming TRAN-ID order. Output goes to TRANSACT.DALY(+1) with the SORTIN DCB.",
      "category": "control-flow",
      "sources": [
        {
          "file": "app/jcl/TRANREPT.jcl",
          "startLine": 37,
          "endLine": 46
        },
        {
          "file": "app/jcl/TRANREPT.jcl",
          "startLine": 51,
          "endLine": 55
        }
      ],
      "fields": [
        "TRAN-CARD-NUM",
        "TRAN-ID"
      ],
      "given": "p01 postings 1 and 3 on card ...1001 and 2 and 4 on card ...1002",
      "when": "the SORT runs",
      "then": "TRANSACT.DALY order is 0000000000000001, 0000000000000003, 0000000000000002, 0000000000000004",
      "fixtures": [
        "fixtures/posting/p01-baseline-mixed"
      ],
      "finding": null
    },
    {
      "id": "BR-POST-033",
      "title": "CBTRN03C SYSOUT banners and DATEPARM echo",
      "rule": "CBTRN03C displays 'START OF EXECUTION OF PROGRAM CBTRN03C', opens its six files, reads the single 80-byte DATEPARM record (start date bytes 1-10, one filler byte, end date bytes 12-21) and displays 'Reporting from ' start ' to ' end. At the end it displays 'END OF EXECUTION OF PROGRAM CBTRN03C'. STEP10R ends RC 0 unless it abends.",
      "category": "sysout",
      "sources": [
        {
          "file": "app/cbl/CBTRN03C.cbl",
          "startLine": 122,
          "endLine": 125
        },
        {
          "file": "app/cbl/CBTRN03C.cbl",
          "startLine": 160,
          "endLine": 168
        },
        {
          "file": "app/cbl/CBTRN03C.cbl",
          "startLine": 215,
          "endLine": 215
        },
        {
          "file": "app/cbl/CBTRN03C.cbl",
          "startLine": 220,
          "endLine": 233
        }
      ],
      "fields": [
        "WS-START-DATE",
        "WS-END-DATE",
        "FD-DATEPARM-REC"
      ],
      "given": "DATEPARM '2022-01-01 2022-07-06'",
      "when": "STEP10R runs",
      "then": "SYSOUT line 2 is 'Reporting from 2022-01-01 to 2022-07-06'; rc.json STEP10R = 0",
      "fixtures": [
        "fixtures/posting/p01-baseline-mixed",
        "fixtures/posting/p07-empty-day"
      ],
      "finding": null
    },
    {
      "id": "BR-POST-034",
      "title": "Empty DATEPARM ends the report before any record",
      "rule": "If the DATEPARM read returns '10' (empty file), END-OF-FILE is set to 'Y', no 'Reporting from' line is displayed and the main loop never executes: no transaction is read and the report is empty. Any other non-'00' status displays 'ERROR READING DATEPARM FILE' and abends.",
      "category": "edge-case",
      "sources": [
        {
          "file": "app/cbl/CBTRN03C.cbl",
          "startLine": 220,
          "endLine": 243
        },
        {
          "file": "app/cbl/CBTRN03C.cbl",
          "startLine": 170,
          "endLine": 171
        }
      ],
      "fields": [
        "DATEPARM-STATUS",
        "END-OF-FILE"
      ],
      "given": "an empty AWS.M2.CARDDEMO.DATEPARM and 4 sorted transactions",
      "when": "STEP10R runs",
      "then": "SYSOUT has only the START and END lines; TRANREPT(+1) has 0 records; RC 0",
      "fixtures": [],
      "finding": null
    },
    {
      "id": "BR-POST-035",
      "title": "Every reported record is echoed to SYSOUT",
      "rule": "Before formatting, CBTRN03C displays the full 350-byte TRAN-RECORD as raw text (zoned amounts with overpunch, e.g. '0000001250{') for every in-window record.",
      "category": "sysout",
      "sources": [
        {
          "file": "app/cbl/CBTRN03C.cbl",
          "startLine": 179,
          "endLine": 180
        }
      ],
      "fields": [
        "TRAN-RECORD"
      ],
      "given": "p01's 4 sorted transactions",
      "when": "STEP10R runs",
      "then": "SYSOUT lines 3-6 are the 4 raw 350-byte records in card order, the first starting '0000000000000001010001POS TERM  Bookshop purchase'",
      "fixtures": [
        "fixtures/posting/p01-baseline-mixed"
      ],
      "finding": null
    },
    {
      "id": "BR-POST-036",
      "title": "Out-of-window record ends the report via NEXT SENTENCE",
      "rule": "CBTRN03C re-tests TRAN-PROC-TS(1:10) against the DATEPARM window. For a record outside it the code executes NEXT SENTENCE, which jumps past the period after END-PERFORM, so the read loop ends at once: that record and all later ones are skipped and no page, account or grand totals are written.",
      "category": "control-flow",
      "sources": [
        {
          "file": "app/cbl/CBTRN03C.cbl",
          "startLine": 170,
          "endLine": 178
        },
        {
          "file": "app/cbl/CBTRN03C.cbl",
          "startLine": 206,
          "endLine": 208
        }
      ],
      "fields": [
        "TRAN-PROC-TS",
        "WS-START-DATE",
        "WS-END-DATE"
      ],
      "given": "DATEPARM '2022-07-06 2022-07-06' and sorted postings with proc date 2022-07-05 (inside the SORT window)",
      "when": "STEP10R runs",
      "then": "the first record fails the test, the loop exits, TRANREPT(+1) has 0 records and SYSOUT shows only START, 'Reporting from 2022-07-06 to 2022-07-06' and END",
      "fixtures": [],
      "finding": "NEXT SENTENCE inside an inline PERFORM acts as 'stop processing' rather than 'skip this record'; reproduce it."
    },
    {
      "id": "BR-POST-037",
      "title": "Empty day produces a zero-record report",
      "rule": "When TRANSACT.DALY is empty, the first read hits EOF while TRAN-RECORD is still its initial blank value; the blank TRAN-PROC-TS fails the date test, NEXT SENTENCE leaves the loop and the EOF totals branch never runs. The report has no headers, no totals, and SYSOUT has no TRAN-AMT / WS-PAGE-TOTAL lines.",
      "category": "edge-case",
      "sources": [
        {
          "file": "app/cbl/CBTRN03C.cbl",
          "startLine": 170,
          "endLine": 206
        }
      ],
      "fields": [
        "TRAN-RECORD",
        "TRAN-PROC-TS",
        "END-OF-FILE"
      ],
      "given": "no daily transactions (p07)",
      "when": "TRANREPT runs",
      "then": "TRANREPT.G0001V00 has 0 records (manifest records: 0); SYSOUT is START / Reporting from 2022-01-01 to 2022-07-06 / END; STEP10R RC 0",
      "fixtures": [
        "fixtures/posting/p07-empty-day"
      ],
      "finding": "The fixture README says headers are produced, but the golden output has 0 records; the golden file wins and the empty report must be reproduced."
    },
    {
      "id": "BR-POST-038",
      "title": "Report header block and 133-byte records",
      "rule": "Before the first detail line CBTRN03C writes REPORT-NAME-HEADER ('DALYREPT' in 38 bytes, 'Daily Transaction Report' in 41, 'Date Range: ', start, ' to ', end = 115 bytes), a blank line, TRANSACTION-HEADER-1 (column titles) and TRANSACTION-HEADER-2 (133 dashes), adding 4 to WS-LINE-COUNTER. Every report record is padded with spaces to LRECL 133 (RECFM=FB).",
      "category": "data-mapping",
      "sources": [
        {
          "file": "app/cbl/CBTRN03C.cbl",
          "startLine": 84,
          "endLine": 85
        },
        {
          "file": "app/cbl/CBTRN03C.cbl",
          "startLine": 274,
          "endLine": 280
        },
        {
          "file": "app/cbl/CBTRN03C.cbl",
          "startLine": 324,
          "endLine": 341
        },
        {
          "file": "app/cpy/CVTRA07Y.cpy",
          "startLine": 4,
          "endLine": 13
        },
        {
          "file": "app/cpy/CVTRA07Y.cpy",
          "startLine": 33,
          "endLine": 48
        },
        {
          "file": "app/jcl/TRANREPT.jcl",
          "startLine": 76,
          "endLine": 80
        }
      ],
      "fields": [
        "REPORT-NAME-HEADER",
        "REPT-START-DATE",
        "REPT-END-DATE",
        "TRANSACTION-HEADER-1",
        "TRANSACTION-HEADER-2",
        "WS-LINE-COUNTER"
      ],
      "given": "DATEPARM 2022-01-01 / 2022-07-06 and at least one in-window transaction",
      "when": "the first detail is written",
      "then": "report lines 1-4: 'DALYREPT ... Daily Transaction Report ... Date Range: 2022-01-01 to 2022-07-06', blank, 'Transaction ID   Account ID  Transaction Type   Tran Category                      Tran Source            Amount', 133 dashes",
      "fixtures": [
        "fixtures/posting/p01-baseline-mixed"
      ],
      "finding": null
    },
    {
      "id": "BR-POST-039",
      "title": "Detail line layout",
      "rule": "Each detail line is TRAN-ID (16), space, account id from XREF-ACCT-ID (11 digits), space, type code '-' type description truncated to 15, space, category code (4) '-' category description truncated to 29, space, TRAN-SOURCE (10), 4 spaces, TRAN-AMT edited -ZZZ,ZZZ,ZZZ.ZZ (leading '-' only for negatives, blank otherwise), 2 spaces. Descriptions come from random reads of TRANTYPE (by type) and TRANCATG (by type+category).",
      "category": "data-mapping",
      "sources": [
        {
          "file": "app/cbl/CBTRN03C.cbl",
          "startLine": 189,
          "endLine": 196
        },
        {
          "file": "app/cbl/CBTRN03C.cbl",
          "startLine": 361,
          "endLine": 374
        },
        {
          "file": "app/cpy/CVTRA07Y.cpy",
          "startLine": 15,
          "endLine": 31
        }
      ],
      "fields": [
        "TRAN-REPORT-TRANS-ID",
        "TRAN-REPORT-ACCOUNT-ID",
        "TRAN-REPORT-TYPE-CD",
        "TRAN-REPORT-TYPE-DESC",
        "TRAN-REPORT-CAT-CD",
        "TRAN-REPORT-CAT-DESC",
        "TRAN-REPORT-SOURCE",
        "TRAN-REPORT-AMT",
        "TRAN-TYPE-DESC",
        "TRAN-CAT-TYPE-DESC"
      ],
      "given": "posting 0000000000000003, account 1001, type 02 'Payment', category 0001 'Payment - Bank Transfer', source OPERATOR, amount -200.00",
      "when": "the detail is written",
      "then": "'0000000000000003 00000001001 02-Payment         0001-Payment - Bank Transfer       OPERATOR      -        200.00'",
      "fixtures": [
        "fixtures/posting/p01-baseline-mixed",
        "fixtures/posting/p06-credits-new-category"
      ],
      "finding": null
    },
    {
      "id": "BR-POST-040",
      "title": "Card break writes the Account Total",
      "rule": "When TRAN-CARD-NUM differs from WS-CURR-CARD-NUM and this is not the first record, CBTRN03C writes 'Account Total' + 84 dots + WS-ACCOUNT-TOTAL edited +ZZZ,ZZZ,ZZZ.ZZ ('+' or '-' sign), then a line of 133 dashes, resets the account total and adds 2 to the line counter. It then looks the new card up in CARDXREF (account id for the detail lines).",
      "category": "calculation",
      "sources": [
        {
          "file": "app/cbl/CBTRN03C.cbl",
          "startLine": 181,
          "endLine": 188
        },
        {
          "file": "app/cbl/CBTRN03C.cbl",
          "startLine": 306,
          "endLine": 316
        },
        {
          "file": "app/cpy/CVTRA07Y.cpy",
          "startLine": 56,
          "endLine": 60
        }
      ],
      "fields": [
        "WS-CURR-CARD-NUM",
        "WS-ACCOUNT-TOTAL",
        "REPT-ACCOUNT-TOTAL",
        "WS-FIRST-TIME"
      ],
      "given": "card ...1001 with 125.00 and -200.00 followed by card ...1002",
      "when": "the first ...1002 record is processed",
      "then": "report line 'Account Total....................................................................................-         75.00' followed by 133 dashes",
      "fixtures": [
        "fixtures/posting/p01-baseline-mixed"
      ],
      "finding": null
    },
    {
      "id": "BR-POST-041",
      "title": "Page break every 20 lines; line counter never resets",
      "rule": "Before each detail line, if MOD(WS-LINE-COUNTER, 20) = 0 the program writes the page total ('Page Total ' + 86 dots + total edited +ZZZ,ZZZ,ZZZ.ZZ) and a dash line, then the 4-line header block. WS-LINE-COUNTER is never reset and account-total breaks add 2 lines, so the counter can step over a multiple of 20 and the page then runs until the next multiple (e.g. 40).",
      "category": "control-flow",
      "sources": [
        {
          "file": "app/cbl/CBTRN03C.cbl",
          "startLine": 128,
          "endLine": 132
        },
        {
          "file": "app/cbl/CBTRN03C.cbl",
          "startLine": 282,
          "endLine": 285
        },
        {
          "file": "app/cbl/CBTRN03C.cbl",
          "startLine": 293,
          "endLine": 304
        },
        {
          "file": "app/cbl/CBTRN03C.cbl",
          "startLine": 306,
          "endLine": 316
        },
        {
          "file": "app/cpy/CVTRA07Y.cpy",
          "startLine": 50,
          "endLine": 54
        }
      ],
      "fields": [
        "WS-LINE-COUNTER",
        "WS-PAGE-SIZE",
        "WS-PAGE-TOTAL"
      ],
      "given": "the line counter is 19 after a detail and the next record starts a new card",
      "when": "the account total (+2 lines) is written",
      "then": "the counter reaches 21, MOD(21,20) <> 0, so no page break is taken at line 20; the next break happens when the counter is exactly 40",
      "fixtures": [],
      "finding": "Page size is not enforced consistently (counter not reset, account-total lines can skip the break); reproduce the counter arithmetic exactly."
    },
    {
      "id": "BR-POST-042",
      "title": "Page total rolls into the grand total",
      "rule": "Every detail adds TRAN-AMT to WS-PAGE-TOTAL and WS-ACCOUNT-TOTAL. Writing the page total adds WS-PAGE-TOTAL to WS-GRAND-TOTAL and resets the page total to 0; the grand total is only ever the sum of written page totals.",
      "category": "calculation",
      "sources": [
        {
          "file": "app/cbl/CBTRN03C.cbl",
          "startLine": 134,
          "endLine": 136
        },
        {
          "file": "app/cbl/CBTRN03C.cbl",
          "startLine": 287,
          "endLine": 288
        },
        {
          "file": "app/cbl/CBTRN03C.cbl",
          "startLine": 293,
          "endLine": 298
        }
      ],
      "fields": [
        "WS-PAGE-TOTAL",
        "WS-ACCOUNT-TOTAL",
        "WS-GRAND-TOTAL"
      ],
      "given": "p06: -300.00, -25.50 and 60.00 on one card, no page break",
      "when": "EOF totals are written",
      "then": "the page total -205.50 (with the BR-POST-043 double count) is added to the zero grand total and 'Grand Total' shows -205.50",
      "fixtures": [
        "fixtures/posting/p06-credits-new-category"
      ],
      "finding": null
    },
    {
      "id": "BR-POST-043",
      "title": "EOF totals double-count the last amount and skip the last Account Total",
      "rule": "At end of file the READ INTO leaves the last record in TRAN-RECORD, so it passes the date test and the ELSE branch adds TRAN-AMT to the page and account totals a second time, then writes the page total (plus dash line) and the grand total. 1120-WRITE-ACCOUNT-TOTALS is not called, so the last card's Account Total never appears.",
      "category": "calculation",
      "sources": [
        {
          "file": "app/cbl/CBTRN03C.cbl",
          "startLine": 197,
          "endLine": 204
        },
        {
          "file": "app/cbl/CBTRN03C.cbl",
          "startLine": 248,
          "endLine": 264
        }
      ],
      "fields": [
        "TRAN-AMT",
        "WS-PAGE-TOTAL",
        "WS-ACCOUNT-TOTAL",
        "WS-GRAND-TOTAL"
      ],
      "given": "p04: a single posted transaction of 50.00",
      "when": "TRANREPT runs",
      "then": "'Page Total' and 'Grand Total' show +100.00 instead of 50.00 and no Account Total line is written (8 report records); in p01 the totals are -26.25 instead of -10.50",
      "fixtures": [
        "fixtures/posting/p04-overlimit-reject",
        "fixtures/posting/p01-baseline-mixed",
        "fixtures/posting/p02-invalid-card"
      ],
      "finding": "Last amount counted twice and last account total missing; the golden reports encode this, so reproduce it."
    },
    {
      "id": "BR-POST-044",
      "title": "EOF SYSOUT shows TRAN-AMT and the pre-adjustment page total",
      "rule": "In the EOF branch CBTRN03C displays 'TRAN-AMT ' + TRAN-AMT and 'WS-PAGE-TOTAL' (no separator) + WS-PAGE-TOTAL, both as 11 digits followed by a trailing sign character, before the double-count addition.",
      "category": "sysout",
      "sources": [
        {
          "file": "app/cbl/CBTRN03C.cbl",
          "startLine": 198,
          "endLine": 199
        }
      ],
      "fields": [
        "TRAN-AMT",
        "WS-PAGE-TOTAL"
      ],
      "given": "p01, last record -15.75, running page total -10.50",
      "when": "EOF is reached",
      "then": "SYSOUT lines 'TRAN-AMT 00000001575-' and 'WS-PAGE-TOTAL00000001050-'",
      "fixtures": [
        "fixtures/posting/p01-baseline-mixed",
        "fixtures/posting/p06-credits-new-category"
      ],
      "finding": null
    },
    {
      "id": "BR-POST-045",
      "title": "Grand Total line",
      "rule": "1110-WRITE-GRAND-TOTALS writes 'Grand Total' + 86 dots + WS-GRAND-TOTAL edited +ZZZ,ZZZ,ZZZ.ZZ as the last report line; it does not add a dash line or update counters.",
      "category": "data-mapping",
      "sources": [
        {
          "file": "app/cbl/CBTRN03C.cbl",
          "startLine": 318,
          "endLine": 322
        },
        {
          "file": "app/cpy/CVTRA07Y.cpy",
          "startLine": 62,
          "endLine": 66
        }
      ],
      "fields": [
        "WS-GRAND-TOTAL",
        "REPT-GRAND-TOTAL"
      ],
      "given": "p01 page total -26.25 at EOF",
      "when": "grand totals are written",
      "then": "last report line 'Grand Total......................................................................................-         26.25'",
      "fixtures": [
        "fixtures/posting/p01-baseline-mixed"
      ],
      "finding": null
    },
    {
      "id": "BR-POST-046",
      "title": "CBTRN03C lookup failures abend",
      "rule": "If the card is not in CARDXREF, the type not in TRANTYPE, or type+category not in TRANCATG, CBTRN03C displays 'INVALID CARD NUMBER : ' / 'INVALID TRANSACTION TYPE : ' / 'INVALID TRAN CATG KEY : ' with the key, forces IO-STATUS 23 ('FILE STATUS IS: NNNN0023') and abends (BR-POST-028); partial report output already written remains.",
      "category": "return-code",
      "sources": [
        {
          "file": "app/cbl/CBTRN03C.cbl",
          "startLine": 484,
          "endLine": 512
        }
      ],
      "fields": [
        "FD-XREF-CARD-NUM",
        "FD-TRAN-TYPE",
        "FD-TRAN-CAT-KEY",
        "IO-STATUS"
      ],
      "given": "a posted transaction with type 01 / category 0009 that has no TRANCATG record",
      "when": "STEP10R reports it",
      "then": "SYSOUT 'INVALID TRAN CATG KEY : 010009' / 'FILE STATUS IS: NNNN0023' / 'ABENDING PROGRAM'; step ends abnormally (RC 12 off-host)",
      "fixtures": [],
      "finding": null
    },
    {
      "id": "BR-POST-047",
      "title": "CBTRN03C I/O error messages",
      "rule": "Open, read, write and close failures display a file-specific message, the file-status line and abend: ERROR OPENING TRANFILE / REPTFILE / CROSS REF FILE / TRANSACTION TYPE FILE / TRANSACTION CATG FILE / DATE PARM FILE, ERROR READING TRANSACTION FILE, ERROR READING DATEPARM FILE, ERROR WRITING REPTFILE, ERROR CLOSING POSTED TRANSACTION FILE / REPORT FILE / CROSS REF FILE / TRANSACTION TYPE FILE / TRANSACTION CATG FILE / DATE PARM FILE.",
      "category": "sysout",
      "sources": [
        {
          "file": "app/cbl/CBTRN03C.cbl",
          "startLine": 248,
          "endLine": 272
        },
        {
          "file": "app/cbl/CBTRN03C.cbl",
          "startLine": 343,
          "endLine": 359
        },
        {
          "file": "app/cbl/CBTRN03C.cbl",
          "startLine": 376,
          "endLine": 482
        },
        {
          "file": "app/cbl/CBTRN03C.cbl",
          "startLine": 514,
          "endLine": 621
        }
      ],
      "fields": [
        "TRANFILE-STATUS",
        "TRANREPT-STATUS",
        "CARDXREF-STATUS",
        "TRANTYPE-STATUS",
        "TRANCATG-STATUS",
        "DATEPARM-STATUS"
      ],
      "given": "the DATEPARM dataset is not allocated (open status '35')",
      "when": "STEP10R starts",
      "then": "SYSOUT: START OF EXECUTION OF PROGRAM CBTRN03C / ERROR OPENING DATE PARM FILE / FILE STATUS IS: NNNN0035 / ABENDING PROGRAM",
      "fixtures": [],
      "finding": null
    }
  ]
}
```
