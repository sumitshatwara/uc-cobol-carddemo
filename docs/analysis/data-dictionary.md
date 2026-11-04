# Data dictionary - CardDemo conversion slices

Every copybook field the four conversion slices touch, with its legacy definition and its target in the Java 21 / PostgreSQL 15 estate. Offsets and lengths are computed by the estate's own codec (`tools/parity/copybook.py`), so they are the ones the parity comparator uses.

Conventions (shared by every slice, see `java/README.md` and `docs/spec/tech-spec.md`):

* **Column** = copybook field name lower-cased with `-` -> `_`; FILLER is not stored. **Java property** = the camelCase form (`ACCT-CURR-BAL` -> `acctCurrBal`).
* **SQL type**: `PIC X(n)` -> `varchar(n)`; DISPLAY / COMP-3 numerics -> `numeric(digits,scale)`; COMP -> `bigint`.
* **Java type**: text -> `String`; scale > 0 -> `BigDecimal` with that scale; scale 0 and <= 9 digits -> `int`; 10-18 digits -> `long`. Arithmetic that COBOL performs without `ROUNDED` uses `RoundingMode.DOWN` at the receiving field's scale.
* **Usage**: every batch record layout below is `DISPLAY` (zoned decimal). Signed fields (`S9`) carry the sign as an EBCDIC-convention overpunch in the last byte (`{`/`A`-`I` positive, `}`/`J`-`R` negative), which is what `cobc -fsign=EBCDIC` writes; the codec decodes and re-encodes it. COMP-3 and COMP appear only in the PL/I fee interface (`FEE-REQ`, below) and in the Db2 VARCHAR length halfwords.
* Text values are right-trimmed when decoded and space-padded when encoded; the golden `.txt` views are right-trimmed, the true LRECL is in `expected/manifest.json`.

## CVACT01Y - AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS (from ACCTDATA.PS)

Record length 300. Target table: `carddemo.account`. Used by: posting, interest, online.

| Field | Offset | Len | PIC | Usage | Scale | Meaning | Column | SQL type | Java type |
|---|---|---|---|---|---|---|---|---|---|
| `ACCT-ID` | 0 | 11 | `9(11)` | DISPLAY (zoned) | 0 | Account number (KSDS key) | `acct_id` | `numeric(11,0)` | `long` |
| `ACCT-ACTIVE-STATUS` | 11 | 1 | `X(01)` | DISPLAY (text) | 0 | Y = active | `acct_active_status` | `varchar(1)` | `String` |
| `ACCT-CURR-BAL` | 12 | 12 | `S9(10)V99` | DISPLAY (zoned, signed overpunch) | 2 | Current balance; CBTRN02C adds TRAN-AMT, CBACT04C adds total interest (except last account: EOF quirk) | `acct_curr_bal` | `numeric(12,2)` | `BigDecimal (scale 2)` |
| `ACCT-CREDIT-LIMIT` | 24 | 12 | `S9(10)V99` | DISPLAY (zoned, signed overpunch) | 2 | Credit limit (posting over-limit check uses cycle amounts, not ACCT-CURR-BAL) | `acct_credit_limit` | `numeric(12,2)` | `BigDecimal (scale 2)` |
| `ACCT-CASH-CREDIT-LIMIT` | 36 | 12 | `S9(10)V99` | DISPLAY (zoned, signed overpunch) | 2 | Cash credit limit (not used by the batch slices) | `acct_cash_credit_limit` | `numeric(12,2)` | `BigDecimal (scale 2)` |
| `ACCT-OPEN-DATE` | 48 | 10 | `X(10)` | DISPLAY (text) | 0 | Open date YYYY-MM-DD | `acct_open_date` | `varchar(10)` | `String` |
| `ACCT-EXPIRAION-DATE` | 58 | 10 | `X(10)` | DISPLAY (text) | 0 | Expiry date YYYY-MM-DD (sic, upstream spelling); posting rejects with reason 103 when it is earlier than `DALYTRAN-ORIG-TS(1:10)` | `acct_expiraion_date` | `varchar(10)` | `String` |
| `ACCT-REISSUE-DATE` | 68 | 10 | `X(10)` | DISPLAY (text) | 0 | Card reissue date | `acct_reissue_date` | `varchar(10)` | `String` |
| `ACCT-CURR-CYC-CREDIT` | 78 | 12 | `S9(10)V99` | DISPLAY (zoned, signed overpunch) | 2 | Cycle credits; posting adds positive amounts; CBACT04C resets to 0 | `acct_curr_cyc_credit` | `numeric(12,2)` | `BigDecimal (scale 2)` |
| `ACCT-CURR-CYC-DEBIT` | 90 | 12 | `S9(10)V99` | DISPLAY (zoned, signed overpunch) | 2 | Cycle debits; posting adds negative amounts; CBACT04C resets to 0 | `acct_curr_cyc_debit` | `numeric(12,2)` | `BigDecimal (scale 2)` |
| `ACCT-ADDR-ZIP` | 102 | 10 | `X(10)` | DISPLAY (text) | 0 | Billing ZIP | `acct_addr_zip` | `varchar(10)` | `String` |
| `ACCT-GROUP-ID` | 112 | 10 | `X(10)` | DISPLAY (text) | 0 | Disclosure group used for the interest-rate lookup (record not found, file status 23 -> retried with group `DEFAULT`) | `acct_group_id` | `varchar(10)` | `String` |

