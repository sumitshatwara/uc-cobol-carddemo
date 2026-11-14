import { useEffect, useState } from 'react';
import { api, type Run } from '../../lib/api';
import { useAsync } from '../../lib/useAsync';
import type { SliceModule } from '../types';

/**
 * Transaction type maintenance (MNTTRDB2 / MNTTRDYN): a run panel comparing the Db2 table
 * carddemo.transaction_type before the job (the case's input/db/seed.sql) with the table after
 * the run (db/carddemo.transaction_type.csv written by the run), and a page with the current rows.
 */

export type Row = { trType: string; trDescription: string };
export type DiffStatus = 'added' | 'changed' | 'deleted' | 'unchanged';
export type DiffRow = { trType: string; before?: string; after?: string; status: DiffStatus };

/** Rows of the seed script: INSERT ... VALUES ('01','Purchase'),(...). */
export function parseSeed(sql: string): Row[] {
  const rows: Row[] = [];
  const re = /\('((?:[^']|'')*)'\s*,\s*'((?:[^']|'')*)'\)/g;
  let m: RegExpExecArray | null;
  while ((m = re.exec(sql)) !== null) rows.push({ trType: m[1].replace(/''/g, "'"), trDescription: m[2].replace(/''/g, "'") });
  return rows;
}

/** PostgreSQL COPY ... CSV HEADER output (quoted fields allowed). */
export function parseCsv(text: string): Row[] {
  const records: string[][] = [];
  let field = '';
  let rec: string[] = [];
  let quoted = false;
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if (quoted) {
      if (c === '"' && text[i + 1] === '"') { field += '"'; i++; }
      else if (c === '"') quoted = false;
      else field += c;
    } else if (c === '"') quoted = true;
    else if (c === ',') { rec.push(field); field = ''; }
    else if (c === '\n') { rec.push(field); records.push(rec); rec = []; field = ''; }
    else if (c !== '\r') field += c;
  }
  if (field !== '' || rec.length) { rec.push(field); records.push(rec); }
  return records.slice(1).filter((r) => r.length >= 2).map((r) => ({ trType: r[0], trDescription: r[1] }));
}

export function diff(before: Row[], after: Row[]): DiffRow[] {
  const b = new Map(before.map((r) => [r.trType, r.trDescription]));
  const a = new Map(after.map((r) => [r.trType, r.trDescription]));
  const keys = [...new Set([...b.keys(), ...a.keys()])].sort();
  return keys.map((k) => {
    const bv = b.get(k);
    const av = a.get(k);
    const status: DiffStatus = bv === undefined ? 'added' : av === undefined ? 'deleted' : bv === av ? 'unchanged' : 'changed';
    return { trType: k, before: bv, after: av, status };
  });
}

const tint: Record<DiffStatus, string | undefined> = {
  added: 'rgba(26,127,69,0.14)',
  changed: 'rgba(168,100,0,0.16)',
  deleted: 'rgba(180,35,24,0.14)',
  unchanged: undefined,
};

/** A description with its stored trailing spaces made visible (COBTUPDT pads to 50). */
function Desc({ value }: { value?: string }) {
  if (value === undefined) return <span className="muted">-</span>;
  const trimmed = value.replace(/ +$/, '');
  const pad = value.length - trimmed.length;
  return (
    <span className="mono">
      {trimmed}
      {pad > 0 && <span className="muted small" title={`${pad} trailing spaces`}> ␣×{pad}</span>}
    </span>
  );
}

function TransactionTypePanel({ run }: { run: Run }) {
  const [rows, setRows] = useState<DiffRow[] | undefined>();
  const [error, setError] = useState<string | undefined>();
  useEffect(() => {
    let live = true;
    Promise.all([
      api.caseFile(run.chain, run.caseName, 'input', 'db/seed.sql').catch(() => ''),
      api.runFile(run.id, 'db/carddemo.transaction_type.csv'),
    ])
      .then(([seed, csv]) => { if (live) setRows(diff(parseSeed(seed), parseCsv(csv))); })
      .catch((e: Error) => { if (live) setError(e.message); });
    return () => { live = false; };
  }, [run.id, run.chain, run.caseName]);

  const counts = (s: DiffStatus) => rows?.filter((r) => r.status === s).length ?? 0;
  return (
    <div className="card" data-testid="trantype-panel" style={{ margin: '14px 0' }}>
      <h3 style={{ marginTop: 0 }}>CARDDEMO.TRANSACTION_TYPE: seed vs after run</h3>
      <p className="small muted">
        Before = <span className="mono">input/db/seed.sql</span> of case {run.caseName}; after = the table committed at end of task
        (<span className="mono">db/carddemo.transaction_type.csv</span>).
      </p>
      {error && <p className="error">{error}</p>}
      {!rows && !error && <p className="muted">Loading…</p>}
      {rows && (
        <>
          <p className="small" data-testid="trantype-counts">
            <span className="ok">{counts('added')} added</span> · <span className="warnc">{counts('changed')} changed</span> ·{' '}
            <span className="bad">{counts('deleted')} deleted</span> · {counts('unchanged')} unchanged
          </p>
          <table className="grid compact" data-testid="trantype-diff">
            <thead>
              <tr><th>TR_TYPE</th><th>Before (seed)</th><th>After run</th><th>Change</th></tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.trType} data-testid={`trantype-row-${r.trType}`} data-status={r.status} style={{ background: tint[r.status] }}>
                  <td className="mono">{r.trType}</td>
                  <td><Desc value={r.before} /></td>
                  <td><Desc value={r.after} /></td>
                  <td className={r.status === 'added' ? 'ok' : r.status === 'deleted' ? 'bad' : r.status === 'changed' ? 'warnc' : 'muted'}>
                    {r.status}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </>
      )}
    </div>
  );
}

function TransactionTypesPage() {
  const { data, error, reload } = useAsync(
    () => fetch('/api/trantype/types').then((r) => { if (!r.ok) throw new Error(`${r.status}`); return r.json() as Promise<Row[]>; }),
    [],
  );
  return (
    <section>
      <h1>Transaction types</h1>
      <p className="muted">Current rows of CARDDEMO.TRANSACTION_TYPE in the Java database (left by the latest run). <button onClick={reload}>Refresh</button></p>
      {error && <p className="error">{error}</p>}
      {data && (
        <table className="grid" data-testid="trantype-types">
          <thead><tr><th>TR_TYPE</th><th>TR_DESCRIPTION</th><th>Length</th></tr></thead>
          <tbody>
            {data.map((r) => (
              <tr key={r.trType}><td className="mono">{r.trType}</td><td><Desc value={r.trDescription} /></td><td>{r.trDescription.length}</td></tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}

const slice: SliceModule = {
  id: 'trantype',
  title: 'Transaction type maintenance',
  nav: [{ label: 'Transaction types', path: '/trantype/types', testId: 'nav-trantype' }],
  routes: [{ path: '/trantype/types', element: <TransactionTypesPage /> }],
  runPanels: {
    'trantype-update': TransactionTypePanel,
    'trantype-dynamic': TransactionTypePanel,
  },
};

export default slice;
