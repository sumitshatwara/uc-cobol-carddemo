import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { api, type Run } from '../lib/api';
import { Markdown } from '../lib/Markdown';
import { useAsync } from '../lib/useAsync';
import { slices } from '../slices/registry';

type Tab = 'sysout' | 'datasets' | 'db' | 'parity';

/** A finished run: return codes, SYSOUT, output datasets (Java next to legacy golden) and parity. */
export function RunPage() {
  const { id = '' } = useParams();
  const { data: run, error } = useAsync(() => api.run(id), [id]);
  const [tab, setTab] = useState<Tab>('sysout');
  if (error) return <p className="error">{error}</p>;
  if (!run) return <p className="muted">Loading…</p>;

  const sysout = run.files.filter((f) => f.startsWith('sysout/'));
  const datasets = run.files.filter((f) => f.startsWith('datasets/'));
  const db = run.files.filter((f) => f.startsWith('db/'));
  const panels = slices.flatMap((s) => (s.runPanels?.[run.chain] ? [{ id: s.id, Panel: s.runPanels[run.chain] }] : []));

  return (
    <section>
      <p className="crumbs"><Link to="/">Jobs</Link> / <Link to={`/jobs/${run.chain}`}>{run.chain}</Link> / run {run.id}</p>
      <h1>{run.chain} <span className="muted">on</span> <span className="mono">{run.caseName}</span></h1>
      <div className="summary">
        <div><div className="muted small">Run</div><b className="mono">{run.id}</b></div>
        <div><div className="muted small">Duration</div><b>{run.durationMs} ms</b></div>
        <div><div className="muted small">Max RC</div><b data-testid="run-maxrc" className={rcClass(run.maxRc)}>{run.maxRc < 0 ? 'ABEND' : run.maxRc}</b></div>
        <div>
          <div className="muted small">Parity vs legacy COBOL</div>
          <b data-testid="parity-verdict" className={run.parity?.passed ? 'ok' : 'bad'}>{run.parity ? (run.parity.passed ? 'PASS' : 'FAIL') : 'n/a'}</b>
        </div>
      </div>
      {run.error && <pre className="error" data-testid="run-failure">{run.error}</pre>}
      {run.parity && <p className="small mono" data-testid="parity-summary">{run.parity.summary}</p>}
      <RcTable run={run} />
      {panels.map(({ id: pid, Panel }) => <Panel key={pid} run={run} />)}
      <nav className="tabs">
        <button className={tab === 'sysout' ? 'active' : ''} data-testid="tab-sysout" onClick={() => setTab('sysout')}>SYSOUT ({sysout.length})</button>
        <button className={tab === 'datasets' ? 'active' : ''} data-testid="tab-datasets" onClick={() => setTab('datasets')}>Output datasets ({datasets.length})</button>
        {db.length > 0 && <button className={tab === 'db' ? 'active' : ''} data-testid="tab-db" onClick={() => setTab('db')}>Tables ({db.length})</button>}
        <button className={tab === 'parity' ? 'active' : ''} data-testid="tab-parity" onClick={() => setTab('parity')}>Parity report</button>
      </nav>
      {tab === 'sysout' && <FileCompare run={run} files={sysout} />}
      {tab === 'datasets' && <FileCompare run={run} files={datasets} />}
      {tab === 'db' && <FileCompare run={run} files={db} />}
      {tab === 'parity' && (run.parity ? <Markdown source={run.parity.report} testId="parity-report" /> : <p className="muted">No parity report for this run.</p>)}
    </section>
  );
}

function rcClass(rc: number) {
  return rc < 0 || rc > 4 ? 'bad' : rc === 4 ? 'warnc' : 'ok';
}

function RcTable({ run }: { run: Run }) {
  if (!run.rc) return null;
  return (
    <table className="grid compact" data-testid="rc-table">
      <thead><tr><th>Job</th><th>Step</th><th>RC</th></tr></thead>
      <tbody>
        {Object.entries(run.rc).flatMap(([job, j]) =>
          Object.entries(j.steps).map(([step, rc]) => (
            <tr key={job + step} data-testid={`rc-${job}-${step}`}>
              <td className="mono">{job}</td><td className="mono">{step}</td><td className={rcClass(rc)}>{rc}</td>
            </tr>
          )),
        )}
      </tbody>
    </table>
  );
}

/** Lists files; shows the selected Java output next to the legacy golden file of the same case. */
function FileCompare({ run, files }: { run: Run; files: string[] }) {
  const [sel, setSel] = useState<string | undefined>(files[0]);
  const [java, setJava] = useState('');
  const [legacy, setLegacy] = useState<string>();
  useEffect(() => {
    if (!sel) return;
    api.runFile(run.id, sel).then(setJava).catch((e: Error) => setJava(`(${e.message})`));
    api.caseFile(run.chain, run.caseName, 'expected', sel).then(setLegacy).catch(() => setLegacy(undefined));
  }, [run, sel]);
  if (files.length === 0) return <p className="muted">None.</p>;
  return (
    <div className="filecompare">
      <ul className="filelist" data-testid="file-list">
        {files.map((f) => (
          <li key={f}><button className={f === sel ? 'active mono' : 'mono'} onClick={() => setSel(f)}>{f.replace(/^(sysout|datasets|db)\//, '')}</button></li>
        ))}
      </ul>
      <div className="panes">
        <div><h4>Java (Spring Batch)</h4><pre className="listing" data-testid="java-file">{java || ' '}</pre></div>
        <div><h4>Legacy COBOL (golden)</h4><pre className="listing" data-testid="legacy-file">{legacy ?? '(no golden file)'}</pre></div>
      </div>
    </div>
  );
}