## CVACT03Y - AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS + AIX path

Record length 50. Target table: `carddemo.card_xref`. Used by: posting, interest, online.

| Field | Offset | Len | PIC | Usage | Scale | Meaning | Column | SQL type | Java type |
|---|---|---|---|---|---|---|---|---|---|
| `XREF-CARD-NUM` | 0 | 16 | `X(16)` | DISPLAY (text) | 0 | Card number (KSDS key) | `xref_card_num` | `varchar(16)` | `String` |
| `XREF-CUST-ID` | 16 | 9 | `9(09)` | DISPLAY (zoned) | 0 | Customer id | `xref_cust_id` | `numeric(9,0)` | `int` |
| `XREF-ACCT-ID` | 25 | 11 | `9(11)` | DISPLAY (zoned) | 0 | Account id (alternate index path XREFFIL1 in INTCALC) | `xref_acct_id` | `numeric(11,0)` | `long` |

## CVTRA01Y - AWS.M2.CARDDEMO.TCATBALF.VSAM.KSDS

Record length 50. Target table: `carddemo.tran_cat_balance`. Used by: posting, interest.

| Field | Offset | Len | PIC | Usage | Scale | Meaning | Column | SQL type | Java type |
|---|---|---|---|---|---|---|---|---|---|
| `TRANCAT-ACCT-ID` | 0 | 11 | `9(11)` | DISPLAY (zoned) | 0 | Key part 1: account | `trancat_acct_id` | `numeric(11,0)` | `long` |
| `TRANCAT-TYPE-CD` | 11 | 2 | `X(02)` | DISPLAY (text) | 0 | Key part 2: transaction type | `trancat_type_cd` | `varchar(2)` | `String` |
| `TRANCAT-CD` | 13 | 4 | `9(04)` | DISPLAY (zoned) | 0 | Key part 3: transaction category | `trancat_cd` | `numeric(4,0)` | `int` |
| `TRAN-CAT-BAL` | 17 | 11 | `S9(09)V99` | DISPLAY (zoned, signed overpunch) | 2 | Category balance; posting adds TRAN-AMT / creates the record; interest base | `tran_cat_bal` | `numeric(11,2)` | `BigDecimal (scale 2)` |

## CVTRA02Y - AWS.M2.CARDDEMO.DISCGRP.VSAM.KSDS

Record length 50. Target table: `carddemo.disclosure_group`. Used by: interest.

| Field | Offset | Len | PIC | Usage | Scale | Meaning | Column | SQL type | Java type |
|---|---|---|---|---|---|---|---|---|---|
| `DIS-ACCT-GROUP-ID` | 0 | 10 | `X(10)` | DISPLAY (text) | 0 | Key part 1: account group (DEFAULT = fallback row) | `dis_acct_group_id` | `varchar(10)` | `String` |
| `DIS-TRAN-TYPE-CD` | 10 | 2 | `X(02)` | DISPLAY (text) | 0 | Key part 2: transaction type | `dis_tran_type_cd` | `varchar(2)` | `String` |
| `DIS-TRAN-CAT-CD` | 12 | 4 | `9(04)` | DISPLAY (zoned) | 0 | Key part 3: transaction category | `dis_tran_cat_cd` | `numeric(4,0)` | `int` |
| `DIS-INT-RATE` | 16 | 6 | `S9(04)V99` | DISPLAY (zoned, signed overpunch) | 2 | Annual interest rate in percent; monthly interest = bal * rate / 1200, truncated; 0 -> no interest | `dis_int_rate` | `numeric(6,2)` | `BigDecimal (scale 2)` |

