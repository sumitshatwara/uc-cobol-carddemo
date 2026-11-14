#!/usr/bin/env python3
"""Build docs/analysis/business-rule-explorer.html: one self-contained page (no network at
runtime) with

* the job -> step -> program -> dataset/table graph, from tools/runjcl/chains.json and the JCL;
* a searchable rule table (docs/analysis/business-rules/*.json), filterable by slice/category;
* click-through from each rule to its legacy source snippet(s) (embedded);
* once the Java slices exist, the rule -> COBOL -> Java -> test traceability columns, read from
  docs/analysis/traceability.json (written by tools/javagen/gen_traceability.py).

    python3 tools/javagen/gen_rule_explorer.py [--rules-dir DIR] [--out FILE]
"""
from __future__ import annotations

import argparse
import html
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SLICES = ["posting", "interest", "trantype", "online"]
CHAIN_SLICE = {"posting": "posting", "interest": "interest", "trantype-update": "trantype", "trantype-dynamic": "trantype"}
IGNORE_DSN = re.compile(r"LOADLIB|\.PROCLIB|DBRMLIB|SYSTOOLS|DSN\.|CEE\.|SYS1\.|SORTLIB")

ONLINE_GRAPH = {  # analysis-only: CICS transactions, HLASM and PL/I routines (not executed)
    "jobs": [
        {"job": "CICS CC00 (sign-on)", "steps": [{"step": "COSGN00", "programs": ["COSGN00C"], "datasets": ["USRSEC (CSUSR01Y)", "map COSGN00"]}]},
        {"job": "CICS CM00 (menu)", "steps": [{"step": "COMEN01", "programs": ["COMEN01C"], "datasets": ["COMEN02Y options", "map COMEN01"]}]},
        {"job": "CICS CAVW (account view)", "steps": [{"step": "COACTVW", "programs": ["COACTVWC"], "datasets": ["CXACAIX (CVACT03Y)", "ACCTDAT (CVACT01Y)", "CUSTDAT (CVCUS01Y)", "map COACTVW"]}]},
        {"job": "CICS CAUP (account update)", "steps": [{"step": "COACTUP", "programs": ["COACTUPC"], "datasets": ["CXACAIX (CVACT03Y)", "ACCTDAT (CVACT01Y)", "CUSTDAT (CVCUS01Y)", "map COACTUP"]}]},
        {"job": "FEECALC (JCL, not runnable)", "steps": [{"step": "STEP05", "programs": ["CBFEEDRV", "CBFEECAL (PL/I)"], "datasets": ["AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS"]}]},
        {"job": "HLASM routines", "steps": [{"step": "CALL", "programs": ["COBDATFT (HLASM)", "MVSWAIT (HLASM)"], "datasets": ["CODATECN-REC", "MVSWAIT-TIME"]}]},
    ]
}


def find_jcl(job: str) -> Path | None:
    for p in (ROOT / "app").rglob("*"):
        if p.is_file() and p.suffix.lower() == ".jcl" and p.stem.upper() == job.upper():
            return p
    return None


def parse_jcl(job: str) -> dict:
    path = find_jcl(job)
    steps: list[dict] = []
    if not path:
        return {"job": job, "path": None, "steps": steps}
    cur = None
    for raw in path.read_text(encoding="latin-1").splitlines():
        line = raw[:72]
        if line.startswith("//*"):
            continue
        m = re.match(r"^//(\S+)\s+EXEC\s+(?:PGM=)?([A-Z0-9$#@]+)", line)
        if m:
            cur = {"step": m.group(1), "programs": [m.group(2)], "datasets": []}
            steps.append(cur)
            continue
        if cur is None:
            continue
        r = re.search(r"RUN\s+PROGRAM\(([A-Z0-9]+)\)", line)
        if r:
            cur["programs"].append(r.group(1))
        p = re.search(r"PARMS\('([A-Z0-9]+)'\)", line)
        if p:
            cur["programs"].append(p.group(1) + " (dynamic CALL)")
        d = re.search(r"DSN=([A-Z0-9.$#@()+-]+)", line)
        if d and not IGNORE_DSN.search(d.group(1)) and d.group(1) not in cur["datasets"]:
            cur["datasets"].append(d.group(1))
    return {"job": job, "path": str(path.relative_to(ROOT)), "steps": steps}


