#!/usr/bin/env python3
"""Generate carddemo's VSAM relational tables from the source copybooks."""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from tools.parity.copybook import parse_copybook

DATASETS = [
    ("AWS.M2.CARDDEMO.ACCTDATA.PS", "account", "CVACT01Y", ["acct_id"]),
    ("AWS.M2.CARDDEMO.CARDXREF.PS", "card_xref", "CVACT03Y", ["xref_card_num"]),
    ("AWS.M2.CARDDEMO.TCATBALF.PS", "tran_cat_balance", "CVTRA01Y",
     ["trancat_acct_id", "trancat_type_cd", "trancat_cd"]),
    ("AWS.M2.CARDDEMO.DISCGRP.PS", "disclosure_group", "CVTRA02Y",
     ["dis_acct_group_id", "dis_tran_type_cd", "dis_tran_cat_cd"]),
    ("AWS.M2.CARDDEMO.TRANTYPE.PS", "tran_type", "CVTRA03Y", ["tran_type"]),
    ("AWS.M2.CARDDEMO.TRANCATG.PS", "tran_category", "CVTRA04Y", ["tran_type_cd", "tran_cat_cd"]),
    ("AWS.M2.CARDDEMO.DALYTRAN.PS", "daily_transaction", "CVTRA06Y", ["dalytran_id"]),
    ("AWS.M2.CARDDEMO.DALYTRAN.PS.INIT", "transaction", "CVTRA05Y", ["tran_id"]),
]


def generate() -> str:
    statements = ["CREATE SCHEMA IF NOT EXISTS carddemo;", ""]
    for _, table, copybook, keys in DATASETS:
        fields = [field for field in parse_copybook(copybook) if not field.is_filler]
        columns = []
        for field in fields:
            name = field.name.lower().replace("-", "_")
            if field.kind == "text":
                data_type = f"varchar({field.length})"
            elif field.kind in ("display", "comp-3"):
                data_type = f"numeric({field.digits},{field.scale})"
            else:
                data_type = "bigint"
            columns.append(f"    {name} {data_type} NOT NULL")
        columns.append(f"    PRIMARY KEY ({', '.join(keys)})")
        identifier = f'"{table}"' if table == "transaction" else table
        statements.append(f"CREATE TABLE carddemo.{identifier} (\n" + ",\n".join(columns) + "\n);")
        statements.append("")
    return "\n".join(statements).rstrip() + "\n"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", type=Path, default=ROOT / "java/common/src/main/resources/db/migration/common/V10__vsam_tables.sql")
    args = parser.parse_args()
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(generate(), encoding="utf-8")


if __name__ == "__main__":
    main()