## CVTRA03Y - AWS.M2.CARDDEMO.TRANTYPE.VSAM.KSDS

Record length 60. Target table: `carddemo.tran_type`. Used by: posting (TRANREPT).

| Field | Offset | Len | PIC | Usage | Scale | Meaning | Column | SQL type | Java type |
|---|---|---|---|---|---|---|---|---|---|
| `TRAN-TYPE` | 0 | 2 | `X(02)` | DISPLAY (text) | 0 | Transaction type code (KSDS key) | `tran_type` | `varchar(2)` | `String` |
| `TRAN-TYPE-DESC` | 2 | 50 | `X(50)` | DISPLAY (text) | 0 | Type description (TRANREPT report) | `tran_type_desc` | `varchar(50)` | `String` |

## CVTRA04Y - AWS.M2.CARDDEMO.TRANCATG.VSAM.KSDS

Record length 60. Target table: `carddemo.tran_category`. Used by: posting (TRANREPT).

| Field | Offset | Len | PIC | Usage | Scale | Meaning | Column | SQL type | Java type |
|---|---|---|---|---|---|---|---|---|---|
| `TRAN-TYPE-CD` | 0 | 2 | `X(02)` | DISPLAY (text) | 0 | Transaction type code | `tran_type_cd` | `varchar(2)` | `String` |
| `TRAN-CAT-CD` | 2 | 4 | `9(04)` | DISPLAY (zoned) | 0 | Transaction category code | `tran_cat_cd` | `numeric(4,0)` | `int` |
| `TRAN-CAT-TYPE-DESC` | 6 | 50 | `X(50)` | DISPLAY (text) | 0 | Category description (TRANREPT report) | `tran_cat_type_desc` | `varchar(50)` | `String` |

## CVTRA06Y - AWS.M2.CARDDEMO.DALYTRAN.PS

Record length 350. Target table: `carddemo.daily_transaction`. Used by: posting.

| Field | Offset | Len | PIC | Usage | Scale | Meaning | Column | SQL type | Java type |
|---|---|---|---|---|---|---|---|---|---|
| `DALYTRAN-ID` | 0 | 16 | `X(16)` | DISPLAY (text) | 0 | Daily transaction id (becomes TRAN-ID) | `dalytran_id` | `varchar(16)` | `String` |
| `DALYTRAN-TYPE-CD` | 16 | 2 | `X(02)` | DISPLAY (text) | 0 | Daily input: transaction type | `dalytran_type_cd` | `varchar(2)` | `String` |
| `DALYTRAN-CAT-CD` | 18 | 4 | `9(04)` | DISPLAY (zoned) | 0 | Daily input: transaction category | `dalytran_cat_cd` | `numeric(4,0)` | `int` |
| `DALYTRAN-SOURCE` | 22 | 10 | `X(10)` | DISPLAY (text) | 0 | Daily input: source (POS TERM, OPERATOR, System) | `dalytran_source` | `varchar(10)` | `String` |
| `DALYTRAN-DESC` | 32 | 100 | `X(100)` | DISPLAY (text) | 0 | Daily input: description | `dalytran_desc` | `varchar(100)` | `String` |
| `DALYTRAN-AMT` | 132 | 11 | `S9(09)V99` | DISPLAY (zoned, signed overpunch) | 2 | Daily input: signed amount, 2 decimals | `dalytran_amt` | `numeric(11,2)` | `BigDecimal (scale 2)` |
| `DALYTRAN-MERCHANT-ID` | 143 | 9 | `9(09)` | DISPLAY (zoned) | 0 | Daily input: merchant id (0 for interest) | `dalytran_merchant_id` | `numeric(9,0)` | `int` |
| `DALYTRAN-MERCHANT-NAME` | 152 | 50 | `X(50)` | DISPLAY (text) | 0 | Daily input: merchant name | `dalytran_merchant_name` | `varchar(50)` | `String` |
| `DALYTRAN-MERCHANT-CITY` | 202 | 50 | `X(50)` | DISPLAY (text) | 0 | Daily input: merchant city | `dalytran_merchant_city` | `varchar(50)` | `String` |
| `DALYTRAN-MERCHANT-ZIP` | 252 | 10 | `X(10)` | DISPLAY (text) | 0 | Daily input: merchant ZIP | `dalytran_merchant_zip` | `varchar(10)` | `String` |
| `DALYTRAN-CARD-NUM` | 262 | 16 | `X(16)` | DISPLAY (text) | 0 | Daily input: card number | `dalytran_card_num` | `varchar(16)` | `String` |
| `DALYTRAN-ORIG-TS` | 278 | 26 | `X(26)` | DISPLAY (text) | 0 | Daily input: origination timestamp (26 chars, DB2 format) | `dalytran_orig_ts` | `varchar(26)` | `String` |
| `DALYTRAN-PROC-TS` | 304 | 26 | `X(26)` | DISPLAY (text) | 0 | Daily input: processing timestamp (overwritten from the clock on posting) | `dalytran_proc_ts` | `varchar(26)` | `String` |

