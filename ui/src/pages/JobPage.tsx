import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { api } from '../lib/api';
import { useAsync } from '../lib/useAsync';

/** One chain: its JCL steps and fixture cases; submit a run (replaces TSO SUBMIT). */
export function JobPage() {
  const { chain = '' } = useParams();
  const nav = useNavigate();
  const { data: job, error } = useAsync(() => api.job(chain), [chain]);
  const [busy, setBusy] = useState<string>();
  const [runError, setRunError] = useState<string>();

  const submit = async (caseName: string) => {
    setBusy(caseName);
    setRunError(undefined);
    try {
      const run = await api.submit(chain, caseName);
      nav(`/runs/${run.id}`);
    } catch (e) {
      setRunError((e as Error).message);
    } finally {
      setBusy(undefined);
    }
  };

  if (error) return <p className="error">{error}</p>;
  if (!job) return <p className="muted">Loading…</p>;
  return (
    <section>
      <p className="crumbs"><Link to="/">Jobs</Link> / {job.chain}</p>
      <h1>{job.chain}</h1>
      <p className="lede">{job.description}</p>
      <div className="cards">
        {job.jobs.map((j) => (
          <div className="card" key={j.job}>
            <h3 className="mono">{j.job}</h3>
            <div className="muted small mono">{j.path}</div>
            <table className="grid compact">
              <thead><tr><th>Step</th><th>Program</th></tr></thead>
              <tbody>{j.steps.map((s, i) => <tr key={i}><td className="mono">{s.step}</td><td className="mono">{s.program}</td></tr>)}</tbody>
            </table>
          </div>
        ))}
        <div className="card">
          <h3>Datasets</h3>
          <div className="small"><b>Inputs</b></div>
          <ul className="mono small">{Object.entries(job.inputs).map(([d, l]) => <li key={d}>{d} <span className="muted">LRECL {l}</span></li>)}</ul>
          <div className="small"><b>Outputs</b></div>
          <ul className="mono small">{Object.entries(job.outputs).map(([d, l]) => <li key={d}>{d} <span className="muted">{l}</span></li>)}</ul>
          {job.tables.length > 0 && <><div className="small"><b>Tables</b></div><ul className="mono small">{job.tables.map((t) => <li key={t}>{t}</li>)}</ul></>}
          <div className="muted small">Frozen clock {job.clock}</div>
        </div>
      </div>
      <h2>Fixture cases</h2>
      {!job.implemented && <p className="warn">The Java implementation of this chain is not available yet.</p>}
      {runError && <p className="error" data-testid="run-error">{runError}</p>}
      <table className="grid" data-testid="cases-table">
        <thead><tr><th>Case</th><th>What it proves</th><th /></tr></thead>
        <tbody>
          {job.cases?.map((c) => (
            <tr key={c.name} data-testid={`case-row-${c.name}`}>
              <td className="mono strong">{c.name}</td>
              <td className="small">{summary(c.readme)}</td>
              <td>
                <button data-testid={`run-${c.name}`} disabled={!!busy || !job.implemented} onClick={() => submit(c.name)}>
                  {busy === c.name ? 'Running…' : 'Run'}
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  );
}

function summary(readme: string): string {
  const body = readme.split('\n').filter((l) => l.trim() && !l.startsWith('#'));
  return body.slice(0, 2).join(' ').slice(0, 260);
}
