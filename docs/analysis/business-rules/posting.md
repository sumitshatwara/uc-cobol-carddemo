# Business rules: Posting (POSTTRAN / TRANREPT)

Slice `posting` of the CardDemo estate: `POSTTRAN` (STEP15 `CBTRN02C`) posts the daily transaction file; `TRANREPT` (REPRO, SORT, `CBTRN03C`) prints the daily transaction report. Rules were extracted by reading `app/cbl/CBTRN02C.cbl`, `app/cbl/CBTRN03C.cbl`, `app/jcl/POSTTRAN.jcl`, `app/jcl/TRANREPT.jcl`, the copybooks they COPY (CVTRA06Y, CVTRA05Y, CVACT01Y, CVACT03Y, CVTRA01Y, CVTRA03Y, CVTRA04Y, CVTRA07Y) and `tools/parity/layouts/REJTRAN.cpy`, and checked against the golden files in `fixtures/posting/p01..p07`. CVTRA02Y (disclosure group) is loaded by the chain setup but not used by either program.

Line numbers are physical lines in the source files. JCL line numbers are within each JCL member. Amounts in the posting files are zoned decimal with an overpunched sign (no COMP-3 fields in this slice). Legacy quirks are recorded as rules and listed under Findings; a migration must reproduce them.

Machine-readable mirror: [`posting.json`](posting.json).

| Category | Rules |
|---|---|
| validation | 8 |
| calculation | 4 |
| rounding | 1 |
| state-change | 4 |
| return-code | 3 |
| sysout | 4 |
| edge-case | 9 |
| control-flow | 5 |
| data-access | 6 |
| **total** | **44** |

## Rules

### BR-POST-001 - Posting job runs CBTRN02C against six datasets

POSTTRAN has a single step (STEP15) that runs CBTRN02C, reading the daily transaction file and card cross-reference, updating the account master and transaction-category balances, writing posted transactions to the transaction master, and writing rejects to a new 430-byte generation of the daily rejects GDG.

- **Category:** control-flow
- **Source:** `app/jcl/POSTTRAN.jcl:23-42`, `app/cbl/CBTRN02C.cbl:29-61`
- **Fields:** `DALYTRAN-RECORD` (CVTRA06Y); `TRAN-RECORD` (CVTRA05Y); `CARD-XREF-RECORD` (CVACT03Y); `ACCOUNT-RECORD` (CVACT01Y); `TRAN-CAT-BAL-RECORD` (CVTRA01Y); `REJECT-RECORD` (REJTRAN.cpy)
- **Test:** *Given* a daily file with 4 valid transactions; *when* POSTTRAN runs; *then* TRANSACT holds 4 records, ACCTDATA and TCATBALF are updated, and DALYREJS(+1) is catalogued as an empty 430-byte dataset.
- **Fixtures:** `fixtures/posting/p01-baseline-mixed`

### BR-POST-002 - Every daily record is read sequentially until end of file

CBTRN02C reads DALYTRAN one record at a time; file status 00 means a record, 10 means end of file and stops the loop, and any other status displays 'ERROR READING DALYTRAN FILE' and abends. Each record read is counted as processed.

- **Category:** control-flow
- **Source:** `app/cbl/CBTRN02C.cbl:202-219`, `app/cbl/CBTRN02C.cbl:345-369`
- **Fields:** `DALYTRAN-RECORD` (CVTRA06Y); `WS-TRANSACTION-COUNT` (CBTRN02C)
- **Test:** *Given* a DALYTRAN file with 2 records; *when* CBTRN02C runs; *then* WS-TRANSACTION-COUNT = 2 and the program ends normally after status 10.
- **Fixtures:** `fixtures/posting/p02-invalid-card`, `fixtures/posting/p03-account-missing`, `fixtures/posting/p04-overlimit-reject`, `fixtures/posting/p05-expired-account`

### BR-POST-003 - Validation order: card, then account, then limit and expiry

Each transaction is validated in a fixed order: the card must exist in the cross-reference; only if it does is the account read and the credit-limit and expiry checks applied. A failed card lookup skips all account checks.

- **Category:** validation
- **Source:** `app/cbl/CBTRN02C.cbl:370-378`
- **Fields:** `WS-VALIDATION-FAIL-REASON` (CBTRN02C); `DALYTRAN-CARD-NUM` (CVTRA06Y); `XREF-ACCT-ID` (CVACT03Y)
- **Test:** *Given* card 4000999999999999 is not in CARDXREF; *when* the transaction is validated; *then* the reason is 100 and the account file is not read for this record.
- **Fixtures:** `fixtures/posting/p02-invalid-card`

### BR-POST-004 - Reject 100: card number not in cross-reference

If DALYTRAN-CARD-NUM has no CARDXREF record, the transaction is rejected with reason 0100 and description 'INVALID CARD NUMBER FOUND'.

- **Category:** validation
- **Source:** `app/cbl/CBTRN02C.cbl:380-392`
- **Fields:** `DALYTRAN-CARD-NUM` (CVTRA06Y); `XREF-CARD-NUM` (CVACT03Y); `REJ-FAIL-REASON` (REJTRAN.cpy); `REJ-FAIL-REASON-DESC` (REJTRAN.cpy)
- **Test:** *Given* transaction 0000000000000002 with card 4000999999999999 and no matching CARDXREF record; *when* CBTRN02C validates it; *then* a DALYREJS record is written with trailer '0100INVALID CARD NUMBER FOUND' and nothing is posted for it.
- **Fixtures:** `fixtures/posting/p02-invalid-card`

### BR-POST-005 - Reject 101: account in cross-reference but not on account master

If the card's cross-referenced account id has no ACCTDATA record, the transaction is rejected with reason 0101 and description 'ACCOUNT RECORD NOT FOUND'; the limit and expiry checks are not applied.

