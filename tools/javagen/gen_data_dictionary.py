#!/usr/bin/env python3
"""Generate docs/analysis/data-dictionary.md: every copybook / record field the four
conversion slices touch, with PIC, usage, scale, meaning, target column and Java type.

Layouts are read with tools/parity/copybook.py (the comparator's own parser), so offsets and
lengths are exactly what parity checks.  Meanings are curated below.

    python3 tools/javagen/gen_data_dictionary.py
"""
from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from tools.parity.copybook import parse_copybook  # noqa: E402

OUT = ROOT / "docs/analysis/data-dictionary.md"

# copybook, title, used by (slices), target table (None = file/report only), dataset(s)
LAYOUTS = [
    ("CVACT01Y", "Account master record", "posting, interest, online", "carddemo.account", "ACCTDATA.PS -> ACCTDATA.VSAM.KSDS (key ACCT-ID)"),
    ("CVACT03Y", "Card cross-reference", "posting, interest, online", "carddemo.card_xref", "CARDXREF.PS -> CARDXREF.VSAM.KSDS (key card; AIX on XREF-ACCT-ID)"),
    ("CVTRA01Y", "Transaction category balance", "posting, interest", "carddemo.tran_cat_balance", "TCATBALF.PS -> TCATBALF.VSAM.KSDS (key acct+type+cat)"),
    ("CVTRA02Y", "Disclosure group (interest rates)", "interest", "carddemo.disclosure_group", "DISCGRP.PS -> DISCGRP.VSAM.KSDS (key group+type+cat)"),
    ("CVTRA03Y", "Transaction type (VSAM)", "posting", "carddemo.tran_type", "TRANTYPE.PS -> TRANTYPE.VSAM.KSDS"),
    ("CVTRA04Y", "Transaction category type (VSAM)", "posting", "carddemo.tran_category", "TRANCATG.PS -> TRANCATG.VSAM.KSDS"),
    ("CVTRA05Y", "Posted transaction", "posting, interest", "carddemo.transact", "TRANSACT.VSAM.KSDS (posting output), SYSTRAN(+1) (interest output)"),
    ("CVTRA06Y", "Daily transaction (input)", "posting", "carddemo.daily_tran", "DALYTRAN.PS (sequential)"),
    ("REJTRAN", "Daily reject record (tools/parity/layouts)", "posting", None, "DALYREJS(+1) GDG, 430 bytes"),
    ("CVCUS01Y", "Customer master", "online", None, "CUSTDATA.VSAM.KSDS (CICS, analysis-only)"),
    ("CSUSR01Y", "User security record", "online", None, "USRSEC.VSAM.KSDS (CICS sign-on, analysis-only)"),
]

