#!/usr/bin/env python3
"""Create codec parity vectors from every fixed-record fixture with a copybook."""
from __future__ import annotations

import json
import sys
from decimal import Decimal
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from tools.parity.copybook import decode_record, encode_record  # noqa: E402

INPUT_LAYOUTS = {
    "AWS.M2.CARDDEMO.ACCTDATA.PS": "CVACT01Y",
    "AWS.M2.CARDDEMO.CARDXREF.PS": "CVACT03Y",
    "AWS.M2.CARDDEMO.TCATBALF.PS": "CVTRA01Y",
    "AWS.M2.CARDDEMO.DISCGRP.PS": "CVTRA02Y",
    "AWS.M2.CARDDEMO.TRANTYPE.PS": "CVTRA03Y",
    "AWS.M2.CARDDEMO.TRANCATG.PS": "CVTRA04Y",
    "AWS.M2.CARDDEMO.DALYTRAN.PS": "CVTRA06Y",
    "AWS.M2.CARDDEMO.DALYTRAN.PS.INIT": "CVTRA05Y",
}
CHAINS = json.loads((ROOT / "tools/runjcl/chains.json").read_text())
GOLDEN = ROOT / "java/common/src/test/resources/codec-golden"


def encode_value(value: object) -> str:
    return format(value, "f") if isinstance(value, Decimal) else value


def vector(layout: str, record: bytes, location: str, index: int) -> dict:
    values = decode_record(layout, record)
    return {
        "location": location,
        "recordIndex": index,
        "layout": layout,
        "sourceHex": record.hex(),
        "pythonEncodedHex": encode_record(layout, values).hex(),
        "values": {key: encode_value(value) for key, value in values.items()},
        "decimalFields": [key for key, value in values.items() if isinstance(value, Decimal)],
    }


def fixed_records(path: Path, lrecl: int) -> list[bytes]:
    if not path.exists():
        return []
    lines = path.read_text(encoding="latin-1").splitlines()
    return [line.ljust(lrecl)[:lrecl].encode("latin-1") for line in lines]


def main() -> None:
    GOLDEN.mkdir(parents=True, exist_ok=True)
    for target in GOLDEN.glob("*.json"):
        target.unlink()
    total = 0
    for chain_name, chain in sorted(CHAINS.items()):
        for case_dir in sorted(path for path in (ROOT / "fixtures" / chain_name).iterdir() if path.is_dir()):
            records = []
            for dsn, lrecl in sorted(chain["inputs"].items()):
                layout = INPUT_LAYOUTS.get(dsn)
                if layout is None:
                    continue
                source = case_dir / "input" / f"{dsn}.txt"
                for index, record in enumerate(fixed_records(source, lrecl), 1):
                    records.append(vector(layout, record, f"input/{dsn}", index))
            expected = case_dir / "expected"
            manifest_path = expected / "manifest.json"
            if manifest_path.exists():
                manifest = json.loads(manifest_path.read_text())
                for dsn, metadata in sorted(manifest.items()):
                    layout = metadata["layout"]
                    if layout == "text":
                        continue
                    source = expected / "datasets" / f"{dsn}.txt"
                    for index, record in enumerate(fixed_records(source, metadata["lrecl"]), 1):
                        records.append(vector(layout, record, f"expected/{dsn}", index))
            if records:
                destination = GOLDEN / f"{chain_name}-{case_dir.name}.json"
                destination.write_text(json.dumps(records, indent=2, sort_keys=True) + "\n", encoding="utf-8")
                total += len(records)
    print(f"generated {total} copybook records in {GOLDEN}")


if __name__ == "__main__":
    main()