- **Category:** validation
- **Source:** `app/cbl/CBTRN02C.cbl:393-399`
- **Fields:** `XREF-ACCT-ID` (CVACT03Y); `ACCT-ID` (CVACT01Y); `REJ-FAIL-REASON` (REJTRAN.cpy)
- **Test:** *Given* card 4000000000001099 maps to account 00000001099, which is absent from ACCTDATA; *when* CBTRN02C validates it; *then* the reject trailer is '0101ACCOUNT RECORD NOT FOUND' and RC is 4.
- **Fixtures:** `fixtures/posting/p03-account-missing`

### BR-POST-006 - Credit-limit check uses cycle amounts, not the current balance

A transaction is over limit when ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT exceeds ACCT-CREDIT-LIMIT. The account's current balance is not used. Landing exactly on the limit is accepted.

- **Category:** validation
- **Source:** `app/cbl/CBTRN02C.cbl:403-408`
- **Fields:** `ACCT-CURR-CYC-CREDIT` (CVACT01Y); `ACCT-CURR-CYC-DEBIT` (CVACT01Y); `ACCT-CREDIT-LIMIT` (CVACT01Y); `DALYTRAN-AMT` (CVTRA06Y); `WS-TEMP-BAL` (CBTRN02C)
- **Test:** *Given* account 1003 with limit 1000.00, cycle credit 950.00, cycle debit 0.00; *when* a 50.00 purchase is validated; *then* WS-TEMP-BAL = 1000.00, which is <= the limit, so the purchase posts.
- **Fixtures:** `fixtures/posting/p04-overlimit-reject`

### BR-POST-007 - Reject 102: over-limit transaction

When the cycle-based balance would exceed the credit limit, the transaction is rejected with reason 0102 and description 'OVERLIMIT TRANSACTION'.

- **Category:** validation
- **Source:** `app/cbl/CBTRN02C.cbl:407-413`
- **Fields:** `ACCT-CREDIT-LIMIT` (CVACT01Y); `WS-TEMP-BAL` (CBTRN02C); `REJ-FAIL-REASON` (REJTRAN.cpy)
- **Test:** *Given* account 1003 with limit 1000.00 whose cycle credit is already 1000.00; *when* a 100.00 purchase is validated; *then* WS-TEMP-BAL = 1100.00 > 1000.00, the trailer is '0102OVERLIMIT TRANSACTION' and the account is unchanged by it.
- **Fixtures:** `fixtures/posting/p04-overlimit-reject`

### BR-POST-008 - Limit check sees earlier postings in the same run

The account is re-read from the master for every transaction, and accepted postings rewrite it immediately, so the limit check for a later transaction includes amounts posted earlier in the same file.

- **Category:** state-change
- **Source:** `app/cbl/CBTRN02C.cbl:393-413`, `app/cbl/CBTRN02C.cbl:545-560`
- **Fields:** `ACCT-CURR-CYC-CREDIT` (CVACT01Y); `ACCT-CREDIT-LIMIT` (CVACT01Y)
- **Test:** *Given* account 1003, limit 1000.00, cycle credit 950.00; two purchases of 50.00 then 100.00; *when* both are processed in order; *then* the first posts (cycle credit becomes 1000.00) and the second is rejected with 102 because 1000.00 + 100.00 > 1000.00.
- **Fixtures:** `fixtures/posting/p04-overlimit-reject`

### BR-POST-009 - Reject 103: transaction after account expiry

If the account expiry date is earlier than the first 10 characters (the date part) of the transaction's origin timestamp, the transaction is rejected with reason 0103 and description 'TRANSACTION RECEIVED AFTER ACCT EXPIRATION'. A transaction on the expiry date itself is accepted. The comparison is a plain character comparison of YYYY-MM-DD text.

- **Category:** validation
- **Source:** `app/cbl/CBTRN02C.cbl:414-420`
- **Fields:** `ACCT-EXPIRAION-DATE` (CVACT01Y); `DALYTRAN-ORIG-TS` (CVTRA06Y); `REJ-FAIL-REASON` (REJTRAN.cpy)
- **Test:** *Given* account 1004 expiring 2022-01-31 and account 1005 expiring 2022-07-04; both transactions have origin 2022-07-04-09.15.00.000000; *when* CBTRN02C validates them; *then* the account 1004 transaction is rejected with '0103TRANSACTION RECEIVED AFTER ACCT EXPIRATION'; the account 1005 one posts.
- **Fixtures:** `fixtures/posting/p05-expired-account`

### BR-POST-010 - Expiry reason overrides over-limit reason

The limit and expiry checks are both applied, one after the other, without stopping at the first failure. If a transaction is both over limit and after expiry, the reject carries only reason 0103 and the expiry text.

- **Category:** edge-case
- **Source:** `app/cbl/CBTRN02C.cbl:403-420`
- **Fields:** `WS-VALIDATION-FAIL-REASON` (CBTRN02C); `WS-VALIDATION-FAIL-REASON-DESC` (CBTRN02C)
- **Test:** *Given* an account with limit 100.00, cycle credit 90.00, expiry 2022-01-31; *when* a 50.00 purchase with origin 2022-07-04 is validated; *then* one reject is written with trailer '0103TRANSACTION RECEIVED AFTER ACCT EXPIRATION' (reason 102 is overwritten).
- **Fixtures:** none (no golden case covers it yet)

### BR-POST-011 - Blank or zero expiry date rejects every transaction

Because expiry is compared as text, an expiry date of spaces or '0000-00-00' sorts below any real origin date, so every transaction for that account is rejected with reason 103.