## CVTRA05Y - AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS (posting), AWS.M2.CARDDEMO.SYSTRAN(+1) (interest)

Record length 350. Target table: `carddemo."transaction"` (common V10, posting TRANSACT); interest SYSTRAN is slice-owned (V200-299). Used by: posting, interest.

| Field | Offset | Len | PIC | Usage | Scale | Meaning | Column | SQL type | Java type |
|---|---|---|---|---|---|---|---|---|---|
| `TRAN-ID` | 0 | 16 | `X(16)` | DISPLAY (text) | 0 | Transaction id (KSDS key); interest: PARM date + 6-digit suffix | `tran_id` | `varchar(16)` | `String` |
| `TRAN-TYPE-CD` | 16 | 2 | `X(02)` | DISPLAY (text) | 0 | Transaction type code | `tran_type_cd` | `varchar(2)` | `String` |
| `TRAN-CAT-CD` | 18 | 4 | `9(04)` | DISPLAY (zoned) | 0 | Transaction category code | `tran_cat_cd` | `numeric(4,0)` | `int` |
| `TRAN-SOURCE` | 22 | 10 | `X(10)` | DISPLAY (text) | 0 | Source (POS TERM, OPERATOR, System) | `tran_source` | `varchar(10)` | `String` |
| `TRAN-DESC` | 32 | 100 | `X(100)` | DISPLAY (text) | 0 | Description | `tran_desc` | `varchar(100)` | `String` |
| `TRAN-AMT` | 132 | 11 | `S9(09)V99` | DISPLAY (zoned, signed overpunch) | 2 | Signed amount, 2 decimals | `tran_amt` | `numeric(11,2)` | `BigDecimal (scale 2)` |
| `TRAN-MERCHANT-ID` | 143 | 9 | `9(09)` | DISPLAY (zoned) | 0 | Merchant id (0 for interest) | `tran_merchant_id` | `numeric(9,0)` | `int` |
| `TRAN-MERCHANT-NAME` | 152 | 50 | `X(50)` | DISPLAY (text) | 0 | Merchant name | `tran_merchant_name` | `varchar(50)` | `String` |
| `TRAN-MERCHANT-CITY` | 202 | 50 | `X(50)` | DISPLAY (text) | 0 | Merchant city | `tran_merchant_city` | `varchar(50)` | `String` |
| `TRAN-MERCHANT-ZIP` | 252 | 10 | `X(10)` | DISPLAY (text) | 0 | Merchant ZIP | `tran_merchant_zip` | `varchar(10)` | `String` |
| `TRAN-CARD-NUM` | 262 | 16 | `X(16)` | DISPLAY (text) | 0 | Card number | `tran_card_num` | `varchar(16)` | `String` |
| `TRAN-ORIG-TS` | 278 | 26 | `X(26)` | DISPLAY (text) | 0 | Origination timestamp (26 chars, DB2 format) | `tran_orig_ts` | `varchar(26)` | `String` |
| `TRAN-PROC-TS` | 304 | 26 | `X(26)` | DISPLAY (text) | 0 | Processing timestamp from the frozen clock 2022-07-05 10:00:00.00 | `tran_proc_ts` | `varchar(26)` | `String` |

