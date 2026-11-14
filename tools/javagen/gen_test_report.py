#!/usr/bin/env python3
"""Build docs/test-report/index.html (self-contained) from the artefacts of the last test run:

* unit        java/*/target/surefire-reports/TEST-*.xml
* regression  work/parity/report.md (legacy rerun vs golden) and work/parity/report-java.md
              (Java vs golden, field by field through tools/parity/compare.py)
* naive       work/parity/report-naive.md (half-even reference on i02-tie-half-cent)
* functional  ui/test-results/e2e-results.json (Playwright JSON reporter)
* rules       docs/analysis/traceability.json (per BR id: tests and their results)

    python3 tools/javagen/gen_test_report.py [--rules-dir DIR] [--out FILE]
"""
from __future__ import annotations

import argparse
import datetime as dt
import html
import json
import re
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CHAIN_SLICE = {"posting": "posting", "interest": "interest", "trantype-update": "trantype", "trantype-dynamic": "trantype"}
MODULE_SLICE = {"common": "foundation", "app": "foundation", "posting": "posting", "interest": "interest",
                "trantype": "trantype", "online": "online"}
e = html.escape


def unit() -> dict:
    mods: dict[str, dict] = {}
    failures = []
    for xml in sorted(ROOT.glob("java/*/target/surefire-reports/TEST-*.xml")):
        mod = xml.parts[-4]
        m = mods.setdefault(mod, {"tests": 0, "pass": 0, "fail": 0, "skip": 0, "time": 0.0})
        for tc in ET.parse(xml).getroot().iter("testcase"):
            m["tests"] += 1
            m["time"] += float(tc.get("time") or 0)
            if tc.find("failure") is not None or tc.find("error") is not None:
                m["fail"] += 1
                f = tc.find("failure") if tc.find("failure") is not None else tc.find("error")
                failures.append({"module": mod, "test": f"{tc.get('classname')}#{tc.get('name')}",
                                 "message": (f.get("message") or "")[:300]})
            elif tc.find("skipped") is not None:
                m["skip"] += 1
            else:
                m["pass"] += 1
    return {"modules": mods, "failures": failures}


def parse_parity(path: Path) -> list[dict]:
    if not path.exists():
        return []
    cases = []
    for block in re.split(r"^(?=## )", path.read_text(), flags=re.M):
        m = re.match(r"## (\S+) / (\S+) — (.+?): \*\*(PASS|FAIL)[^*]*\*\*", block)
        if not m:
            continue
        checked = re.search(r"^Checked .*$", block, re.M)
        rows = [r for r in block.splitlines() if r.startswith("| ") and not r.startswith("| Where") and not r.startswith("|---")]
        cases.append({"chain": m.group(1), "case": m.group(2), "label": m.group(3), "verdict": m.group(4),
                      "checked": checked.group(0) if checked else "", "rows": rows})
    return cases


def functional() -> dict:
    p = ROOT / "ui/test-results/e2e-results.json"
    if not p.exists():
        return {"tests": [], "stats": {}}
    data = json.loads(p.read_text())
    tests = []

    def walk(suite, file=None):
        file = suite.get("file") or file
        for spec in suite.get("specs", []):
            for t in spec.get("tests", []):
                res = t.get("results", [])
                status = res[-1]["status"] if res else "skipped"
                tests.append({"file": file, "title": spec["title"], "status": status,
                              "ms": sum(r.get("duration", 0) for r in res)})
        for s in suite.get("suites", []):
            walk(s, file)

    for s in data.get("suites", []):
        walk(s)
    return {"tests": tests, "stats": data.get("stats", {})}


def md_table_rows(rows: list[str]) -> str:
    out = []
    for r in rows:
        cells = [c.strip() for c in re.split(r"(?<!\\)\|", r)[1:-1]]
        out.append("<tr>" + "".join(f"<td>{e(c).replace('&lt;br&gt;', '<br>').replace('`', '')}</td>" for c in cells) + "</tr>")
    return "".join(out)