- **Category:** edge-case
- **Source:** `app/cbl/CBTRN02C.cbl:414-420`
- **Fields:** `ACCT-EXPIRAION-DATE` (CVACT01Y); `DALYTRAN-ORIG-TS` (CVTRA06Y)
- **Test:** *Given* an account whose ACCT-EXPIRAION-DATE is '0000-00-00'; *when* a transaction with origin 2022-07-04 is validated; *then* it is rejected with reason 0103.
- **Fixtures:** none (no golden case covers it yet)

### BR-POST-012 - Reject record layout: input record plus 80-byte trailer

A rejected transaction is written to DALYREJS as the unchanged 350-byte daily record followed by an 80-byte trailer: a 4-digit reason code and a 76-character description. A write failure displays 'ERROR WRITING TO REJECTS FILE' and abends.

- **Category:** data-access
- **Source:** `app/cbl/CBTRN02C.cbl:176-182`, `app/cbl/CBTRN02C.cbl:446-465`, `app/jcl/POSTTRAN.jcl:34-38`
- **Fields:** `REJECT-TRAN-DATA` (CBTRN02C); `WS-VALIDATION-FAIL-REASON` (CBTRN02C); `WS-VALIDATION-FAIL-REASON-DESC` (CBTRN02C); `REJ-FAIL-REASON` (REJTRAN.cpy); `REJ-FAIL-REASON-DESC` (REJTRAN.cpy)
- **Test:** *Given* a rejected transaction with reason 102; *when* the reject is written; *then* the record is 430 bytes: bytes 1-350 equal the input record, bytes 351-354 are '0102', bytes 355-430 are 'OVERLIMIT TRANSACTION' padded with spaces.
- **Fixtures:** `fixtures/posting/p02-invalid-card`, `fixtures/posting/p03-account-missing`, `fixtures/posting/p04-overlimit-reject`, `fixtures/posting/p05-expired-account`

### BR-POST-013 - Rejects are counted and the posting continues

A rejected transaction increments the reject count and processing continues with the next record; rejects never stop the run.

- **Category:** control-flow
- **Source:** `app/cbl/CBTRN02C.cbl:211-216`
- **Fields:** `WS-REJECT-COUNT` (CBTRN02C)
- **Test:** *Given* a file with one invalid-card record and one valid record; *when* CBTRN02C runs; *then* one record is rejected, the other is posted, and both are counted as processed.
- **Fixtures:** `fixtures/posting/p02-invalid-card`, `fixtures/posting/p03-account-missing`, `fixtures/posting/p04-overlimit-reject`, `fixtures/posting/p05-expired-account`

### BR-POST-014 - Return code 4 when any transaction is rejected

CBTRN02C ends with return code 4 if at least one transaction was rejected, otherwise 0.

- **Category:** return-code
- **Source:** `app/cbl/CBTRN02C.cbl:229-231`
- **Fields:** `WS-REJECT-COUNT` (CBTRN02C); `RETURN-CODE`
- **Test:** *Given* a run with 2 transactions of which 1 is rejected; *when* CBTRN02C finishes; *then* STEP15 ends with RC 4 (RC 0 when no rejects).
- **Fixtures:** `fixtures/posting/p01-baseline-mixed`, `fixtures/posting/p02-invalid-card`, `fixtures/posting/p03-account-missing`, `fixtures/posting/p04-overlimit-reject`, `fixtures/posting/p05-expired-account`, `fixtures/posting/p06-credits-new-category`, `fixtures/posting/p07-empty-day`

### BR-POST-015 - Posting SYSOUT messages and counters

CBTRN02C displays 'START OF EXECUTION OF PROGRAM CBTRN02C', then at the end 'TRANSACTIONS PROCESSED :' and 'TRANSACTIONS REJECTED  :' each followed by a 9-digit zero-padded count, then 'END OF EXECUTION OF PROGRAM CBTRN02C'.

- **Category:** sysout
- **Source:** `app/cbl/CBTRN02C.cbl:194`, `app/cbl/CBTRN02C.cbl:227-232`, `app/cbl/CBTRN02C.cbl:184-186`
- **Fields:** `WS-TRANSACTION-COUNT` (CBTRN02C); `WS-REJECT-COUNT` (CBTRN02C)
- **Test:** *Given* 2 transactions, 1 rejected; *when* CBTRN02C finishes; *then* SYSOUT contains 'TRANSACTIONS PROCESSED :000000002' and 'TRANSACTIONS REJECTED  :000000001'.
- **Fixtures:** `fixtures/posting/p01-baseline-mixed`, `fixtures/posting/p02-invalid-card`, `fixtures/posting/p03-account-missing`, `fixtures/posting/p04-overlimit-reject`, `fixtures/posting/p05-expired-account`, `fixtures/posting/p06-credits-new-category`, `fixtures/posting/p07-empty-day`

### BR-POST-016 - Posted transaction copies the daily record field by field

An accepted daily transaction is written to the transaction master with id, type, category, source, description, amount, merchant id/name/city/zip, card number and origin timestamp copied unchanged from the daily record.

- **Category:** state-change
- **Source:** `app/cbl/CBTRN02C.cbl:424-444`, `app/cbl/CBTRN02C.cbl:562-579`
- **Fields:** `DALYTRAN-*` (CVTRA06Y); `TRAN-ID, TRAN-TYPE-CD, TRAN-CAT-CD, TRAN-SOURCE, TRAN-DESC, TRAN-AMT, TRAN-MERCHANT-ID, TRAN-MERCHANT-NAME, TRAN-MERCHANT-CITY, TRAN-MERCHANT-ZIP, TRAN-CARD-NUM, TRAN-ORIG-TS` (CVTRA05Y)
- **Test:** *Given* daily transaction 0000000000000001, type 01, category 0001, amount 125.00; *when* it posts; *then* TRANSACT has a record keyed 0000000000000001 with the same field values.
- **Fixtures:** `fixtures/posting/p01-baseline-mixed`

### BR-POST-017 - Processing timestamp is the run clock, not the input value

