import { Link } from 'react-router-dom';
import { api } from '../lib/api';
import { useAsync } from '../lib/useAsync';

/** Job library: every batch chain the estate runs, with its legacy JCL and Java status. */
export function JobsPage() {
  const { data, error, loading } = useAsync(api.jobs, []);
  return (
    <section>
      <h1>Batch jobs</h1>
      <p className="lede">The JCL job streams of the CardDemo estate. Pick one to run it against a fixture case on the Java 21 / Spring Batch platform.</p>
      {loading && <p className="muted">Loading…</p>}
      {error && <p className="error">{error}</p>}
      {data && (
        <table className="grid" data-testid="jobs-table">
          <thead>
            <tr><th>Chain</th><th>JCL jobs</th><th>Programs</th><th>Cases</th><th>Java</th></tr>
          </thead>
          <tbody>
            {data.map((j) => (
              <tr key={j.chain} data-testid={`job-row-${j.chain}`}>
                <td><Link to={`/jobs/${j.chain}`} className="strong">{j.chain}</Link><div className="muted small">{j.description}</div></td>
                <td className="mono">{j.jobs.map((x) => x.job).join(', ')}</td>
                <td className="mono">{[...new Set(j.jobs.flatMap((x) => x.steps.map((s) => s.program)).filter((p) => /^C[OB]/.test(p)))].join(', ')}</td>
                <td>{j.caseCount}</td>
                <td>{j.implemented ? <span className="badge ok">implemented</span> : <span className="badge muted">pending</span>}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}