## REJTRAN - AWS.M2.CARDDEMO.DALYREJS(+1) (tools/parity/layouts/REJTRAN.cpy)

Record length 430. Target table: slice-owned reject table (posting, V100-199). Used by: posting.

| Field | Offset | Len | PIC | Usage | Scale | Meaning | Column | SQL type | Java type |
|---|---|---|---|---|---|---|---|---|---|
| `DALYTRAN-ID` | 0 | 16 | `X(16)` | DISPLAY (text) | 0 | Daily transaction id (becomes TRAN-ID) | `dalytran_id` | `varchar(16)` | `String` |
| `DALYTRAN-TYPE-CD` | 16 | 2 | `X(02)` | DISPLAY (text) | 0 | Daily input: transaction type | `dalytran_type_cd` | `varchar(2)` | `String` |
| `DALYTRAN-CAT-CD` | 18 | 4 | `9(04)` | DISPLAY (zoned) | 0 | Daily input: transaction category | `dalytran_cat_cd` | `numeric(4,0)` | `int` |
| `DALYTRAN-SOURCE` | 22 | 10 | `X(10)` | DISPLAY (text) | 0 | Daily input: source (POS TERM, OPERATOR, System) | `dalytran_source` | `varchar(10)` | `String` |
| `DALYTRAN-DESC` | 32 | 100 | `X(100)` | DISPLAY (text) | 0 | Daily input: description | `dalytran_desc` | `varchar(100)` | `String` |
| `DALYTRAN-AMT` | 132 | 11 | `S9(09)V99` | DISPLAY (zoned, signed overpunch) | 2 | Daily input: signed amount, 2 decimals | `dalytran_amt` | `numeric(11,2)` | `BigDecimal (scale 2)` |
| `DALYTRAN-MERCHANT-ID` | 143 | 9 | `9(09)` | DISPLAY (zoned) | 0 | Daily input: merchant id (0 for interest) | `dalytran_merchant_id` | `numeric(9,0)` | `int` |
| `DALYTRAN-MERCHANT-NAME` | 152 | 50 | `X(50)` | DISPLAY (text) | 0 | Daily input: merchant name | `dalytran_merchant_name` | `varchar(50)` | `String` |
| `DALYTRAN-MERCHANT-CITY` | 202 | 50 | `X(50)` | DISPLAY (text) | 0 | Daily input: merchant city | `dalytran_merchant_city` | `varchar(50)` | `String` |
| `DALYTRAN-MERCHANT-ZIP` | 252 | 10 | `X(10)` | DISPLAY (text) | 0 | Daily input: merchant ZIP | `dalytran_merchant_zip` | `varchar(10)` | `String` |
| `DALYTRAN-CARD-NUM` | 262 | 16 | `X(16)` | DISPLAY (text) | 0 | Daily input: card number | `dalytran_card_num` | `varchar(16)` | `String` |
| `DALYTRAN-ORIG-TS` | 278 | 26 | `X(26)` | DISPLAY (text) | 0 | Daily input: origination timestamp (26 chars, DB2 format) | `dalytran_orig_ts` | `varchar(26)` | `String` |
| `DALYTRAN-PROC-TS` | 304 | 26 | `X(26)` | DISPLAY (text) | 0 | Daily input: processing timestamp (overwritten from the clock on posting) | `dalytran_proc_ts` | `varchar(26)` | `String` |
| `REJ-FAIL-REASON` | 350 | 4 | `9(04)` | DISPLAY (zoned) | 0 | Reject reason code (100 invalid card, 101 account not found, 102 over limit, 103 received after expiry, 109 account rewrite failed) | `rej_fail_reason` | `numeric(4,0)` | `int` |
| `REJ-FAIL-REASON-DESC` | 354 | 76 | `X(76)` | DISPLAY (text) | 0 | Reject reason text | `rej_fail_reason_desc` | `varchar(76)` | `String` |

## CSUSR01Y - AWS.M2.CARDDEMO.USRSEC.VSAM.KSDS (CICS file USRSEC)

Record length 80. Target table: online-owned (V400-499). Used by: online.