TRAN-PROC-TS is set from the current date and time in DB2 timestamp form YYYY-MM-DD-HH.MM.SS.hh0000 (hundredths of a second followed by '0000'); the daily record's own DALYTRAN-PROC-TS is ignored.

- **Category:** calculation
- **Source:** `app/cbl/CBTRN02C.cbl:437-438`, `app/cbl/CBTRN02C.cbl:692-705`, `app/cbl/CBTRN02C.cbl:150-174`
- **Fields:** `TRAN-PROC-TS` (CVTRA05Y); `DALYTRAN-PROC-TS` (CVTRA06Y); `DB2-FORMAT-TS` (CBTRN02C)
- **Test:** *Given* the clock frozen at 2022-07-05 10:00:00.00; *when* a transaction posts; *then* TRAN-PROC-TS = '2022-07-05-10.00.00.000000'.
- **Fixtures:** `fixtures/posting/p01-baseline-mixed`, `fixtures/posting/p06-credits-new-category`

### BR-POST-018 - Transaction-category balance key

Each posting updates the transaction-category balance identified by the cross-referenced account id, the transaction type code and the category code.

- **Category:** data-access
- **Source:** `app/cbl/CBTRN02C.cbl:467-479`
- **Fields:** `TRANCAT-ACCT-ID, TRANCAT-TYPE-CD, TRANCAT-CD` (CVTRA01Y); `XREF-ACCT-ID` (CVACT03Y); `DALYTRAN-TYPE-CD, DALYTRAN-CAT-CD` (CVTRA06Y)
- **Test:** *Given* account 00000001006, type 02, category 0001; *when* a payment posts; *then* the TCATBALF key used is '00000001006020001'.
- **Fixtures:** `fixtures/posting/p06-credits-new-category`

### BR-POST-019 - Create a category balance record when none exists

If no balance record exists for the key, CBTRN02C displays 'TCATBAL record not found for key : <key>.. Creating.' and writes a new record whose balance equals the transaction amount.

- **Category:** state-change
- **Source:** `app/cbl/CBTRN02C.cbl:474-479`, `app/cbl/CBTRN02C.cbl:495-524`
- **Fields:** `TRAN-CAT-BAL` (CVTRA01Y); `TRAN-CAT-KEY` (CVTRA01Y)
- **Test:** *Given* no TCATBALF record for account 1006, type 03, category 0001; *when* a -25.50 credit posts; *then* SYSOUT shows 'TCATBAL record not found for key : 00000001006030001.. Creating.' and a new record with TRAN-CAT-BAL = -25.50 is written.
- **Fixtures:** `fixtures/posting/p06-credits-new-category`

### BR-POST-020 - Add to an existing category balance

If the balance record exists, the transaction amount (positive or negative) is added to TRAN-CAT-BAL and the record is rewritten.

- **Category:** state-change
- **Source:** `app/cbl/CBTRN02C.cbl:495-499`, `app/cbl/CBTRN02C.cbl:526-542`
- **Fields:** `TRAN-CAT-BAL` (CVTRA01Y); `DALYTRAN-AMT` (CVTRA06Y)
- **Test:** *Given* TCATBALF record 00000001003010001 with balance 950.00; *when* a 50.00 purchase posts; *then* the balance becomes 1000.00.
- **Fixtures:** `fixtures/posting/p04-overlimit-reject`

### BR-POST-021 - Category balance read error abends

Any TCATBALF read status other than 00 (found) or 23 (not found) displays 'ERROR READING TRANSACTION BALANCE FILE' and abends. Write and rewrite failures display 'ERROR WRITING TRANSACTION BALANCE FILE' or 'ERROR REWRITING TRANSACTION BALANCE FILE' and abend.

- **Category:** data-access
- **Source:** `app/cbl/CBTRN02C.cbl:481-493`, `app/cbl/CBTRN02C.cbl:512-524`, `app/cbl/CBTRN02C.cbl:530-542`
- **Fields:** `TCATBALF-STATUS` (CBTRN02C)
- **Test:** *Given* TCATBALF returns status 92 on read; *when* a transaction posts; *then* SYSOUT shows 'ERROR READING TRANSACTION BALANCE FILE', the file status line and 'ABENDING PROGRAM'.
- **Fixtures:** none (no golden case covers it yet)

### BR-POST-022 - Account balance and cycle totals updated by sign of amount

Every posted amount is added to the account's current balance. A zero or positive amount is also added to the cycle credit; a negative amount is added (as a negative number) to the cycle debit, so cycle debit grows more negative.

- **Category:** calculation
- **Source:** `app/cbl/CBTRN02C.cbl:545-552`
- **Fields:** `ACCT-CURR-BAL` (CVACT01Y); `ACCT-CURR-CYC-CREDIT` (CVACT01Y); `ACCT-CURR-CYC-DEBIT` (CVACT01Y); `DALYTRAN-AMT` (CVTRA06Y)
- **Test:** *Given* account 1006 with balance 800.00, cycle credit 800.00, cycle debit 0.00; *when* transactions of -300.00, -25.50 and +60.00 post; *then* balance = 534.50, cycle credit = 860.00, cycle debit = -325.50.
- **Fixtures:** `fixtures/posting/p06-credits-new-category`

### BR-POST-023 - Payments raise the limit-check amount

Because cycle debit holds negative amounts and the limit check subtracts it, every payment or credit posted in the cycle increases the amount tested against the limit instead of lowering it.

- **Category:** edge-case
- **Source:** `app/cbl/CBTRN02C.cbl:403-405`, `app/cbl/CBTRN02C.cbl:548-552`
- **Fields:** `ACCT-CURR-CYC-DEBIT` (CVACT01Y); `WS-TEMP-BAL` (CBTRN02C)
- **Test:** *Given* an account with limit 1000.00, cycle credit 600.00 and cycle debit -500.00 (a 500.00 payment); *when* a 50.00 purchase is validated; *then* WS-TEMP-BAL = 600.00 - (-500.00) + 50.00 = 1150.00 > 1000.00 and the purchase is rejected with 102.
- **Fixtures:** none (no golden case covers it yet)

