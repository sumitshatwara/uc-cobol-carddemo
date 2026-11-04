#!/usr/bin/env python3
"""Builds docs/analysis/business-rule-explorer.html (self-contained, no network).

Inputs: the trailing ```json block of docs/analysis/business-rules/<slice>.md (or the directory given as argv[1]),
field metadata from docs/analysis/data-dictionary.md and, when present,
docs/analysis/traceability.json ({"<rule id>": {"java": [...], "tests": [...]}}).
"""
import json, re, sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DOCS = ROOT / "docs" / "analysis"
REPO_URL = "https://github.com/sumitshatwara/uc-cobol-carddemo/blob/main/"
SLICES = ["posting", "interest", "trantype", "online"]


def load_rules(rules_dir):
    out = []
    for s in SLICES:
        p = rules_dir / f"{s}.md"
        if not p.exists():
            continue
        blocks = re.findall(r"```json\s*\n(.*?)\n```", p.read_text(), re.S)
        if not blocks:
            sys.exit(f"{p}: no ```json block")
        doc = json.loads(blocks[-1])
        for r in doc["rules"]:
            r["slice"] = s
            out.append(r)
    return out


def load_fields():
    fields = {}
    p = DOCS / "data-dictionary.md"
    if not p.exists():
        return fields
    section = ""
    header = []
    for line in p.read_text().splitlines():
        if line.startswith("## "):
            section, header = line[3:], []
        elif line.startswith("| ") and not header:
            header = [c.strip().lower() for c in line.strip("|").split("|")]
        elif line.startswith("|---"):
            continue
        elif line.startswith("| ") and header:
            cells = [c.strip().strip("`") for c in line.strip("|").split("|")]
            row = dict(zip(header, cells))
            name = row.get("field") or row.get("db2 column")
            if name:
                fields.setdefault(name, {"section": section, **row})
        else:
            header = header if line.startswith("|") else []
    return fields


def main():
    rules_dir = Path(sys.argv[1]) if len(sys.argv) > 1 else DOCS / "business-rules"
    rules = load_rules(rules_dir)
    trace_p = DOCS / "traceability.json"
    trace = json.loads(trace_p.read_text()) if trace_p.exists() else {}
    data = {"rules": rules, "fields": load_fields(), "trace": trace, "repo": REPO_URL}
    html = TEMPLATE.replace("/*DATA*/null", json.dumps(data).replace("</", "<\\/"))
    (DOCS / "business-rule-explorer.html").write_text(html)
    print(f"business-rule-explorer.html: {len(rules)} rules, {len(trace)} traced")