def build_graph() -> list[dict]:
    chains = json.loads((ROOT / "tools/runjcl/chains.json").read_text())
    out = []
    for name, c in chains.items():
        jobs = [parse_jcl(j) for j in c["jobs"]]
        tables = list(c.get("database", {}).get("tables", {}).keys())
        if tables:
            for j in jobs:
                for s in j["steps"]:
                    if len(s["programs"]) > 1:
                        s["datasets"] += [f"table {t}" for t in tables]
        out.append({"chain": name, "slice": CHAIN_SLICE.get(name, name), "description": c.get("description", ""),
                    "jobs": jobs, "cases": sorted(p.name for p in (ROOT / "fixtures" / name).iterdir() if p.is_dir())})
    out.append({"chain": "online (analysis-only)", "slice": "online",
                "description": "CICS sign-on, menu, account view/update; HLASM and PL/I routines. Not executable off-host.",
                "jobs": ONLINE_GRAPH["jobs"], "cases": []})
    return out


def snippets(rules: list[dict]) -> dict[str, dict]:
    cache: dict[str, list[str]] = {}
    out: dict[str, dict] = {}
    for r in rules:
        for s in r.get("sources", []):
            f = s.get("file", "")
            key = f"{f}:{s.get('start')}-{s.get('end')}"
            if key in out:
                continue
            if f not in cache:
                p = ROOT / f
                cache[f] = p.read_text(encoding="latin-1").splitlines() if p.is_file() else []
            lines = cache[f]
            start, end = int(s.get("start") or 1), int(s.get("end") or s.get("start") or 1)
            lo, hi = max(1, start - 3), min(len(lines), end + 3)
            out[key] = {"file": f, "start": start, "end": end, "from": lo,
                        "lines": [ln.rstrip()[:100] for ln in lines[lo - 1:hi]], "missing": not lines}
    return out


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--rules-dir", type=Path, default=ROOT / "docs/analysis/business-rules")
    ap.add_argument("--trace", type=Path, default=ROOT / "docs/analysis/traceability.json")
    ap.add_argument("--out", type=Path, default=ROOT / "docs/analysis/business-rule-explorer.html")
    args = ap.parse_args()
    rules: list[dict] = []
    for s in SLICES:
        p = args.rules_dir / f"{s}.json"
        if p.exists():
            rules += json.loads(p.read_text())
    trace = json.loads(args.trace.read_text()) if args.trace.exists() else {}
    data = {"rules": rules, "graph": build_graph(), "snippets": snippets(rules), "trace": trace.get("rules", {}),
            "traceMeta": {k: v for k, v in trace.items() if k != "rules"}}
    payload = json.dumps(data, separators=(",", ":")).replace("</", "<\\/")
    page = TEMPLATE.replace("/*DATA*/null", payload)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(page)
    print(f"{args.out}: {len(rules)} rules, {len(data['snippets'])} snippets, "
          f"{sum(1 for v in data['trace'].values() if v.get('tests'))} rules traced to tests")
    return 0