### BR-POST-024 - Account rewrite failure sets reason 109 but is not a reject

If the account rewrite fails with an invalid key, reason 109 'ACCOUNT RECORD NOT FOUND' is stored, but the record is neither counted as rejected nor written to DALYREJS, and the transaction is still written to TRANSACT.

- **Category:** edge-case
- **Source:** `app/cbl/CBTRN02C.cbl:554-559`, `app/cbl/CBTRN02C.cbl:440-442`
- **Fields:** `WS-VALIDATION-FAIL-REASON` (CBTRN02C); `WS-REJECT-COUNT` (CBTRN02C)
- **Test:** *Given* an account deleted between its read and rewrite; *when* the transaction posts; *then* the transaction is in TRANSACT, the reject count is unchanged and RC is not raised by it.
- **Fixtures:** none (no golden case covers it yet)

### BR-POST-025 - Posting order: category balance, account, transaction

For an accepted transaction the category balance is updated first, then the account, then the transaction record is written. A failure writing the transaction ('ERROR WRITING TO TRANSACTION FILE') abends after the balances were already changed.

- **Category:** control-flow
- **Source:** `app/cbl/CBTRN02C.cbl:440-442`, `app/cbl/CBTRN02C.cbl:562-579`
- **Fields:** `TRAN-CAT-BAL` (CVTRA01Y); `ACCOUNT-RECORD` (CVACT01Y); `TRAN-RECORD` (CVTRA05Y)
- **Test:** *Given* a transaction whose id already exists in TRANSACT; *when* it posts; *then* TCATBALF and ACCTDATA are updated, then the duplicate-key write fails, SYSOUT shows 'ERROR WRITING TO TRANSACTION FILE' and the program abends.
- **Fixtures:** none (no golden case covers it yet)

### BR-POST-026 - Transaction master is opened for output

CBTRN02C opens the transaction master KSDS for OUTPUT, so the posted transactions of this run are the only content written by the program (the file is loaded from DALYTRAN.PS.INIT by the TRANFILE setup job, empty in every fixture).

- **Category:** data-access
- **Source:** `app/cbl/CBTRN02C.cbl:254-270`
- **Fields:** `TRAN-RECORD` (CVTRA05Y)
- **Test:** *Given* an empty transaction master and 4 accepted transactions; *when* CBTRN02C runs; *then* TRANSACT contains exactly those 4 records in transaction-id order.
- **Fixtures:** `fixtures/posting/p01-baseline-mixed`

### BR-POST-027 - Empty daily file posts nothing and ends RC 0

If the daily file has no records, nothing is written to TRANSACT or DALYREJS, both counters are zero and the return code is 0.

- **Category:** edge-case
- **Source:** `app/cbl/CBTRN02C.cbl:202-231`
- **Fields:** `WS-TRANSACTION-COUNT` (CBTRN02C); `WS-REJECT-COUNT` (CBTRN02C)
- **Test:** *Given* an empty DALYTRAN; *when* POSTTRAN runs; *then* SYSOUT shows 'TRANSACTIONS PROCESSED :000000000' and 'TRANSACTIONS REJECTED  :000000000'; RC 0.
- **Fixtures:** `fixtures/posting/p07-empty-day`

### BR-POST-028 - File open, close and I/O errors abend with a status line

Any unexpected file status on open, read, write or close displays a specific message (for example 'ERROR OPENING DALYTRAN'), then 'FILE STATUS IS: NNNN' followed by the 4-digit status, then 'ABENDING PROGRAM', and calls CEE3ABD with abend code 999. Off-host the CEE3ABD stub returns RC 12.

- **Category:** return-code
- **Source:** `app/cbl/CBTRN02C.cbl:236-343`, `app/cbl/CBTRN02C.cbl:582-690`, `app/cbl/CBTRN02C.cbl:707-727`, `app/cbl/CBTRN03C.cbl:376-482`, `app/cbl/CBTRN03C.cbl:514-649`
- **Fields:** `IO-STATUS` (CBTRN02C / CBTRN03C); `IO-STATUS-04` (CBTRN02C / CBTRN03C); `ABCODE` (CBTRN02C / CBTRN03C)
- **Test:** *Given* the account master cannot be opened (status 35); *when* CBTRN02C starts; *then* SYSOUT shows 'ERROR OPENING ACCOUNT MASTER FILE', 'FILE STATUS IS: NNNN0035', 'ABENDING PROGRAM' and the step ends with the abend.
- **Fixtures:** none (no golden case covers it yet)

### BR-POST-029 - No rounding; amounts carry exactly two decimals

All posting arithmetic is ADD or COMPUTE on fields with two decimal places, without ROUNDED and without ON SIZE ERROR. Results are exact; a value that exceeds the receiving field's integer digits loses its high-order digits silently.

- **Category:** rounding
- **Source:** `app/cbl/CBTRN02C.cbl:403-405`, `app/cbl/CBTRN02C.cbl:508`, `app/cbl/CBTRN02C.cbl:527`, `app/cbl/CBTRN02C.cbl:547-552`
- **Fields:** `DALYTRAN-AMT S9(09)V99` (CVTRA06Y); `TRAN-CAT-BAL S9(09)V99` (CVTRA01Y); `ACCT-CURR-BAL S9(10)V99` (CVACT01Y); `WS-TEMP-BAL S9(09)V99` (CBTRN02C)
- **Test:** *Given* a TCATBALF balance of 999,999,999.00; *when* a 2.00 purchase posts; *then* TRAN-CAT-BAL becomes 1.00 (high-order digit truncated) and no error is raised.
- **Fixtures:** none (no golden case covers it yet)