TEMPLATE = r"""<!doctype html>
<html lang="en"><head><meta charset="utf-8"><title>CardDemo Business Rule Explorer</title>
<meta name="viewport" content="width=device-width,initial-scale=1">
<style>
:root{--bg:#0d1117;--panel:#161b22;--line:#30363d;--fg:#e6edf3;--mut:#8b949e;--acc:#58a6ff;--ok:#3fb950;--warn:#d29922;--bad:#f85149}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--fg);font:14px/1.45 -apple-system,Segoe UI,Roboto,sans-serif}
header{padding:14px 20px;border-bottom:1px solid var(--line);display:flex;gap:24px;align-items:center;flex-wrap:wrap}
h1{font-size:18px;margin:0}h1 small{color:var(--mut);font-weight:400}
.stats{display:flex;gap:10px;flex-wrap:wrap}.stat{background:var(--panel);border:1px solid var(--line);border-radius:6px;padding:4px 10px}
.stat b{font-size:16px;margin-right:4px}
.bar{display:flex;gap:8px;padding:10px 20px;border-bottom:1px solid var(--line);flex-wrap:wrap;align-items:center}
button.chip{background:var(--panel);color:var(--fg);border:1px solid var(--line);border-radius:14px;padding:3px 11px;cursor:pointer;font-size:13px}
button.chip.on{background:#1f6feb;border-color:#1f6feb}
input[type=search]{background:var(--panel);color:var(--fg);border:1px solid var(--line);border-radius:6px;padding:6px 10px;width:280px}
select{background:var(--panel);color:var(--fg);border:1px solid var(--line);border-radius:6px;padding:5px}
main{display:grid;grid-template-columns:minmax(420px,44%) 1fr;height:calc(100vh - 112px)}
#list{overflow:auto;border-right:1px solid var(--line)}
table{width:100%;border-collapse:collapse}th,td{text-align:left;padding:6px 10px;border-bottom:1px solid var(--line);vertical-align:top}
th{position:sticky;top:0;background:var(--panel);color:var(--mut);font-weight:600;font-size:12px;text-transform:uppercase}
tr.r{cursor:pointer}tr.r:hover{background:#1c2129}tr.r.sel{background:#1f2a3a}
.id{font-family:ui-monospace,Menlo,monospace;white-space:nowrap;color:var(--acc)}
.tag{display:inline-block;border-radius:4px;padding:0 6px;font-size:11px;border:1px solid var(--line);color:var(--mut);white-space:nowrap}
.tag.fx{color:var(--ok);border-color:#238636}.tag.find{color:var(--warn);border-color:#9e6a03}.tag.tr{color:var(--acc);border-color:#1f6feb}
#detail{overflow:auto;padding:18px 24px}#detail h2{margin:0 0 4px;font-size:18px}
.sec{margin:16px 0}.sec h3{font-size:12px;text-transform:uppercase;color:var(--mut);margin:0 0 6px}
.gwt{display:grid;grid-template-columns:70px 1fr;gap:4px 10px;background:var(--panel);border:1px solid var(--line);border-radius:6px;padding:10px}
.gwt b{color:var(--mut)}.finding{border-left:3px solid var(--warn);background:#2a2110;padding:8px 12px;border-radius:4px}
code,.mono{font-family:ui-monospace,Menlo,monospace;font-size:12.5px}a{color:var(--acc)}
.fld{display:inline-block;margin:2px 6px 2px 0;padding:2px 7px;background:var(--panel);border:1px solid var(--line);border-radius:4px;cursor:help}
.empty{color:var(--mut);padding:30px}
</style></head><body>
<header><h1>CardDemo Business Rule Explorer <small>COBOL / JCL / Db2 / PL/I / HLASM -> Java 21</small></h1><div class="stats" id="stats"></div></header>
<div class="bar"><span id="slices"></span><select id="cat" data-testid="category-filter"><option value="">all categories</option></select>
<button class="chip" id="onlyFind" data-testid="findings-toggle">findings only</button>
<input type="search" id="q" placeholder="search id, text, field, file..." data-testid="rule-search"><span id="count" style="color:var(--mut)"></span></div>
<main><div id="list"><table><thead><tr><th>Rule</th><th>Title</th><th>Category</th><th>Proof</th></tr></thead><tbody id="rows"></tbody></table></div>
<div id="detail"><div class="empty">Select a rule.</div></div></main>
<script>
const D=/*DATA*/null;
const esc=s=>String(s??"").replace(/[&<>"]/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;"}[c]));
const st={slice:"",cat:"",q:"",find:false,sel:null};
const tr=id=>D.trace[id]||{};
function stats(){const r=D.rules,f=r.filter(x=>x.finding).length,fx=r.filter(x=>(x.fixtures||[]).length).length,t=r.filter(x=>(tr(x.id).tests||[]).length).length;
 document.getElementById("stats").innerHTML=[[r.length,"rules"],[fx,"fixture-proven"],[r.length-fx,"source-only"],[f,"legacy findings"],[t,"traced to Java tests"]].map(([n,l])=>`<span class="stat"><b>${n}</b>${l}</span>`).join("")}
function chips(){const by={};D.rules.forEach(r=>by[r.slice]=(by[r.slice]||0)+1);
 document.getElementById("slices").innerHTML=[["","all",D.rules.length],...Object.entries(by).map(([k,v])=>[k,k,v])].map(([k,l,n])=>`<button class="chip ${st.slice===k?"on":""}" data-s="${k}" data-testid="slice-${l}">${l} (${n})</button>`).join(" ");
 document.querySelectorAll("[data-s]").forEach(b=>b.onclick=()=>{st.slice=b.dataset.s;chips();render()})}
function match(r){if(st.slice&&r.slice!==st.slice)return false;if(st.cat&&r.category!==st.cat)return false;if(st.find&&!r.finding)return false;
 if(!st.q)return true;const t=st.q.toLowerCase();return JSON.stringify([r,tr(r.id)]).toLowerCase().includes(t)}
function render(){const rs=D.rules.filter(match);document.getElementById("count").textContent=`${rs.length} shown`;
 document.getElementById("rows").innerHTML=rs.map(r=>`<tr class="r ${st.sel===r.id?"sel":""}" data-id="${esc(r.id)}" data-testid="rule-row"><td class="id">${esc(r.id)}</td><td>${esc(r.title)}${r.finding?' <span class="tag find">finding</span>':""}</td><td><span class="tag">${esc(r.category)}</span></td><td>${(r.fixtures||[]).length?'<span class="tag fx">golden</span>':'<span class="tag">source</span>'}${(tr(r.id).tests||[]).length?' <span class="tag tr">java</span>':""}</td></tr>`).join("");
 document.querySelectorAll("tr.r").forEach(e=>e.onclick=()=>{st.sel=e.dataset.id;location.hash=st.sel;render();show()})}
function fieldChip(f){const m=D.fields[f];const tip=m?Object.entries(m).filter(([k])=>k!=="meaning").map(([k,v])=>k+": "+v).join("\n"):"not in data dictionary";return `<span class="fld mono" title="${esc(tip)}">${esc(f)}${m&&m["java type"]?` <span style="color:var(--mut)">${esc(m["java type"])}</span>`:""}</span>`}
function src(s){const u=D.repo+s.file+"#L"+s.startLine+(s.endLine&&s.endLine!==s.startLine?"-L"+s.endLine:"");return `<div class="mono"><a href="${esc(u)}" target="_blank" rel="noopener">${esc(s.file)}:${s.startLine}${s.endLine&&s.endLine!==s.startLine?"-"+s.endLine:""}</a></div>`}
function show(){const r=D.rules.find(x=>x.id===st.sel);const el=document.getElementById("detail");if(!r){el.innerHTML='<div class="empty">Select a rule.</div>';return}
 const t=tr(r.id);el.innerHTML=`<div data-testid="rule-detail"><div class="id">${esc(r.id)} &middot; ${esc(r.slice)}</div><h2>${esc(r.title)}</h2><span class="tag">${esc(r.category)}</span>
 <div class="sec"><h3>Rule</h3>${esc(r.rule)}</div>
 ${r.finding?`<div class="sec"><h3>Legacy finding (reproduced, not fixed)</h3><div class="finding">${esc(r.finding)}</div></div>`:""}
 <div class="sec"><h3>Test (Given / When / Then)</h3><div class="gwt"><b>Given</b><span>${esc(r.given)}</span><b>When</b><span>${esc(r.when)}</span><b>Then</b><span>${esc(r.then)}</span></div></div>
 <div class="sec"><h3>Legacy source</h3>${(r.sources||[]).map(src).join("")}</div>
 <div class="sec"><h3>Fields</h3>${(r.fields||[]).map(fieldChip).join("")||"-"}</div>
 <div class="sec"><h3>Golden proof</h3>${(r.fixtures||[]).map(f=>`<div class="mono">${esc(f)}</div>`).join("")||'<span style="color:var(--mut)">source-only</span>'}</div>
 <div class="sec"><h3>Java traceability</h3>${(t.java||[]).length||(t.tests||[]).length?`${(t.java||[]).map(j=>`<div class="mono">${esc(j)}</div>`).join("")}<div style="margin-top:6px">${(t.tests||[]).map(x=>`<div class="mono" style="color:var(--ok)">&#10003; ${esc(x)}</div>`).join("")}</div>`:'<span style="color:var(--mut)">not yet converted</span>'}</div></div>`}
stats();chips();[...new Set(D.rules.map(r=>r.category))].sort().forEach(c=>document.getElementById("cat").add(new Option(c,c)));
document.getElementById("cat").onchange=e=>{st.cat=e.target.value;render()};document.getElementById("q").oninput=e=>{st.q=e.target.value;render()};
document.getElementById("onlyFind").onclick=e=>{st.find=!st.find;e.target.classList.toggle("on",st.find);render()};
st.sel=decodeURIComponent(location.hash.slice(1))||(D.rules[0]||{}).id;render();show();const se=document.querySelector("tr.sel");se&&se.scrollIntoView({block:"center"});
</script></body></html>
"""

if __name__ == "__main__":
    main()