| Field | Offset | Len | PIC | Usage | Scale | Meaning | Column | SQL type | Java type |
|---|---|---|---|---|---|---|---|---|---|
| `SEC-USR-ID` | 0 | 8 | `X(08)` | DISPLAY (text) | 0 |  | `sec_usr_id` | `varchar(8)` | `String` |
| `SEC-USR-FNAME` | 8 | 20 | `X(20)` | DISPLAY (text) | 0 |  | `sec_usr_fname` | `varchar(20)` | `String` |
| `SEC-USR-LNAME` | 28 | 20 | `X(20)` | DISPLAY (text) | 0 |  | `sec_usr_lname` | `varchar(20)` | `String` |
| `SEC-USR-PWD` | 48 | 8 | `X(08)` | DISPLAY (text) | 0 |  | `sec_usr_pwd` | `varchar(8)` | `String` |
| `SEC-USR-TYPE` | 56 | 1 | `X(01)` | DISPLAY (text) | 0 |  | `sec_usr_type` | `varchar(1)` | `String` |
| `SEC-USR-FILLER` | 57 | 23 | `X(23)` | DISPLAY (text) | 0 |  | `sec_usr_filler` | `varchar(23)` | `String` |

## CVACT02Y - AWS.M2.CARDDEMO.CARDDATA.VSAM.KSDS

Record length 150. Target table: online-owned (V400-499). Used by: online.

| Field | Offset | Len | PIC | Usage | Scale | Meaning | Column | SQL type | Java type |
|---|---|---|---|---|---|---|---|---|---|
| `CARD-NUM` | 0 | 16 | `X(16)` | DISPLAY (text) | 0 |  | `card_num` | `varchar(16)` | `String` |
| `CARD-ACCT-ID` | 16 | 11 | `9(11)` | DISPLAY (zoned) | 0 |  | `card_acct_id` | `numeric(11,0)` | `long` |
| `CARD-CVV-CD` | 27 | 3 | `9(03)` | DISPLAY (zoned) | 0 |  | `card_cvv_cd` | `numeric(3,0)` | `int` |
| `CARD-EMBOSSED-NAME` | 30 | 50 | `X(50)` | DISPLAY (text) | 0 |  | `card_embossed_name` | `varchar(50)` | `String` |
| `CARD-EXPIRAION-DATE` | 80 | 10 | `X(10)` | DISPLAY (text) | 0 |  | `card_expiraion_date` | `varchar(10)` | `String` |
| `CARD-ACTIVE-STATUS` | 90 | 1 | `X(01)` | DISPLAY (text) | 0 |  | `card_active_status` | `varchar(1)` | `String` |

## CVCUS01Y - AWS.M2.CARDDEMO.CUSTDATA.VSAM.KSDS

Record length 500. Target table: online-owned (V400-499). Used by: online.