### BR-POST-030 - Report step 1: unload the transaction master

TRANREPT first copies the transaction master KSDS (in transaction-id order) to a new 350-byte sequential backup generation with IDCAMS REPRO.

- **Category:** data-access
- **Source:** `app/jcl/TRANREPT.jcl:23-33`
- **Fields:** `TRAN-RECORD` (CVTRA05Y)
- **Test:** *Given* a TRANSACT KSDS with 4 records; *when* STEP05R (REPROC) runs; *then* TRANSACT.BKUP(+1) holds the same 4 records.
- **Fixtures:** `fixtures/posting/p01-baseline-mixed`

### BR-POST-031 - Report step 2: sort by card number and filter by processing date

A SORT step keeps only transactions whose processing date (bytes 305-314) is between 2022-01-01 and 2022-07-06 inclusive, and sorts them ascending by card number (bytes 263-278). These dates are hard-coded in the JCL SYMNAMES, not read from DATEPARM. Records with equal card numbers keep their input (transaction-id) order.

- **Category:** validation
- **Source:** `app/jcl/TRANREPT.jcl:37-55`
- **Fields:** `TRAN-CARD-NUM` (CVTRA05Y); `TRAN-PROC-TS` (CVTRA05Y)
- **Test:** *Given* transactions 1 and 3 on card ...1001 and 2 and 4 on card ...1002, all processed 2022-07-05; *when* the SORT step runs; *then* TRANSACT.DALY(+1) is in the order 1, 3, 2, 4.
- **Fixtures:** `fixtures/posting/p01-baseline-mixed`

### BR-POST-032 - Report date range comes from the DATEPARM record

CBTRN03C reads one DATEPARM record: start date in columns 1-10, end date in columns 12-21, and displays 'Reporting from <start> to <end>'. An empty DATEPARM sets end of file, so no transaction is reported.

- **Category:** data-access
- **Source:** `app/cbl/CBTRN03C.cbl:122-125`, `app/cbl/CBTRN03C.cbl:220-243`, `app/jcl/TRANREPT.jcl:73-74`
- **Fields:** `WS-START-DATE` (CBTRN03C); `WS-END-DATE` (CBTRN03C)
- **Test:** *Given* DATEPARM contains '2022-01-01 2022-07-06'; *when* CBTRN03C starts; *then* SYSOUT shows 'Reporting from 2022-01-01 to 2022-07-06'.
- **Fixtures:** `fixtures/posting/p01-baseline-mixed`, `fixtures/posting/p02-invalid-card`, `fixtures/posting/p03-account-missing`, `fixtures/posting/p04-overlimit-reject`, `fixtures/posting/p05-expired-account`, `fixtures/posting/p06-credits-new-category`, `fixtures/posting/p07-empty-day`

### BR-POST-033 - Out-of-range processing date ends the report loop

CBTRN03C checks each record's processing date against the DATEPARM range. A record outside the range executes NEXT SENTENCE, which leaves the whole read loop: no further records are reported and no page or grand total is written.

- **Category:** edge-case
- **Source:** `app/cbl/CBTRN03C.cbl:170-178`, `app/cbl/CBTRN03C.cbl:206`
- **Fields:** `TRAN-PROC-TS` (CVTRA05Y); `WS-START-DATE` (CBTRN03C); `WS-END-DATE` (CBTRN03C)
- **Test:** *Given* an empty sorted transaction file (the record area stays blank, which is below the start date); *when* CBTRN03C runs; *then* the loop exits at once: the report file has 0 lines and no 'TRAN-AMT' or 'WS-PAGE-TOTAL' lines appear in SYSOUT.
- **Fixtures:** `fixtures/posting/p07-empty-day`

### BR-POST-034 - Each reported transaction is echoed to SYSOUT

For every in-range record CBTRN03C displays the raw 350-byte transaction record on SYSOUT before reporting it.

- **Category:** sysout
- **Source:** `app/cbl/CBTRN03C.cbl:179-180`
- **Fields:** `TRAN-RECORD` (CVTRA05Y)
- **Test:** *Given* 4 sorted transactions; *when* CBTRN03C runs; *then* SYSOUT contains the 4 records verbatim, in sorted order.
- **Fixtures:** `fixtures/posting/p01-baseline-mixed`

### BR-POST-035 - Report headers on the first detail and on each new page

Before the first detail line the program fills the date range and writes four header lines: the report name line ('DALYREPT', 'Daily Transaction Report', 'Date Range: <start> to <end>'), a blank line, the column header line and a line of 133 dashes.

- **Category:** sysout
- **Source:** `app/cbl/CBTRN03C.cbl:274-280`, `app/cbl/CBTRN03C.cbl:324-341`, `app/cpy/CVTRA07Y.cpy:4-13`, `app/cpy/CVTRA07Y.cpy:33-48`
- **Fields:** `REPORT-NAME-HEADER, REPT-START-DATE, REPT-END-DATE, TRANSACTION-HEADER-1, TRANSACTION-HEADER-2` (CVTRA07Y); `WS-LINE-COUNTER` (CBTRN03C)
- **Test:** *Given* DATEPARM 2022-01-01 to 2022-07-06 and one transaction; *when* the report is written; *then* line 1 starts 'DALYREPT' and ends 'Date Range: 2022-01-01 to 2022-07-06', line 2 is blank, line 4 is 133 '-' characters.
- **Fixtures:** `fixtures/posting/p01-baseline-mixed`

### BR-POST-036 - Detail line format

Each detail line holds transaction id (16), account id (11 digits from the cross-reference), type code '-' type description truncated to 15, category code (4 digits) '-' category description truncated to 29, source (10), and the amount edited as -ZZZ,ZZZ,ZZZ.ZZ (leading '-' for negatives, blank for positives), padded to 133 bytes.