MEANING = {
    "ACCT-ID": "Account number", "ACCT-ACTIVE-STATUS": "Y = active, N = inactive",
    "ACCT-CURR-BAL": "Current balance (posting adds every amount; interest adds monthly interest)",
    "ACCT-CREDIT-LIMIT": "Credit limit", "ACCT-CASH-CREDIT-LIMIT": "Cash credit limit",
    "ACCT-OPEN-DATE": "Open date YYYY-MM-DD", "ACCT-EXPIRAION-DATE": "Expiry date YYYY-MM-DD (typo kept from source); posting rejects later transactions (103)",
    "ACCT-REISSUE-DATE": "Card reissue date YYYY-MM-DD",
    "ACCT-CURR-CYC-CREDIT": "Credits this cycle (limit check uses cycle amounts); reset to 0 by interest",
    "ACCT-CURR-CYC-DEBIT": "Debits this cycle; reset to 0 by interest", "ACCT-ADDR-ZIP": "Billing zip",
    "ACCT-GROUP-ID": "Disclosure group id (rate table key; blank/unknown falls back to DEFAULT)",
    "XREF-CARD-NUM": "Card number (key)", "XREF-CUST-ID": "Customer id", "XREF-ACCT-ID": "Account id (alternate key)",
    "TRANCAT-ACCT-ID": "Account id (key part 1)", "TRANCAT-TYPE-CD": "Transaction type code (key part 2)",
    "TRANCAT-CD": "Transaction category code (key part 3)", "TRAN-CAT-BAL": "Category balance; basis of monthly interest",
    "DIS-ACCT-GROUP-ID": "Disclosure group id (key part 1)", "DIS-TRAN-TYPE-CD": "Type code (key part 2)",
    "DIS-TRAN-CAT-CD": "Category code (key part 3)", "DIS-INT-RATE": "Annual interest rate in percent (interest = bal * rate / 1200, truncated)",
    "TRAN-TYPE": "Transaction type code", "TRAN-TYPE-DESC": "Type description (report)",
    "TRAN-TYPE-CD": "Transaction type code", "TRAN-CAT-CD": "Transaction category code",
    "TRAN-CAT-TYPE-DESC": "Category description (report)",
    "TRAN-ID": "Transaction id (key). Interest: PARM date + 6-digit sequence", "TRAN-SOURCE": "Origin (e.g. POS TERM, System)",
    "TRAN-DESC": "Description", "TRAN-AMT": "Amount; negative = credit/payment", "TRAN-MERCHANT-ID": "Merchant id",
    "TRAN-MERCHANT-NAME": "Merchant name", "TRAN-MERCHANT-CITY": "Merchant city", "TRAN-MERCHANT-ZIP": "Merchant zip",
    "TRAN-CARD-NUM": "Card number", "TRAN-ORIG-TS": "Origination timestamp YYYY-MM-DD-hh.mm.ss.ffffff",
    "TRAN-PROC-TS": "Processing timestamp = frozen run clock (Db2 format)",
    "REJ-FAIL-REASON": "Reject reason: 100 invalid card, 101 account not found, 102 over limit, 103 expired",
    "REJ-FAIL-REASON-DESC": "Reject reason text",
    "SEC-USR-ID": "User id (key, upper-cased)", "SEC-USR-FNAME": "First name", "SEC-USR-LNAME": "Last name",
    "SEC-USR-PWD": "Password (plain text in legacy)", "SEC-USR-TYPE": "A = admin, U = regular user", "SEC-USR-FILLER": "Unused",
}
for k in list(MEANING):
    if k.startswith("TRAN-") and k not in ("TRAN-TYPE", "TRAN-TYPE-DESC", "TRAN-CAT-TYPE-DESC", "TRAN-CAT-BAL"):
        MEANING.setdefault("DALYTRAN-" + k[5:], MEANING[k].replace("= frozen run clock (Db2 format)", "(input; posting overwrites with run clock)"))


def pic(f) -> str:
    if f.kind == "text":
        return f"X({f.length})"
    ip = f.digits - f.scale
    s = ("S" if f.signed else "") + f"9({ip})" + (f"V9({f.scale})" if f.scale else "")
    return s


def usage(f) -> str:
    return {"text": "DISPLAY", "display": "DISPLAY (zoned" + (", overpunch sign)" if f.signed else ")"),
            "comp-3": "COMP-3", "comp": "COMP"}[f.kind]


def coltype(f) -> tuple[str, str]:
    if f.kind == "text":
        return f"VARCHAR({f.length})", "String"
    if f.scale == 0:
        return "BIGINT", "Long"
    return f"NUMERIC({f.digits},{f.scale})", "BigDecimal"


def camel(name: str) -> str:
    p = name.lower().split("-")
    return p[0] + "".join(x.capitalize() for x in p[1:])


def humanize(name: str) -> str:
    return name.replace("-", " ").title()