| Field | Offset | Len | PIC | Usage | Scale | Meaning | Column | SQL type | Java type |
|---|---|---|---|---|---|---|---|---|---|
| `CUST-ID` | 0 | 9 | `9(09)` | DISPLAY (zoned) | 0 |  | `cust_id` | `numeric(9,0)` | `int` |
| `CUST-FIRST-NAME` | 9 | 25 | `X(25)` | DISPLAY (text) | 0 |  | `cust_first_name` | `varchar(25)` | `String` |
| `CUST-MIDDLE-NAME` | 34 | 25 | `X(25)` | DISPLAY (text) | 0 |  | `cust_middle_name` | `varchar(25)` | `String` |
| `CUST-LAST-NAME` | 59 | 25 | `X(25)` | DISPLAY (text) | 0 |  | `cust_last_name` | `varchar(25)` | `String` |
| `CUST-ADDR-LINE-1` | 84 | 50 | `X(50)` | DISPLAY (text) | 0 |  | `cust_addr_line_1` | `varchar(50)` | `String` |
| `CUST-ADDR-LINE-2` | 134 | 50 | `X(50)` | DISPLAY (text) | 0 |  | `cust_addr_line_2` | `varchar(50)` | `String` |
| `CUST-ADDR-LINE-3` | 184 | 50 | `X(50)` | DISPLAY (text) | 0 |  | `cust_addr_line_3` | `varchar(50)` | `String` |
| `CUST-ADDR-STATE-CD` | 234 | 2 | `X(02)` | DISPLAY (text) | 0 |  | `cust_addr_state_cd` | `varchar(2)` | `String` |
| `CUST-ADDR-COUNTRY-CD` | 236 | 3 | `X(03)` | DISPLAY (text) | 0 |  | `cust_addr_country_cd` | `varchar(3)` | `String` |
| `CUST-ADDR-ZIP` | 239 | 10 | `X(10)` | DISPLAY (text) | 0 |  | `cust_addr_zip` | `varchar(10)` | `String` |
| `CUST-PHONE-NUM-1` | 249 | 15 | `X(15)` | DISPLAY (text) | 0 |  | `cust_phone_num_1` | `varchar(15)` | `String` |
| `CUST-PHONE-NUM-2` | 264 | 15 | `X(15)` | DISPLAY (text) | 0 |  | `cust_phone_num_2` | `varchar(15)` | `String` |
| `CUST-SSN` | 279 | 9 | `9(09)` | DISPLAY (zoned) | 0 |  | `cust_ssn` | `numeric(9,0)` | `int` |
| `CUST-GOVT-ISSUED-ID` | 288 | 20 | `X(20)` | DISPLAY (text) | 0 |  | `cust_govt_issued_id` | `varchar(20)` | `String` |
| `CUST-DOB-YYYY-MM-DD` | 308 | 10 | `X(10)` | DISPLAY (text) | 0 |  | `cust_dob_yyyy_mm_dd` | `varchar(10)` | `String` |
| `CUST-EFT-ACCOUNT-ID` | 318 | 10 | `X(10)` | DISPLAY (text) | 0 |  | `cust_eft_account_id` | `varchar(10)` | `String` |
| `CUST-PRI-CARD-HOLDER-IND` | 328 | 1 | `X(01)` | DISPLAY (text) | 0 |  | `cust_pri_card_holder_ind` | `varchar(1)` | `String` |
| `CUST-FICO-CREDIT-SCORE` | 329 | 3 | `9(03)` | DISPLAY (zoned) | 0 |  | `cust_fico_credit_score` | `numeric(3,0)` | `int` |

## COBTUPDT / CBTRNDYN input card - INPFILE (LRECL 53, `WS-INPUT-REC` in COBTUPDT.cbl lines 71-76)

| Field | Offset | Len | PIC | Usage | Meaning | Java |
|---|---|---|---|---|---|---|
| `INPUT-REC-TYPE` | 0 | 1 | `X(1)` | DISPLAY | Action: `A` add, `U` update, `D` delete, `*` comment, anything else = error | `String action` |
| `INPUT-REC-NUMBER` | 1 | 2 | `X(2)` | DISPLAY | Transaction type code -> `tr_type` | `String trType` |
| `INPUT-REC-DESC` | 3 | 50 | `X(50)` | DISPLAY | Description -> `tr_description`, sent space-padded to 50 by ocesql | `String trDescription` (keep padding) |

DYNSQL (LRECL 80): one SQL statement per card, `--` lines are comments; read by CBTRNDYN into the VARCHAR host variable `WS-SQL-STMT` (`-LEN`/`-ARR`).

## Db2 tables (DCLGEN `DCLTRTYP`, `DCLTRTCT`; DDL `db/postgres/01-transaction-type.sql`, Flyway V300)

| Db2 column | Db2 type | Host variable | PIC / usage | PostgreSQL | Java |
|---|---|---|---|---|---|
| `TR_TYPE` | CHAR(2) NOT NULL, PK | `DCL-TR-TYPE` | `X(2)` | `carddemo.transaction_type.tr_type char(2)` | `String trType` |
| `TR_DESCRIPTION` | VARCHAR(50) NOT NULL | `DCL-TR-DESCRIPTION-LEN` / `-TEXT` | `S9(4) COMP` / `X(50)` | `tr_description varchar(50)` (stored padded to 50 by ocesql; golden CSVs keep the padding) | `String trDescription` |
| `TRC_TYPE_CODE` | CHAR(2) NOT NULL, PK part, FK -> TR_TYPE | `DCL-TRC-TYPE-CODE` | `X(2)` | `transaction_type_categor.trc_type_code char(2)` | `String trcTypeCode` |
| `TRC_TYPE_CATEGORY` | CHAR(4) NOT NULL, PK part | `DCL-TRC-TYPE-CATEGORY` | `X(4)` | `trc_type_category char(4)` | `String trcTypeCategory` |
| `TRC_CAT_DATA` | VARCHAR(50) NOT NULL | `DCL-TRC-CAT-DATA-LEN` / `-TEXT` | `S9(4) COMP` / `X(50)` | `trc_cat_data varchar(50)` | `String trcCatData` |

