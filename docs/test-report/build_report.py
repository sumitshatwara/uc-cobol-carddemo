#!/usr/bin/env python3
"""Build docs/test-report/index.html (self-contained) from the verification artifacts.

Usage: python3 docs/test-report/build_report.py [work/verify]
Inputs (all produced by the verify run): legacy.log, parity-java.log, naive.log (+ naive.rc),
surefire/*.xml, playwright.json.  Every number shown is parsed from those files.
"""
import html, json, re, sys, xml.etree.ElementTree as ET
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SRC = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "work" / "verify"
OUT = Path(__file__).resolve().parent / "index.html"
CASE_RE = re.compile(r"^(\S+?)/(\S+) \[([^\]]+)\]: (PASS|FAIL) \((\d+) records, (\d+) fields, (\d+) differences\)", re.M)
SLICE_OF = {"posting": "posting", "interest": "interest", "trantype-update": "trantype", "trantype-dynamic": "trantype"}


def esc(s):
    return html.escape(str(s))


def parity(log):
    rows = []
    if (SRC / log).exists():
        for m in CASE_RE.finditer((SRC / log).read_text(errors="replace")):
            rows.append(dict(chain=m[1], case=m[2], label=m[3], status=m[4], records=int(m[5]),
                             fields=int(m[6]), diffs=int(m[7])))
    return rows


def surefire():
    cases = []
    for f in sorted((SRC / "surefire").rglob("*.xml")):
        root = ET.parse(f).getroot()
        for tc in root.iter("testcase"):
            cls = tc.get("classname", "")
            status = "passed"
            for tag in ("failure", "error"):
                if tc.find(tag) is not None:
                    status = "failed"
            if tc.find("skipped") is not None:
                status = "skipped"
            m = re.match(r"com\.carddemo\.(\w+)", cls)
            name = tc.get("name", "")
            rule = re.search(r"BR-[A-Z]+-\d{3}", name) or re.search(r"br(Post|Int|Trt|Onl)(\d{3})", name)
            rid = rule[0] if rule and rule[0].startswith("BR-") else (
                f"BR-{rule[1].upper()[:4] if rule[1] != 'Post' else 'POST'}-{rule[2]}" if rule else "")
            cases.append(dict(module=m[1] if m else "?", cls=cls.split(".")[-1], name=name,
                              status=status, time=float(tc.get("time") or 0), rule=rid))
    return cases


def playwright():
    p = SRC / "playwright.json"
    if not p.exists():
        return [], {}
    doc = json.loads(p.read_text())
    out = []

    def walk(suite, file):
        file = suite.get("file") or file
        for spec in suite.get("specs", []):
            for t in spec.get("tests", []):
                res = t.get("results", [])
                last = res[-1]["status"] if res else "skipped"
                out.append(dict(file=file, title=spec["title"], status=last, retries=max(0, len(res) - 1),
                                ms=sum(r.get("duration", 0) for r in res)))
        for s in suite.get("suites", []):
            walk(s, file)
    for s in doc.get("suites", []):
        walk(s, s.get("file", ""))
    return out, doc.get("stats", {})


def count(rows, key="status", ok=("PASS", "passed")):
    p = sum(r[key] in ok for r in rows)
    return p, len(rows)