- **Category:** sysout
- **Source:** `app/cbl/CBTRN03C.cbl:361-374`, `app/cpy/CVTRA07Y.cpy:15-31`
- **Fields:** `TRAN-REPORT-TRANS-ID, TRAN-REPORT-ACCOUNT-ID, TRAN-REPORT-TYPE-CD, TRAN-REPORT-TYPE-DESC, TRAN-REPORT-CAT-CD, TRAN-REPORT-CAT-DESC, TRAN-REPORT-SOURCE, TRAN-REPORT-AMT` (CVTRA07Y); `TRAN-TYPE-DESC` (CVTRA03Y); `TRAN-CAT-TYPE-DESC` (CVTRA04Y); `XREF-ACCT-ID` (CVACT03Y)
- **Test:** *Given* transaction 0000000000000003, account 1001, type 02 'Payment', category 0001 'Payment - Bank Transfer', source OPERATOR, amount -200.00; *when* its detail line is written; *then* the line reads '0000000000000003 00000001001 02-Payment         0001-Payment - Bank Transfer       OPERATOR      -        200.00'.
- **Fixtures:** `fixtures/posting/p01-baseline-mixed`, `fixtures/posting/p06-credits-new-category`

### BR-POST-037 - Zero amounts print as blanks

The amount pictures use only Z for digit positions, so a 0.00 amount prints as all spaces on detail and total lines (standard COBOL editing).

- **Category:** edge-case
- **Source:** `app/cpy/CVTRA07Y.cpy:30`, `app/cpy/CVTRA07Y.cpy:54`
- **Fields:** `TRAN-REPORT-AMT` (CVTRA07Y); `REPT-PAGE-TOTAL` (CVTRA07Y)
- **Test:** *Given* a posted transaction with amount 0.00; *when* it is reported; *then* the 15-character amount field on its detail line is blank.
- **Fixtures:** none (no golden case covers it yet)

### BR-POST-038 - Lookups for report descriptions abend when missing

On every card-number change the card is looked up in CARDXREF; for every record the type and type+category are looked up in TRANTYPE and TRANCATG. A missing key displays 'INVALID CARD NUMBER : ', 'INVALID TRANSACTION TYPE : ' or 'INVALID TRAN CATG KEY : ' with the key, then 'FILE STATUS IS: NNNN0023' and abends.

- **Category:** validation
- **Source:** `app/cbl/CBTRN03C.cbl:181-195`, `app/cbl/CBTRN03C.cbl:484-512`
- **Fields:** `FD-XREF-CARD-NUM, FD-TRAN-TYPE, FD-TRAN-CAT-KEY` (CBTRN03C); `TRAN-TYPE-CD, TRAN-CAT-CD` (CVTRA05Y)
- **Test:** *Given* a posted transaction with type 09 that is not in TRANTYPE; *when* CBTRN03C reports it; *then* SYSOUT shows 'INVALID TRANSACTION TYPE : 09', 'FILE STATUS IS: NNNN0023', 'ABENDING PROGRAM'.
- **Fixtures:** none (no golden case covers it yet)

### BR-POST-039 - Account totals break on card number

When the card number differs from the previous record (and it is not the first record), an 'Account Total' line with the accumulated amount is written, followed by a dash line, and the account total is reset. The break is on card number, not account id.

- **Category:** calculation
- **Source:** `app/cbl/CBTRN03C.cbl:181-188`, `app/cbl/CBTRN03C.cbl:306-316`, `app/cpy/CVTRA07Y.cpy:56-60`
- **Fields:** `WS-CURR-CARD-NUM` (CBTRN03C); `WS-ACCOUNT-TOTAL` (CBTRN03C); `REPT-ACCOUNT-TOTAL` (CVTRA07Y)
- **Test:** *Given* card ...1001 with 125.00 and -200.00 followed by card ...1002; *when* the first card ...1002 record is reached; *then* 'Account Total' with dots and '-         75.00' is written, then a dash line.
- **Fixtures:** `fixtures/posting/p01-baseline-mixed`

### BR-POST-040 - Page break every 20 lines by exact multiple

Before each detail line, if the line counter is an exact multiple of 20, a page-total line and dash line are written, followed by the four header lines. The counter counts headers, details, account-total and page-total lines.

- **Category:** control-flow
- **Source:** `app/cbl/CBTRN03C.cbl:127-132`, `app/cbl/CBTRN03C.cbl:282-285`, `app/cbl/CBTRN03C.cbl:293-304`
- **Fields:** `WS-LINE-COUNTER` (CBTRN03C); `WS-PAGE-SIZE` (CBTRN03C)
- **Test:** *Given* the line counter at 20 when the 17th detail is about to be written (4 headers + 16 details); *when* the detail is reported; *then* 'Page Total' line, dash line and 4 header lines are written first, then the detail.
- **Fixtures:** none (no golden case covers it yet)

### BR-POST-041 - Page totals roll into the grand total

Writing a page-total line adds the page total to the grand total and resets the page total to zero. The grand total is printed once, at end of file, as 'Grand Total' with dots and the amount edited +ZZZ,ZZZ,ZZZ.ZZ.

- **Category:** calculation
- **Source:** `app/cbl/CBTRN03C.cbl:293-304`, `app/cbl/CBTRN03C.cbl:318-322`, `app/cpy/CVTRA07Y.cpy:50-66`
- **Fields:** `WS-PAGE-TOTAL` (CBTRN03C); `WS-GRAND-TOTAL` (CBTRN03C); `REPT-PAGE-TOTAL, REPT-GRAND-TOTAL` (CVTRA07Y)
- **Test:** *Given* a single page with page total +84.00; *when* end of file is reached; *then* the last three lines are the 'Page Total' line ending '+         84.00', a dash line, and the 'Grand Total' line ending '+         84.00'.
- **Fixtures:** `fixtures/posting/p02-invalid-card`, `fixtures/posting/p03-account-missing`, `fixtures/posting/p04-overlimit-reject`, `fixtures/posting/p05-expired-account`, `fixtures/posting/p06-credits-new-category`

