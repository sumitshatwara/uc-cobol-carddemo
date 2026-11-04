import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import ReactMarkdown from 'react-markdown';
import type { RunSummary } from '../types';

type Tab = 'sysout' | 'datasets' | 'db' | 'parity';
type DecodedDataset = { dsn: string; layout: string; records: Record<string, unknown>[] };

export function RunDetailPage() {
  const { runId = '' } = useParams();
  const [run, setRun] = useState<RunSummary | null>(null);
  const [tab, setTab] = useState<Tab>('sysout');
  const [fileText, setFileText] = useState<Record<string, string>>({});
  const [decoded, setDecoded] = useState<Record<string, DecodedDataset>>({});
  const [parity, setParity] = useState<{ verdict: string; reportMarkdown: string } | null>(null);
  const [loadingParity, setLoadingParity] = useState(false);
  useEffect(() => { fetch(`/api/runs/${runId}`).then((response) => response.json()).then(setRun).catch(() => setRun(null)); }, [runId]);

  async function fetchFile(path: string) {
    if (fileText[path] !== undefined) return;
    const response = await fetch(`/api/runs/${runId}/files?path=${encodeURIComponent(path)}`);
    if (response.ok) {
      const content = await response.text();
      setFileText((current) => ({ ...current, [path]: content }));
    }
  }
  async function fetchDecoded(dsn: string) {
    if (decoded[dsn]) return;
    const response = await fetch(`/api/runs/${runId}/datasets/${encodeURIComponent(dsn)}/decoded`);
    if (response.ok) {
      const data = await response.json();
      setDecoded((current) => ({ ...current, [dsn]: data }));
    }
  }
  async function compare() {
    setLoadingParity(true);
    const response = await fetch(`/api/runs/${runId}/parity`, { method: 'POST' });
    if (response.ok) setParity(await response.json());
    setLoadingParity(false);
  }

  if (!run) return <section><Link className="back-link" to="/runs">← Runs</Link><div className="empty-card">Run not found.</div></section>;
  const sysoutFiles = run.files.filter((file) => file.startsWith('sysout/'));
  const datasetFiles = run.files.filter((file) => file.startsWith('datasets/') && file !== 'datasets/manifest.json');
  const dbFiles = run.files.filter((file) => file.startsWith('db/'));
  return (
    <section>
      <Link className="back-link" to="/runs">← All runs</Link>
      <div className="page-heading run-heading">
        <div><div className="eyebrow">{run.chain.toUpperCase()} / {run.caseName}</div><h1>Run detail</h1><p className="run-id">{run.runId}</p></div>
        <div className="run-summary"><span data-testid="run-status" className="badge badge-green">{run.status}</span><span className="rc-summary">MAX RC <strong data-testid="run-maxrc">{run.maxRc}</strong></span></div>
      </div>
      <div className="rc-card"><div className="section-heading"><div><h2>Return codes</h2><p>Job and step completion codes</p></div></div>
        <table data-testid="rc-table"><thead><tr><th>JOB</th><th>MAX RC</th><th>STEP</th><th>RC</th></tr></thead>
          <tbody>{Object.entries(run.rc).flatMap(([job, result]) => Object.entries(result.steps).map(([step, rc]) => (
            <tr key={`${job}-${step}`}><td className="mono">{job}</td><td>{result.maxrc}</td><td className="mono">{step}</td><td>{rc}</td></tr>
          )))}</tbody>
        </table>
      </div>
      <div className="tab-bar" role="tablist">
        {(['sysout', 'datasets', 'db', 'parity'] as Tab[]).map((name) => <button key={name} role="tab" aria-selected={tab === name}
          data-testid={`tab-${name}`} className={tab === name ? 'active' : ''} onClick={() => setTab(name)}>{name === 'db' ? 'DB snapshots' : name.toUpperCase()}</button>)}
      </div>
      {tab === 'sysout' && <div className="output-grid">{sysoutFiles.length ? sysoutFiles.map((path) => {
        const name = path.slice('sysout/'.length, -4);
        void fetchFile(path);
        return <article className="output-card" key={path}><h3 data-testid={`sysout-${name}`}>{name.replace('.', ' · ')}</h3><pre>{fileText[path] ?? 'Loading SYSOUT…'}</pre></article>;
      }) : <div className="empty-card">No SYSOUT files in this run.</div>}</div>}
      {tab === 'datasets' && <div className="output-grid">{datasetFiles.length ? datasetFiles.map((path) => {
        const dsn = path.slice('datasets/'.length, -4);
        void fetchFile(path); void fetchDecoded(dsn);
        const decodedDataset = decoded[dsn];
        return <article className="output-card" key={path} data-testid={`dataset-${dsn}`}><h3>{dsn}</h3>
          <div className="dataset-columns"><div><span className="mini-label">RAW RECORDS</span><pre>{fileText[path] ?? 'Loading…'}</pre></div>
            <div><span className="mini-label">DECODED FIELDS</span>{decodedDataset?.records?.[0] ? <table className="decoded-table"><tbody>{Object.entries(decodedDataset.records[0]).map(([field, value]) => <tr key={field}><th>{field}</th><td>{String(value)}</td></tr>)}</tbody></table> : <p className="muted">No decoded records</p>}</div>
          </div></article>;
      }) : <div className="empty-card">No dataset files in this run.</div>}</div>}
      {tab === 'db' && <div className="output-grid">{dbFiles.length ? dbFiles.map((path) => { void fetchFile(path); return <article className="output-card" key={path}><h3>{path.slice(3)}</h3><pre>{fileText[path] ?? 'Loading snapshot…'}</pre></article>; }) : <div className="empty-card">No database snapshots in this run.</div>}</div>}
      {tab === 'parity' && <div className="parity-card"><div className="section-heading"><div><h2>Legacy parity</h2><p>Compare this Java output against the committed fixture golden.</p></div>
        <button data-testid="parity-button" className="button-primary" onClick={() => void compare()} disabled={loadingParity}>{loadingParity ? 'Comparing…' : 'Compare with legacy'}</button></div>
        {parity && <><div data-testid="parity-verdict" className={`verdict ${parity.verdict === 'PASS' ? 'verdict-pass' : 'verdict-fail'}`}>{parity.verdict}</div><div className="markdown-body"><ReactMarkdown>{parity.reportMarkdown}</ReactMarkdown></div></>}
      </div>}
    </section>
  );
}