def main():
    legacy, java, naive = parity("legacy.log"), parity("parity-java.log"), parity("naive.log")
    unit = surefire()
    pw, pwstats = playwright()
    nlog = (SRC / "naive.log").read_text(errors="replace") if (SRC / "naive.log").exists() else ""
    m = re.search(r"exit_code=(\d+)", nlog)
    naive_rc = m[1] if m else "?"
    nrep = ROOT / "work" / "parity" / "report-naive.md"
    naive_lines = [l for l in nlog.splitlines() if "FAIL" in l]
    if nrep.exists():
        naive_lines += [l for l in nrep.read_text().splitlines() if l.startswith("|") and "---" not in l]

    lp, lt = count(legacy)
    jp, jt = count(java)
    up, ut = count(unit)
    usk = sum(c["status"] == "skipped" for c in unit)
    fp, ft = count(pw, ok=("passed",))
    nf = sum(r["status"] == "FAIL" for r in naive)

    by_mod = defaultdict(lambda: [0, 0, 0, 0])
    for c in unit:
        b = by_mod[c["module"]]
        b[0] += c["status"] == "passed"; b[1] += c["status"] == "failed"; b[2] += c["status"] == "skipped"; b[3] += 1
    rules_tested = sorted({c["rule"] for c in unit if c["rule"]})

    def card(title, val, sub, good):
        return f'<div class="card {"ok" if good else "bad"}"><div class="t">{esc(title)}</div><div class="v">{esc(val)}</div><div class="s">{esc(sub)}</div></div>'

    cards = "".join([
        card("Legacy regression (make parity)", f"{lp}/{lt}", "COBOL/JCL rerun vs golden", lt > 0 and lp == lt),
        card("Unit (make test-unit)", f"{up}/{ut}", f"{usk} skipped · {len(rules_tested)} rule IDs tested", ut > 0 and up + usk == ut),
        card("Java parity (make parity-java)", f"{jp}/{jt}", "Java run vs the same golden files", jt > 0 and jp == jt),
        card("Functional (make test-functional)", f"{fp}/{ft}", "Playwright, operations console", ft > 0 and fp == ft),
        card("Naive half-even (expected FAIL)", "FAIL" if nf else "did not fail", f"i02-tie-half-cent · exit {naive_rc}", nf > 0),
    ])

    def parity_table(rows, title):
        tr = "".join(f'<tr><td>{esc(r["chain"])}</td><td>{esc(r["case"])}</td><td class="{r["status"].lower()}">{r["status"]}</td>'
                     f'<td>{r["records"]}</td><td>{r["fields"]}</td><td>{r["diffs"]}</td></tr>' for r in rows)
        return f'<h2>{esc(title)}</h2><table><tr><th>Chain</th><th>Case</th><th>Verdict</th><th>Records</th><th>Fields</th><th>Differences</th></tr>{tr}</table>'

    side = "".join(
        f'<tr><td>{esc(c)}/{esc(k)}</td><td class="{l["status"].lower()}">{l["status"]}</td><td class="{j["status"].lower() if j else ""}">{j["status"] if j else "—"}</td><td>{(str(j["records"]) + " / " + str(j["fields"])) if j else ""}</td></tr>'
        for l in legacy for c, k in [(l["chain"], l["case"])]
        for j in [next((x for x in java if x["chain"] == c and x["case"] == k), None)])
    mod_rows = "".join(f"<tr><td>{esc(m)}</td><td>{b[0]}</td><td>{b[1]}</td><td>{b[2]}</td><td>{b[3]}</td></tr>" for m, b in sorted(by_mod.items()))
    unit_rows = "".join(f'<tr data-m="{esc(c["module"])}"><td>{esc(c["module"])}</td><td>{esc(c["rule"])}</td><td>{esc(c["cls"])}</td><td>{esc(c["name"])}</td><td class="{c["status"]}">{c["status"]}</td></tr>' for c in unit)
    pw_rows = "".join(f'<tr><td>{esc(t["file"])}</td><td>{esc(t["title"])}</td><td class="{t["status"]}">{esc(t["status"])}</td><td>{t["retries"]}</td><td>{t["ms"]/1000:.1f}s</td></tr>' for t in pw)
    naive_html = "<br>".join(esc(l) for l in naive_lines[:20]) or "(no output captured)"

    page = f"""<!doctype html><html lang="en"><head><meta charset="utf-8"><title>CardDemo Java 21 — verification report</title>
<style>
body{{background:#0d1117;color:#c9d1d9;font:14px/1.45 system-ui,Segoe UI,sans-serif;margin:0;padding:24px 32px}}
h1{{font-size:22px;margin:0 0 4px}} h2{{font-size:16px;margin:28px 0 8px;color:#e6edf3}} .muted{{color:#8b949e}}
.cards{{display:grid;grid-template-columns:repeat(5,1fr);gap:12px;margin-top:18px}}
.card{{background:#161b22;border:1px solid #30363d;border-left:4px solid #3fb950;border-radius:6px;padding:12px}} .card.bad{{border-left-color:#f85149}}
.card .t{{color:#8b949e;font-size:12px}} .card .v{{font-size:28px;font-weight:600;margin:4px 0}} .card .s{{color:#8b949e;font-size:12px}}
table{{border-collapse:collapse;width:100%;background:#161b22;border:1px solid #30363d}} th,td{{padding:5px 8px;border-bottom:1px solid #21262d;text-align:left;font-size:13px}}
th{{background:#1f2630;color:#8b949e;font-weight:600}} .pass,.passed{{color:#3fb950;font-weight:600}} .fail,.failed{{color:#f85149;font-weight:600}} .skipped{{color:#d29922}}
.grid2{{display:grid;grid-template-columns:1fr 1fr;gap:16px}} code,pre{{background:#161b22;border:1px solid #30363d;border-radius:4px;padding:8px;display:block;white-space:pre-wrap;font-size:12px}}
input{{background:#0d1117;color:#c9d1d9;border:1px solid #30363d;border-radius:4px;padding:5px 8px;width:280px}} a{{color:#58a6ff}}
.scroll{{max-height:420px;overflow:auto}}
</style></head><body>
<h1>CardDemo COBOL → Java 21 — verification report</h1>
<div class="muted">Every number is parsed from the verify-branch run logs (work/verify). Field-level comparison uses the unchanged <code style="display:inline;padding:1px 4px">tools/parity/compare.py</code> against the golden fixtures. CICS, HLASM and PL/I are not executable off-host: the online/system slice is verified by unit and browser tests only. <a href="../analysis/business-rule-explorer.html">Business Rule Explorer →</a></div>
<div class="cards">{cards}</div>
<h2>Legacy vs Java, per golden case</h2><table><tr><th>Case</th><th>Legacy rerun</th><th>Java</th><th>Decoded records / fields (Java; SYSOUT, DB snapshots and RCs are compared too)</th></tr>{side}</table>
<div class="grid2"><div><h2>Unit tests per module</h2><table><tr><th>Module</th><th>Passed</th><th>Failed</th><th>Skipped</th><th>Total</th></tr>{mod_rows}</table></div>
<div><h2>Why truncation matters: naive half-even reference (expected to FAIL)</h2><pre>{naive_html}</pre></div></div>
<h2>Functional tests (Playwright)</h2><table><tr><th>Spec</th><th>Test</th><th>Status</th><th>Retries</th><th>Duration</th></tr>{pw_rows}</table>
<h2>Unit test cases</h2><input id="q" placeholder="Filter by rule ID, class or test name"><div class="scroll"><table id="ut"><tr><th>Module</th><th>Rule</th><th>Class</th><th>Test</th><th>Status</th></tr>{unit_rows}</table></div>
{parity_table(java, "Java parity detail")}
<script>document.getElementById('q').oninput=e=>{{const v=e.target.value.toLowerCase();document.querySelectorAll('#ut tr[data-m]').forEach(r=>r.style.display=r.textContent.toLowerCase().includes(v)?'':'none')}}</script>
</body></html>"""
    OUT.write_text(page)
    summary = dict(legacy=[lp, lt], unit=[up, ut, usk], parityJava=[jp, jt], functional=[fp, ft],
                   naiveFailed=bool(nf), naiveExit=naive_rc, rulesTested=len(rules_tested), modules=by_mod)
    (OUT.parent / "summary.json").write_text(json.dumps(summary, indent=1, sort_keys=True) + "\n")
    print(json.dumps(summary, sort_keys=True))


if __name__ == "__main__":
    main()