### BR-POST-042 - End of file adds the last amount again

At end of file the record area still holds the last transaction; its amount is displayed ('TRAN-AMT ' and 'WS-PAGE-TOTAL' lines on SYSOUT) and then added a second time to the page and account totals before the final page and grand totals are written. Totals are therefore overstated by the last transaction's amount.

- **Category:** edge-case
- **Source:** `app/cbl/CBTRN03C.cbl:197-203`
- **Fields:** `TRAN-AMT` (CVTRA05Y); `WS-PAGE-TOTAL` (CBTRN03C); `WS-ACCOUNT-TOTAL` (CBTRN03C)
- **Test:** *Given* one reported transaction of 42.00; *when* end of file is reached; *then* SYSOUT shows 'TRAN-AMT 00000004200+' and 'WS-PAGE-TOTAL00000004200+', and the page and grand totals print +84.00.
- **Fixtures:** `fixtures/posting/p01-baseline-mixed`, `fixtures/posting/p02-invalid-card`, `fixtures/posting/p03-account-missing`, `fixtures/posting/p04-overlimit-reject`, `fixtures/posting/p05-expired-account`, `fixtures/posting/p06-credits-new-category`

### BR-POST-043 - Last account's total is never printed

Account totals are written only on a card-number change, and the end-of-file branch writes page and grand totals only, so the final card in the report never gets an 'Account Total' line.

- **Category:** edge-case
- **Source:** `app/cbl/CBTRN03C.cbl:181-184`, `app/cbl/CBTRN03C.cbl:197-203`
- **Fields:** `WS-ACCOUNT-TOTAL` (CBTRN03C)
- **Test:** *Given* two cards in the report; *when* the report completes; *then* exactly one 'Account Total' line appears (for the first card).
- **Fixtures:** `fixtures/posting/p01-baseline-mixed`

### BR-POST-044 - Report SYSOUT start and end messages

CBTRN03C displays 'START OF EXECUTION OF PROGRAM CBTRN03C' at start and 'END OF EXECUTION OF PROGRAM CBTRN03C' after closing its files. It sets no return code, so TRANREPT ends RC 0 even when POSTTRAN had rejects.

- **Category:** return-code
- **Source:** `app/cbl/CBTRN03C.cbl:160`, `app/cbl/CBTRN03C.cbl:208-217`
- **Fields:** `RETURN-CODE`
- **Test:** *Given* a posting run that ended RC 4; *when* TRANREPT runs; *then* STEP10R ends RC 0 and SYSOUT ends with 'END OF EXECUTION OF PROGRAM CBTRN03C'.
- **Fixtures:** `fixtures/posting/p01-baseline-mixed`, `fixtures/posting/p02-invalid-card`, `fixtures/posting/p03-account-missing`, `fixtures/posting/p04-overlimit-reject`, `fixtures/posting/p05-expired-account`, `fixtures/posting/p06-credits-new-category`, `fixtures/posting/p07-empty-day`

## Findings

Behaviour that looks like a legacy defect. All of it is reproduced by the golden files or follows directly from the source; none of it is to be fixed in a migration.

1. **Expiry overrides over-limit.** CBTRN02C runs the limit and expiry checks back to back (lines 407-420) without testing the reason in between, so reason 103 silently replaces 102 (BR-POST-010).
2. **Payments raise the limit test.** The limit test computes `CYC-CREDIT - CYC-DEBIT + AMT` while negative amounts are added to `CYC-DEBIT` (lines 403-405, 548-552). Payments therefore push an account towards its limit (BR-POST-023). Documented quirk in docs/BASELINE.md; reproduced.
3. **Reason 109 is not a reject.** A failed account REWRITE (lines 554-559) sets reason 109 but the record is not counted, not written to DALYREJS and the transaction is still written (BR-POST-024).
4. **No transactional integrity.** Category balance and account are rewritten before the transaction record is written (lines 440-442); a failed transaction write abends with balances already changed (BR-POST-025).
5. **Wrong status on DALYREJS close error.** 9300-DALYREJS-CLOSE displays `XREFFILE-STATUS` instead of `DALYREJS-STATUS` (CBTRN02C line 649).
6. **Silent high-order truncation.** No ON SIZE ERROR on any ADD/COMPUTE; `WS-TEMP-BAL` is S9(09)V99 while the account fields are S9(10)V99 (BR-POST-029).
7. **Last amount double-counted in TRANREPT.** The end-of-file branch (CBTRN03C lines 197-203) adds the last record's amount again; every non-empty fixture report shows totals overstated by that amount (BR-POST-042).
8. **Last account total missing.** No account-total line for the final card (BR-POST-043).
9. **NEXT SENTENCE exits the loop.** An out-of-range processing date ends the report instead of skipping the record (CBTRN03C line 177, BR-POST-033); with an empty input the report is empty, not a header-only page as p07's README states.
10. **Page break can be skipped.** The page break needs `MOD(WS-LINE-COUNTER, 20) = 0`; account-total and page-total blocks add 2 lines, so the counter can step over a multiple of 20 and a page runs long (BR-POST-040).
11. **Account break on card number.** Totals break on `TRAN-CARD-NUM`; an account with two cards gets two account totals (BR-POST-039).
12. **Two sources for the date range.** The SORT INCLUDE dates are hard-coded in TRANREPT.jcl SYMNAMES while CBTRN03C uses DATEPARM; they can disagree (BR-POST-031, BR-POST-032).