def badge(ok: bool | None, yes="PASS", no="FAIL") -> str:
    if ok is None:
        return '<span class="badge muted">not run</span>'
    return f'<span class="badge {"ok" if ok else "bad"}">{yes if ok else no}</span>'


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--rules-dir", type=Path, default=ROOT / "docs/analysis/business-rules")
    ap.add_argument("--out", type=Path, default=ROOT / "docs/test-report/index.html")
    args = ap.parse_args()

    u = unit()
    legacy = parse_parity(ROOT / "work/parity/report.md")
    java = parse_parity(ROOT / "work/parity/report-java.md")
    naive = parse_parity(ROOT / "work/parity/report-naive.md")
    func = functional()
    trace = json.loads((ROOT / "docs/analysis/traceability.json").read_text()).get("rules", {}) if (ROOT / "docs/analysis/traceability.json").exists() else {}
    rules = []
    for s in ["posting", "interest", "trantype", "online"]:
        p = args.rules_dir / f"{s}.json"
        if p.exists():
            rules += json.loads(p.read_text())

    ut = sum(m["tests"] for m in u["modules"].values())
    up = sum(m["pass"] for m in u["modules"].values())
    uf = sum(m["fail"] for m in u["modules"].values())
    jp = sum(1 for c in java if c["verdict"] == "PASS")
    lp = sum(1 for c in legacy if c["verdict"] == "PASS")
    ft = len(func["tests"])
    fp = sum(1 for t in func["tests"] if t["status"] in ("passed", "expected"))
    total_cases = 24

    # per slice summary
    slices = ["posting", "interest", "trantype", "online"]
    slice_rows = []
    for s in slices:
        mods = [m for k, m in u["modules"].items() if MODULE_SLICE.get(k) == s]
        st, sp = sum(m["tests"] for m in mods), sum(m["pass"] for m in mods)
        jc = [c for c in java if CHAIN_SLICE.get(c["chain"]) == s]
        lc = [c for c in legacy if CHAIN_SLICE.get(c["chain"]) == s]
        fc = [t for t in func["tests"] if (t["file"] or "").startswith(s) or t["title"].startswith(f"[{s}]")]
        sr = [r for r in rules if r["slice"] == s]
        traced = sum(1 for r in sr if trace.get(r["id"], {}).get("tests"))
        rpass = sum(1 for r in sr if trace.get(r["id"], {}).get("status") == "pass")
        reg = "n/a (CICS, HLASM, PL/I cannot run off-host)" if s == "online" else (
            f"{sum(1 for c in jc if c['verdict'] == 'PASS')}/{len(jc)} Java, {sum(1 for c in lc if c['verdict'] == 'PASS')}/{len(lc)} legacy" if jc or lc else "not run")
        slice_rows.append(f"<tr><td><b>{s}</b></td><td>{sp}/{st}</td><td>{reg}</td>"
                          f"<td>{sum(1 for t in fc if t['status'] in ('passed', 'expected'))}/{len(fc)}</td>"
                          f"<td>{rpass}/{len(sr)} pass · {traced} traced</td></tr>")

    reg_rows = []
    lmap = {(c["chain"], c["case"]): c for c in legacy}
    for c in java:
        lc = lmap.get((c["chain"], c["case"]))
        diff = f'<table class="inner"><tr><th>Where</th><th>Field</th><th>Expected (legacy)</th><th>Actual (Java)</th></tr>{md_table_rows(c["rows"])}</table>' if c["rows"] else '<span class="muted small">0 differences</span>'
        reg_rows.append(f"<tr><td class='mono'>{e(c['chain'])}</td><td class='mono'>{e(c['case'])}</td><td>{badge(lc['verdict'] == 'PASS' if lc else None)}</td>"
                        f"<td>{badge(c['verdict'] == 'PASS')}</td><td class='small'>{e(c['checked'])}</td><td>{diff}</td></tr>")
    for (ch, cs), lc in lmap.items():
        if not any(c["chain"] == ch and c["case"] == cs for c in java):
            reg_rows.append(f"<tr><td class='mono'>{e(ch)}</td><td class='mono'>{e(cs)}</td><td>{badge(lc['verdict'] == 'PASS')}</td><td>{badge(None)}</td><td class='small'>{e(lc['checked'])}</td><td></td></tr>")

    naive_html = "<p class='muted'>not run</p>"
    if naive:
        n = naive[0]
        jn = next((c for c in java if c["chain"] == "interest" and c["case"] == n["case"]), None)
        naive_html = (f"<p>Same fixture <span class='mono'>interest/{e(n['case'])}</span>, same comparator. "
                      f"Plausible half-even port: {badge(n['verdict'] == 'PASS')} · Java port (RoundingMode.DOWN): {badge(jn['verdict'] == 'PASS' if jn else None)}</p>"
                      + (f"<table class='inner'><tr><th>Where</th><th>Field</th><th>Expected (legacy)</th><th>Actual (half-even)</th></tr>{md_table_rows(n['rows'])}</table>" if n["rows"] else ""))

    rule_rows = []
    for r in rules:
        t = trace.get(r["id"], {})
        res = t.get("results", {})
        st = t.get("status", "none")
        cls = {"pass": "ok", "fail": "bad"}.get(st, "muted")
        rule_rows.append(f"<tr data-s='{r['slice']}'><td class='mono'>{r['id']}</td><td>{e(r['title'])}</td><td><span class='badge {cls}'>{st}</span></td>"
                         f"<td class='mono small'>{'<br>'.join(e(x) + (' ✓' if res.get(x) == 'pass' else ' ✗' if res.get(x) == 'fail' else '') for x in t.get('tests', [])[:6])}</td>"
                         f"<td class='small'>{e(t.get('regression', ''))}</td></tr>")

    unit_rows = "".join(f"<tr><td class='mono'>{e(k)}</td><td>{MODULE_SLICE.get(k, k)}</td><td>{m['tests']}</td><td class='ok'>{m['pass']}</td><td class='{'bad' if m['fail'] else ''}'>{m['fail']}</td><td>{m['skip']}</td><td>{m['time']:.1f}s</td></tr>" for k, m in sorted(u["modules"].items()))
    unit_fail = "".join(f"<li class='mono small'>{e(f['test'])}: {e(f['message'])}</li>" for f in u["failures"]) or "<li class='muted'>none</li>"
    func_rows = "".join(f"<tr><td class='mono small'>{e(t['file'] or '')}</td><td>{e(t['title'])}</td><td>{badge(t['status'] in ('passed', 'expected'))}</td><td>{t['ms'] / 1000:.1f}s</td></tr>" for t in func["tests"])

    now = dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%d %H:%M UTC")
    page = f"""<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Conversion Test Report</title><style>
:root{{--bg:#f5f6f8;--panel:#fff;--ink:#1b2230;--muted:#667085;--line:#dfe3ea;--accent:#1f5fbf;--ok:#18794e;--bad:#b42318}}
@media (prefers-color-scheme:dark){{:root:not([data-theme="light"]){{--bg:#0d1218;--panel:#151c26;--ink:#e4e9f1;--muted:#93a0b4;--line:#263041;--accent:#5b9bff;--ok:#4cc38a;--bad:#ff7a6e}}}}
:root[data-theme="dark"]{{--bg:#0d1218;--panel:#151c26;--ink:#e4e9f1;--muted:#93a0b4;--line:#263041;--accent:#5b9bff;--ok:#4cc38a;--bad:#ff7a6e}}
*{{box-sizing:border-box}}body{{margin:0;background:var(--bg);color:var(--ink);font:14px/1.45 Inter,system-ui,sans-serif}}
header,main{{padding:20px 28px}}main{{padding-top:0;display:flex;flex-direction:column;gap:16px}}h1{{margin:0 0 4px;font-size:22px}}h2{{font-size:16px;margin:0 0 10px}}
.card{{background:var(--panel);border:1px solid var(--line);border-radius:10px;padding:16px;overflow-x:auto}}
.kpis{{display:grid;grid-template-columns:repeat(auto-fit,minmax(200px,1fr));gap:12px}}.kpi{{background:var(--panel);border:1px solid var(--line);border-radius:10px;padding:14px}}
.kpi b{{font-size:26px;display:block}}.muted{{color:var(--muted)}}.small{{font-size:12px}}.mono{{font-family:ui-monospace,Menlo,monospace}}
table{{border-collapse:collapse;width:100%}}th,td{{text-align:left;padding:6px 8px;border-bottom:1px solid var(--line);vertical-align:top}}
th{{font-size:11px;text-transform:uppercase;letter-spacing:.04em;color:var(--muted)}}table.inner{{font-size:12px;margin:4px 0}}table.inner td,table.inner th{{padding:3px 6px}}
.badge{{font-size:11px;padding:1px 8px;border-radius:999px;border:1px solid currentColor;white-space:nowrap}}.ok{{color:var(--ok)}}.bad{{color:var(--bad)}}
input{{font:inherit;padding:6px 10px;border:1px solid var(--line);border-radius:8px;background:var(--panel);color:var(--ink);min-width:260px}}
</style></head><body>
<header><h1>CardDemo COBOL → Java 21: test report</h1><div class="muted">Generated {now}. Three layers: unit (JUnit), regression (legacy COBOL and Java run on the same fixture input, compared field by field by <span class="mono">tools/parity/compare.py</span>), functional (Playwright on the operations console). CICS, HLASM and PL/I are analysis-only and were not executed.</div></header>
<main>
<section class="kpis">
<div class="kpi">Unit tests<b class="{'ok' if ut and not uf else 'bad'}">{up}/{ut}</b><span class="small muted">{uf} failing</span></div>
<div class="kpi">Regression, Java vs legacy<b class="{'ok' if jp == total_cases else 'bad'}">{jp}/{total_cases}</b><span class="small muted">cases with 0 field differences</span></div>
<div class="kpi">Legacy rerun vs golden<b class="{'ok' if lp == total_cases else 'bad'}">{lp}/{total_cases}</b><span class="small muted">the oracle itself</span></div>
<div class="kpi">Functional (e2e)<b class="{'ok' if ft and fp == ft else 'bad'}">{fp}/{ft}</b><span class="small muted">Playwright journeys</span></div>
<div class="kpi">Business rules with passing tests<b>{sum(1 for r in rules if trace.get(r['id'], {}).get('status') == 'pass')}/{len(rules)}</b><span class="small muted">BR ids traced to JUnit</span></div>
</section>
<section class="card"><h2>Per slice</h2><table><tr><th>Slice</th><th>Unit</th><th>Regression</th><th>Functional</th><th>Business rules</th></tr>{''.join(slice_rows)}</table></section>
<section class="card"><h2>Rounding proof: a half-even port fails where the Java port passes</h2>{naive_html}</section>
<section class="card"><h2>Regression: COBOL vs Java, per case</h2><p class="small muted">Both runs use the same fixture input. A case passes only with 0 differences across decoded dataset fields (COMP-3, zoned overpunch), SYSOUT lines, return codes and table snapshots.</p>
<table><tr><th>Chain</th><th>Case</th><th>Legacy vs golden</th><th>Java vs golden</th><th>Checked</th><th>Field differences</th></tr>{''.join(reg_rows) or '<tr><td colspan=6 class=muted>not run</td></tr>'}</table></section>
<section class="card"><h2>Unit tests (JUnit) per module</h2><table><tr><th>Module</th><th>Slice</th><th>Tests</th><th>Pass</th><th>Fail</th><th>Skipped</th><th>Time</th></tr>{unit_rows}</table><h2 style="margin-top:12px">Failures</h2><ul>{unit_fail}</ul></section>
<section class="card"><h2>Functional tests (Playwright)</h2><table><tr><th>Spec</th><th>Journey</th><th>Result</th><th>Time</th></tr>{func_rows or '<tr><td colspan=4 class=muted>not run</td></tr>'}</table></section>
<section class="card"><h2>Per business rule</h2><input id="q" placeholder="Filter by id, title or slice"><table id="rules"><tr><th>Rule</th><th>Title</th><th>Status</th><th>Tests</th><th>Regression evidence</th></tr>{''.join(rule_rows)}</table></section>
</main><script>document.getElementById('q').oninput=ev=>{{const q=ev.target.value.toLowerCase();document.querySelectorAll('#rules tr[data-s]').forEach(r=>r.style.display=(r.textContent+r.dataset.s).toLowerCase().includes(q)?'':'none')}}</script></body></html>"""
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(page)
    print(f"{args.out}: unit {up}/{ut}, regression java {jp}/{total_cases} legacy {lp}/{total_cases}, functional {fp}/{ft}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