SQLCODE mapping under the target (PostgreSQL via JDBC) must reproduce what the golden files recorded under Open COBOL ESQL: duplicate key -403 (Db2 would return -803), 0-row DELETE returns 0 (so COBTUPDT prints `RECORD DELETED SUCCESSFULLY`), and any statement after a failure in the same transaction gets -400 (transaction aborted).

## PL/I fee interface - `FEE-REQ` (app/cbl/CBFEEDRV.cbl lines 54-62, PL/I `FEE_REQ BASED(REQ_PTR)` in app/pli/CBFEECAL.pli)

| Field | Offset | Len | PIC | Usage | PL/I | Meaning | Java |
|---|---|---|---|---|---|---|---|
| `FEE-ACCT-ID` | 0 | 11 | `X(11)` | DISPLAY | CHAR(11) | Account id | `String acctId` |
| `FEE-GROUP-ID` | 11 | 10 | `X(10)` | DISPLAY | CHAR(10) | Group (`GOLD` with cycle balance < 100.00 -> waived, RC 4) | `String groupId` |
| `FEE-CYCLE-BAL` | 21 | 7 | `S9(10)V99` | COMP-3 | DECIMAL FIXED(12,2) | Cycle balance = cycle credit - cycle debit | `BigDecimal cycleBal` (scale 2) |
| `FEE-LATE-DAYS` | 28 | 2 | `S9(04)` | COMP | FIXED BINARY(15) | Days overdue (<= 0 -> fee 0, RC 0; the average is computed first, so 0 cycle days still gives RC 8) | `int lateDays` |
| `FEE-CYCLE-DAYS` | 30 | 2 | `S9(04)` | COMP | FIXED BINARY(15) | Days in cycle (0 -> ZERODIVIDE, RC 8) | `int cycleDays` |
| `FEE-AMT` | 32 | 5 | `S9(07)V99` | COMP-3 | DECIMAL FIXED(9,2) | Fee = bal * 2.5 / 100 truncated, floor 5.00, cap 35.00 | `BigDecimal feeAmt` (scale 2, DOWN) |
| `FEE-AVG-DAILY` | 37 | 7 | `S9(10)V99` | COMP-3 | DECIMAL FIXED(12,2) | Average daily balance = bal / cycle days, truncated | `BigDecimal avgDaily` (scale 2, DOWN) |
| `FEE-RC` | 44 | 2 | `S9(04)` | COMP | FIXED BINARY(15) | 0 ok, 4 waived, 8 zero divide / error | `int rc` |

## HLASM `COBDATFT` parameter - `CODATECN-REC` (app/cpy/CODATECN.cpy)

| Field | Len | PIC | Meaning | Java |
|---|---|---|---|---|
| `CODATECN-TYPE` | 1 | `X` | Input format: `1` = YYYYMMDD, `2` = YYYY-MM-DD | `String inType` |
| `CODATECN-INP-DATE` | 20 | `X(20)` | Input date (REDEFINES by format) | `String inDate` |
| `CODATECN-OUTTYPE` | 1 | `X` | Output format: `1` = YYYY-MM-DD, `2` = YYYYMMDD | `String outType` |
| `CODATECN-0UT-DATE` | 20 | `X(20)` | Output date (sic `0UT`) | `String outDate` |
| `CODATECN-ERROR-MSG` | 38 | `X(38)` | `INVALID INPUT` on a type/format mismatch | `String errorMsg` |

`MVSWAIT` takes one fullword `MVSWAIT-TIME PIC 9(8) COMP` (hundredths of a second) -> `long hundredths`; Java stand-in is a no-op/`Thread.sleep` with no functional output.

## Out of scope

`CVEXPORT` (CBEXPORT/CBIMPORT) is not mapped: both programs fail to compile upstream (`EXPORT-SEQUENCE-NUM` is not declared by any shipped copybook).