def main() -> int:
    out = ["# Data dictionary", "",
           "Every record field the four conversion slices touch: legacy PIC, usage, scale and offset, its meaning,",
           "and the PostgreSQL column and Java type it maps to.  Layouts come from `tools/parity/copybook.py` (the",
           "comparator's parser), so offsets are exactly what parity checks.  Generated by",
           "`tools/javagen/gen_data_dictionary.py`; the tables are created by Flyway",
           "`java/common/.../V001__vsam_dataset_tables.sql` (generated by `tools/javagen/gen_vsam.py`).", "",
           "## Mapping rules", "",
           "| COBOL | PostgreSQL | Java | Rule |", "|---|---|---|---|",
           "| `PIC X(n)` | `VARCHAR(n) COLLATE \"C\"` | `String` | stored right-trimmed, space-padded on export; `C` collation = VSAM key byte order |",
           "| `PIC [S]9(p)` | `BIGINT` | `Long` | integer ids and codes |",
           "| `PIC [S]9(p)V9(s)` DISPLAY | `NUMERIC(p+s,s)` | `BigDecimal` | never `double`; COMPUTE without ROUNDED = `RoundingMode.DOWN` |",
           "| `COMP-3` | `NUMERIC(p,s)` | `BigDecimal` | packed decimal, sign nibble C/F positive, D/B negative |",
           "| `COMP` / `BINARY` | `SMALLINT`/`INTEGER`/`BIGINT` | `short`/`int`/`long` | big-endian; PL/I FIXED BINARY(15) = COMP halfword |",
           "| signed zoned on output | - | `RecordCodec.encodeDisplay` | EBCDIC overpunch on the last digit: `{ABCDEFGHI` positive, `}JKLMNOPQR` negative |",
           "| `FILLER` | not stored | - | not compared by parity |", ""]
    for cpy, title, slices, table, dsn in LAYOUTS:
        fields = parse_copybook(cpy)
        length = max(f.offset + f.length for f in fields)
        out += [f"## {cpy} - {title}", "",
                f"Used by: {slices}. Dataset: {dsn}. Record length {length}. Target: "
                + (f"`{table}`" if table else "file / screen only (no table)") + ".", "",
                "| Field | Offset | PIC | Usage | Scale | Meaning | Column | Java |", "|---|---|---|---|---|---|---|---|"]
        for f in fields:
            if f.is_filler:
                out.append(f"| FILLER | {f.offset} | X({f.length}) | DISPLAY | - | unused | - | - |")
                continue
            sql, jt = coltype(f)
            col = f"`{f.name.lower().replace('-', '_')}` {sql}" if table else "-"
            out.append(f"| `{f.name}` | {f.offset} | `{pic(f)}` | {usage(f)} | {f.scale} | {MEANING.get(f.name, humanize(f.name))} | {col} | `{jt} {camel(f.name)}` |")
        out.append("")
    out += ["## COBTUPDT input record (INPFILE, inline FD, 53 bytes)", "",
            "Used by: trantype (MNTTRDB2, MNTTRDYN).  `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:39-46, 71-77`.", "",
            "| Field | Offset | PIC | Usage | Meaning | Java |", "|---|---|---|---|---|---|",
            "| `INPUT-REC-TYPE` | 0 | `X(1)` | DISPLAY | Action: A add, U update, D delete, `*` comment; anything else = invalid | `String action` |",
            "| `INPUT-REC-NUMBER` | 1 | `X(2)` | DISPLAY | Transaction type code (not validated) | `String trType` |",
            "| `INPUT-REC-DESC` | 3 | `X(50)` | DISPLAY | Description, sent space-padded to 50 by ocesql | `String trDescription` |", "",
            "## DYNSQL card (80 bytes)", "",
            "Used by: trantype-dynamic.  Free SQL text; `--` in columns 1-2 is a comment; up to 10 lines are concatenated into `WS-SQL-STMT`. Java: `List<String>`.", "",
            "## Db2 tables (DCLGEN DCLTRTYP / DCLTRCAT)", "",
            "| Db2 column | Db2 type | Host variable | PostgreSQL (Flyway V300) | Java |", "|---|---|---|---|---|",
            "| `TR_TYPE` | `CHAR(2)` PK | `DCL-TR-TYPE PIC X(2)` | `tr_type CHAR(2)` | `String` |",
            "| `TR_DESCRIPTION` | `VARCHAR(50)` | `DCL-TR-DESCRIPTION` (49 LEN COMP + TEXT X(50)) | `tr_description VARCHAR(50)` (stored space-padded, as ocesql sends it) | `String` |",
            "| `TRC_TYPE_CODE` | `CHAR(2)` PK, FK -> TR_TYPE | `DCL-TRC-TYPE-CODE` | `trc_type_code CHAR(2)` | `String` |",
            "| `TRC_TYPE_CATEGORY` | `CHAR(4)` PK | `DCL-TRC-TYPE-CATEGORY` | `trc_type_category CHAR(4)` | `String` |",
            "| `TRC_CAT_DATA` | `VARCHAR(50)` | `DCL-TRC-CAT-DATA` | `trc_cat_data VARCHAR(50)` | `String` |", "",
            "## DATEPARM (80 bytes, TRANREPT)", "",
            "Used by: posting (CBTRN03C).  `WS-START-DATE X(10)`, filler `X(1)`, `WS-END-DATE X(10)`: report date range, compared as text with `TRAN-PROC-TS(1:10)`.", "",
            "## CBFEECAL request area FEE_REQ / FEE-REQ (PL/I, analysis-only)", "",
            "Used by: online slice (Java port `FeeCalculator`).  `app/pli/CBFEECAL.pli:21-34`, COBOL mirror `app/cbl/CBFEEDRV.cbl:54-62`.", "",
            "| PL/I | COBOL | Meaning | Java |", "|---|---|---|---|",
            "| `FEE_ACCT_ID CHAR(11)` | `FEE-ACCT-ID PIC X(11)` | Account id | `String` |",
            "| `FEE_GROUP_ID CHAR(10)` | `FEE-GROUP-ID PIC X(10)` | Group (GOLD waiver) | `String` |",
            "| `FEE_CYCLE_BAL DECIMAL FIXED(11,2)` | `PIC S9(9)V99 COMP-3` | Cycle balance (credit - debit) | `BigDecimal` scale 2 |",
            "| `FEE_LATE_DAYS FIXED BINARY(15)` | `PIC S9(4) COMP` | Days late (<= 0: no fee) | `short` |",
            "| `FEE_CYCLE_DAYS FIXED BINARY(15)` | `PIC S9(4) COMP` | Days in cycle (0: ZERODIVIDE, RC 8) | `short` |",
            "| `FEE_AMT DECIMAL FIXED(9,2)` | `PIC S9(7)V99 COMP-3` | Late fee: 2.5 %, truncated, floor 5.00, cap 35.00 | `BigDecimal` scale 2, `RoundingMode.DOWN` |",
            "| `FEE_AVG_DAILY DECIMAL FIXED(12,2)` | `PIC S9(10)V99 COMP-3` | Average daily balance, truncated | `BigDecimal` scale 2, `RoundingMode.DOWN` |",
            "| `FEE_RC FIXED BINARY(15)` | `PIC S9(4) COMP` | 0 ok, 4 waived, 8 zero-divide, 12 size/error | `short` |", "",
            "## HLASM parameter areas (analysis-only)", "",
            "| Routine | Parameter | Layout | Java |", "|---|---|---|---|",
            "| COBDATFT | `CODATECN-REC` (`app/cpy/CODATECN.cpy`) | `CODATECN-TYPE X(1)` in format (1 = YYYYMMDD, 2 = YYYY-MM-DD), `CODATECN-INP-DATE X(20)`, `CODATECN-OUTTYPE X(1)`, `CODATECN-0UT-DATE X(20)`, `CODATECN-ERROR-MSG X(38)` | `DateFormatRoutine.convert(...)` record |",
            "| MVSWAIT | `MVSWAIT-TIME` | `PIC 9(8) COMP` fullword, hundredths of a second | `long` centiseconds; no-op in batch tests |", ""]
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text("\n".join(out) + "\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
