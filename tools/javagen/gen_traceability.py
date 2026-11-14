#!/usr/bin/env python3
"""Rule -> COBOL -> Java -> test traceability, written to docs/analysis/traceability.json.

* tests:   JUnit methods whose name starts with BR_<SLICE>_NNN (java/*/src/test/**/*.java),
           Playwright tests whose title mentions a BR id (ui/e2e/*.spec.ts);
* java:    main sources that mention the BR id (Javadoc @see, comments), java/*/src/main/**;
* results: surefire XML (java/*/target/surefire-reports/TEST-*.xml) -> pass/fail per test;
* regression: per fixture case cited by the rule, the Java parity verdict from
           work/parity/report-java.md (only when that report exists, i.e. parity-java ran).

    python3 tools/javagen/gen_traceability.py [--rules-dir DIR]
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import re
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SLICES = ["posting", "interest", "trantype", "online"]
TEST_RE = re.compile(r"void\s+(BR_([A-Z]+)_(\d{3})\w*)\s*\(")
ID_RE = re.compile(r"BR[-_](POST|INT|TT|ONL)[-_](\d{3})")


def surefire_results() -> dict[str, str]:
    res: dict[str, str] = {}
    for xml in ROOT.glob("java/*/target/surefire-reports/TEST-*.xml"):
        for tc in ET.parse(xml).getroot().iter("testcase"):
            name = tc.get("name", "").split("(")[0]
            status = "fail" if tc.find("failure") is not None or tc.find("error") is not None else (
                "skip" if tc.find("skipped") is not None else "pass")
            res[f"{tc.get('classname')}#{name}"] = status
    return res


def parity_verdicts() -> dict[str, str]:
    rep = ROOT / "work/parity/report-java.md"
    out: dict[str, str] = {}
    if rep.exists():
        for m in re.finditer(r"^## (\S+) / (\S+) — java: \*\*(PASS|FAIL)", rep.read_text(), re.M):
            out[f"fixtures/{m.group(1)}/{m.group(2)}"] = m.group(3)
    return out


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--rules-dir", type=Path, default=ROOT / "docs/analysis/business-rules")
    ap.add_argument("--out", type=Path, default=ROOT / "docs/analysis/traceability.json")
    args = ap.parse_args()
    rules = []
    for s in SLICES:
        p = args.rules_dir / f"{s}.json"
        if p.exists():
            rules += json.loads(p.read_text())
    trace = {r["id"]: {"java": [], "tests": [], "results": {}} for r in rules}
    results = surefire_results()

    for f in sorted(ROOT.glob("java/*/src/test/java/**/*.java")):
        text = f.read_text(encoding="utf-8", errors="replace")
        pkg = re.search(r"^package\s+([\w.]+);", text, re.M)
        cls = (pkg.group(1) + "." if pkg else "") + f.stem
        for m in TEST_RE.finditer(text):
            rid = f"BR-{m.group(2)}-{m.group(3)}"
            if rid in trace:
                tid = f"{f.stem}#{m.group(1)}"
                if tid not in trace[rid]["tests"]:
                    trace[rid]["tests"].append(tid)
                if f"{cls}#{m.group(1)}" in results:
                    trace[rid]["results"][tid] = results[f"{cls}#{m.group(1)}"]
    for f in sorted(ROOT.glob("ui/e2e/*.spec.ts")):
        text = f.read_text(encoding="utf-8", errors="replace")
        for m in re.finditer(r"test\(\s*['\"`]([^'\"`]+)['\"`]", text):
            for idm in ID_RE.finditer(m.group(1)):
                rid = f"BR-{idm.group(1)}-{idm.group(2)}"
                if rid in trace:
                    trace[rid]["tests"].append(f"{f.name}: {m.group(1)}")
    for f in sorted(ROOT.glob("java/*/src/main/java/**/*.java")):
        text = f.read_text(encoding="utf-8", errors="replace")
        rel = str(f.relative_to(ROOT))
        for idm in set(ID_RE.findall(text)):
            rid = f"BR-{idm[0]}-{idm[1]}"
            if rid in trace and rel not in trace[rid]["java"]:
                trace[rid]["java"].append(rel)

    parity = parity_verdicts()
    for r in rules:
        t = trace[r["id"]]
        vals = list(t["results"].values())
        t["status"] = "fail" if "fail" in vals else ("pass" if vals else ("traced" if t["tests"] else "none"))
        cases = [c.rstrip("/") for c in r.get("fixtures", [])]
        if parity and cases:
            v = [f"{c.split('/')[-1]} {parity.get(c, 'not run')}" for c in cases]
            t["regression"] = "Java vs legacy (compare.py): " + ", ".join(v)
    out = {"generated": dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
           "note": "Java files and test ids scanned from java/ and ui/e2e; results from the last test run.",
           "rules": trace}
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(out, indent=1, sort_keys=True))
    n = sum(1 for v in trace.values() if v["tests"])
    print(f"{args.out}: {len(rules)} rules, {n} with tests, {sum(1 for v in trace.values() if v['java'])} with Java refs")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