TEMPLATE = r"""<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Business Rule Explorer</title>
<style>
:root{--bg:#f5f6f8;--panel:#fff;--ink:#1b2230;--muted:#667085;--line:#dfe3ea;--accent:#1f5fbf;--accent-soft:#e8f0fc;
--ok:#18794e;--bad:#b42318;--warn:#a15c07;--code:#0f1724;--code-ink:#d6e2f3;--hl:#2a3b57;
--posting:#2563eb;--interest:#9333ea;--trantype:#0d9488;--online:#c2410c}
@media (prefers-color-scheme:dark){:root:not([data-theme="light"]){--bg:#0d1218;--panel:#151c26;--ink:#e4e9f1;--muted:#93a0b4;--line:#263041;--accent:#5b9bff;--accent-soft:#1a2b45;--ok:#4cc38a;--bad:#ff7a6e;--warn:#f0a83a;--code:#0a0f16;--hl:#22324c}}
:root[data-theme="dark"]{--bg:#0d1218;--panel:#151c26;--ink:#e4e9f1;--muted:#93a0b4;--line:#263041;--accent:#5b9bff;--accent-soft:#1a2b45;--ok:#4cc38a;--bad:#ff7a6e;--warn:#f0a83a;--code:#0a0f16;--hl:#22324c}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--ink);font:14px/1.45 Inter,system-ui,-apple-system,"Segoe UI",sans-serif}
header{padding:20px 24px 8px}h1{margin:0;font-size:22px}h2{font-size:16px;margin:0 0 10px}.muted{color:var(--muted)}.small{font-size:12px}
.mono,code,pre{font-family:"JetBrains Mono",ui-monospace,Menlo,monospace}
main{padding:8px 24px 32px;display:flex;flex-direction:column;gap:16px}
.card{background:var(--panel);border:1px solid var(--line);border-radius:10px;padding:16px;min-width:0}
.stats{display:flex;gap:24px;flex-wrap:wrap}.stat b{display:block;font-size:22px}
.chainbar{display:flex;gap:6px;flex-wrap:wrap;margin-bottom:10px}
button,.chip{font:inherit;border:1px solid var(--line);background:var(--panel);color:var(--ink);border-radius:999px;padding:4px 12px;cursor:pointer}
button.on,.chip.on{background:var(--accent);border-color:var(--accent);color:#fff}
.graphwrap{overflow-x:auto}svg text{font-family:inherit;fill:var(--ink)}
.node rect{fill:var(--panel);stroke:var(--line)}.node.job rect{fill:var(--accent-soft);stroke:var(--accent)}
.node.prog rect{stroke-width:2}.node.ds rect{rx:12}.node.click{cursor:pointer}.node.sel rect{stroke:var(--accent);stroke-width:3}
.edge{stroke:var(--muted);fill:none;stroke-opacity:.5}
.controls{display:flex;gap:10px;flex-wrap:wrap;align-items:center;margin-bottom:10px}
input[type=search]{font:inherit;padding:7px 10px;border:1px solid var(--line);border-radius:8px;background:var(--panel);color:var(--ink);min-width:280px}
select{font:inherit;padding:6px;border:1px solid var(--line);border-radius:8px;background:var(--panel);color:var(--ink)}
.layout{display:grid;grid-template-columns:minmax(0,1.25fr) minmax(0,1fr);gap:16px}
table{width:100%;border-collapse:collapse}th,td{text-align:left;padding:6px 8px;border-bottom:1px solid var(--line);vertical-align:top}
th{font-size:11px;text-transform:uppercase;letter-spacing:.04em;color:var(--muted)}
tbody tr{cursor:pointer}tbody tr:hover{background:var(--accent-soft)}tbody tr.sel{background:var(--accent-soft);box-shadow:inset 3px 0 var(--accent)}
.tablewrap{max-height:620px;overflow:auto}
.dot{display:inline-block;width:8px;height:8px;border-radius:50%;margin-right:6px}
.badge{font-size:11px;padding:1px 7px;border-radius:999px;border:1px solid currentColor;white-space:nowrap}
.ok{color:var(--ok)}.bad{color:var(--bad)}.warn{color:var(--warn)}
.detail h3{margin:0 0 4px;font-size:16px}.kv{display:grid;grid-template-columns:110px 1fr;gap:4px 10px;margin:10px 0}
.kv div:nth-child(odd){color:var(--muted)}
pre.src{background:var(--code);color:var(--code-ink);border-radius:8px;padding:8px 0;overflow:auto;font-size:12px;margin:6px 0 12px}
pre.src span{display:block;padding:0 10px;white-space:pre}pre.src span.h{background:var(--hl)}pre.src i{color:#7d8ba1;font-style:normal;display:inline-block;width:42px}
.gwt{border-left:3px solid var(--accent);padding:4px 10px;margin:8px 0;background:var(--accent-soft);border-radius:0 6px 6px 0}
@media (max-width:1000px){.layout{grid-template-columns:1fr}}
</style>
</head>
<body>
<header>
  <h1>CardDemo Business Rule Explorer</h1>
  <div class="muted">Reverse-engineered rules of the CardDemo estate, traced from legacy source (COBOL, JCL, CICS/BMS, HLASM, PL/I) to the Java 21 implementation and its tests. CICS, HLASM and PL/I are analysis-only: no legacy execution exists for them.</div>
</header>
<main>
  <section class="card stats" id="stats"></section>
  <section class="card">
    <h2>Job → step → program → dataset / table</h2>
    <div class="chainbar" id="chains"></div>
    <div class="graphwrap"><svg id="graph" role="img" aria-label="Batch chain graph"></svg></div>
    <div class="small muted">Click a program to filter the rule table to rules whose source lives in that program.</div>
  </section>
  <section class="layout">
    <div class="card">
      <h2>Rules</h2>
      <div class="controls">
        <input type="search" id="q" placeholder="Search id, title, rule text, field, file…">
        <span id="slicechips"></span>
        <select id="cat"><option value="">all categories</option></select>
        <span class="small muted" id="count"></span>
      </div>
      <div class="tablewrap"><table><thead><tr><th>Rule</th><th>Title</th><th>Category</th><th>Legacy source</th><th>Java / tests</th></tr></thead><tbody id="rows"></tbody></table></div>
    </div>
    <div class="card detail" id="detail"><p class="muted">Select a rule to see its legacy source, test case, Java implementation and tests.</p></div>
  </section>
</main>
<script>
const DATA = /*DATA*/null;
const SL = {posting:'var(--posting)',interest:'var(--interest)',trantype:'var(--trantype)',online:'var(--online)'};
const esc = s => String(s ?? '').replace(/[&<>"]/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]));
const state = {q:'', slices:new Set(), cat:'', prog:null, sel:null, chain:DATA.graph[0]?.chain};
const trace = id => DATA.trace[id] || {};

function stats(){
  const r = DATA.rules, traced = r.filter(x => (trace(x.id).tests||[]).length).length;
  const pass = r.filter(x => trace(x.id).status === 'pass').length, fail = r.filter(x => trace(x.id).status === 'fail').length;
  const per = ['posting','interest','trantype','online'].map(s => `<div class="stat"><span class="dot" style="background:${SL[s]}"></span>${s}<b>${r.filter(x=>x.slice===s).length}</b></div>`).join('');
  document.getElementById('stats').innerHTML = `<div class="stat">rules<b>${r.length}</b></div>${per}
    <div class="stat">traced to tests<b>${traced}</b></div>` + (DATA.traceMeta.generated ? `<div class="stat">tests passing<b class="ok">${pass}</b></div><div class="stat">tests failing<b class="${fail?'bad':''}">${fail}</b></div>` : '') +
    `<div class="stat small muted" style="align-self:end">${esc(DATA.traceMeta.note || 'Java traceability appears after the conversion phase.')}</div>`;
}

function chains(){
  const el = document.getElementById('chains');
  el.innerHTML = DATA.graph.map(g => `<button class="${g.chain===state.chain?'on':''}" data-c="${esc(g.chain)}"><span class="dot" style="background:${SL[g.slice]}"></span>${esc(g.chain)}</button>`).join('');
  el.querySelectorAll('button').forEach(b => b.onclick = () => { state.chain = b.dataset.c; chains(); graph(); });
}

function graph(){
  const g = DATA.graph.find(x => x.chain === state.chain), svg = document.getElementById('graph');
  const cols = [[],[],[],[]], edges = [], idx = {};
  const add = (col, key, label, cls, prog) => { if (!(key in idx)) { idx[key] = {col, i: cols[col].length, label, cls, prog}; cols[col].push(key); } return key; };
  g.jobs.forEach(j => { const jk = add(0,'J:'+j.job, j.job, 'job');
    j.steps.forEach(s => { const sk = add(1,'S:'+j.job+s.step, s.step, 'step'); edges.push([jk,sk]);
      s.programs.forEach(p => { const pk = add(2,'P:'+p, p, 'prog click', p.split(' ')[0]); edges.push([sk,pk]);
        s.datasets.forEach(d => { const dk = add(3,'D:'+d, d, 'ds'); edges.push([pk,dk]); }); }); }); });
  const W = [190,110,200,380], X = [0,230,380,620], H = 30, GAP = 10;
  const rows = Math.max(...cols.map(c => c.length), 1), height = rows * (H + GAP) + 30;
  const y = k => { const n = idx[k]; const off = (rows - cols[n.col].length) * (H + GAP) / 2; return 24 + off + n.i * (H + GAP); };
  let out = ['Job','Step','Program','Dataset / table'].map((t,i) => `<text x="${X[i]}" y="14" class="small" style="fill:var(--muted);font-size:11px">${t.toUpperCase()}</text>`).join('');
  out += edges.map(([a,b]) => { const A = idx[a], B = idx[b]; const x1 = X[A.col] + W[A.col], y1 = y(a) + H/2, x2 = X[B.col], y2 = y(b) + H/2, mx = (x1+x2)/2;
    return `<path class="edge" d="M${x1},${y1} C${mx},${y1} ${mx},${y2} ${x2},${y2}"/>`; }).join('');
  out += Object.entries(idx).map(([k,n]) => { const w = W[n.col], lbl = n.label.length > (w/7.2) ? n.label.slice(0, Math.floor(w/7.2)-1)+'…' : n.label;
    const sel = n.prog && n.prog === state.prog ? ' sel' : '';
    return `<g class="node ${n.cls}${sel}" ${n.prog?`data-p="${esc(n.prog)}"`:''}><title>${esc(n.label)}</title><rect x="${X[n.col]}" y="${y(k)}" width="${w}" height="${H}" rx="6"/><text x="${X[n.col]+8}" y="${y(k)+19}" font-size="12" class="${n.col>=2?'mono':''}">${esc(lbl)}</text></g>`; }).join('');
  svg.setAttribute('viewBox', `0 0 ${X[3]+W[3]+4} ${height}`); svg.setAttribute('width', X[3]+W[3]+4); svg.setAttribute('height', height);
  svg.innerHTML = out;
  svg.querySelectorAll('.node.click').forEach(n => n.onclick = () => { state.prog = state.prog === n.dataset.p ? null : n.dataset.p; graph(); rows_(); });
  if (g.cases.length) svg.insertAdjacentHTML('beforeend', '');
}

function matches(r){
  if (state.slices.size && !state.slices.has(r.slice)) return false;
  if (state.cat && r.category !== state.cat) return false;
  if (state.prog && !(r.sources||[]).some(s => s.file.toUpperCase().includes(state.prog.toUpperCase()))) return false;
  if (!state.q) return true;
  const t = trace(r.id);
  const hay = [r.id, r.title, r.rule, r.category, (r.fields||[]).join(' '), (r.sources||[]).map(s=>s.file).join(' '), (r.fixtures||[]).join(' '), (t.java||[]).join(' '), (t.tests||[]).join(' ')].join(' ').toLowerCase();
  return state.q.toLowerCase().split(/\s+/).every(w => hay.includes(w));
}

function rows_(){
  const list = DATA.rules.filter(matches);
  document.getElementById('count').textContent = `${list.length} of ${DATA.rules.length}` + (state.prog ? ` · program ${state.prog} (click again to clear)` : '');
  document.getElementById('rows').innerHTML = list.map(r => { const t = trace(r.id), n = (t.tests||[]).length;
    const st = t.status === 'pass' ? `<span class="badge ok">${n} pass</span>` : t.status === 'fail' ? `<span class="badge bad">fail</span>` : n ? `<span class="badge">${n} test${n>1?'s':''}</span>` : '<span class="muted small">-</span>';
    const src = (r.sources||[])[0]; return `<tr data-id="${r.id}" class="${state.sel===r.id?'sel':''}"><td class="mono" style="white-space:nowrap"><span class="dot" style="background:${SL[r.slice]}"></span>${r.id}</td>
    <td>${esc(r.title)}</td><td class="small">${esc(r.category)}</td><td class="mono small">${src?esc(src.file.split('/').pop())+':'+src.start:''}</td><td>${st}</td></tr>`; }).join('');
  document.querySelectorAll('#rows tr').forEach(tr => tr.onclick = () => { state.sel = tr.dataset.id; rows_(); detail(); });
}

function detail(){
  const r = DATA.rules.find(x => x.id === state.sel); if (!r) return; const t = trace(r.id);
  const srcs = (r.sources||[]).map(s => { const sn = DATA.snippets[`${s.file}:${s.start}-${s.end}`]; if (!sn || sn.missing) return `<div class="mono small">${esc(s.file)}:${s.start}-${s.end} (source not available)</div>`;
    return `<div class="mono small">${esc(s.file)} : ${s.start}${s.end!==s.start?'-'+s.end:''}</div><pre class="src">${sn.lines.map((l,i) => { const n = sn.from + i; return `<span class="${n>=sn.start&&n<=sn.end?'h':''}"><i>${n}</i>${esc(l)}</span>`; }).join('')}</pre>`; }).join('');
  const tests = (t.tests||[]).map(x => `<li class="mono small">${esc(x)}${t.results&&t.results[x]?` <span class="${t.results[x]==='pass'?'ok':'bad'}">${t.results[x]}</span>`:''}</li>`).join('');
  document.getElementById('detail').innerHTML = `<h3><span class="dot" style="background:${SL[r.slice]}"></span>${r.id} · ${esc(r.title)}</h3>
    <div class="muted small">${esc(r.slice)} · ${esc(r.category)}</div><p>${esc(r.rule)}</p>
    <div class="gwt"><b>Given</b> ${esc(r.test?.given)}<br><b>When</b> ${esc(r.test?.when)}<br><b>Then</b> ${esc(r.test?.then)}</div>
    <div class="kv"><div>Fields</div><div class="mono small">${(r.fields||[]).map(esc).join('<br>')||'-'}</div>
    <div>Fixtures</div><div class="mono small">${(r.fixtures||[]).map(esc).join('<br>')||'-'}</div>
    <div>Java</div><div class="mono small">${(t.java||[]).map(esc).join('<br>')||'<span class="muted">not traced yet</span>'}</div>
    <div>Tests</div><div>${tests?`<ul style="margin:0;padding-left:16px">${tests}</ul>`:'<span class="muted small">not traced yet</span>'}</div>
    ${t.regression?`<div>Regression</div><div class="small">${esc(t.regression)}</div>`:''}</div>
    <h2 style="margin-top:14px">Legacy source</h2>${srcs}`;
}

function controls(){
  const chips = document.getElementById('slicechips');
  chips.innerHTML = ['posting','interest','trantype','online'].map(s => `<button class="chip" data-s="${s}"><span class="dot" style="background:${SL[s]}"></span>${s}</button>`).join(' ');
  chips.querySelectorAll('button').forEach(b => b.onclick = () => { state.slices.has(b.dataset.s) ? state.slices.delete(b.dataset.s) : state.slices.add(b.dataset.s); b.classList.toggle('on'); rows_(); });
  const cat = document.getElementById('cat');
  [...new Set(DATA.rules.map(r => r.category))].sort().forEach(c => cat.insertAdjacentHTML('beforeend', `<option>${esc(c)}</option>`));
  cat.onchange = () => { state.cat = cat.value; rows_(); };
  document.getElementById('q').oninput = e => { state.q = e.target.value.trim(); rows_(); };
}
stats(); chains(); graph(); controls(); rows_();
const first = new URLSearchParams(location.hash.slice(1)).get('rule'); if (first) { state.sel = first; rows_(); detail(); }
</script>
</body>
</html>
"""

if __name__ == "__main__":
    raise SystemExit(main())
