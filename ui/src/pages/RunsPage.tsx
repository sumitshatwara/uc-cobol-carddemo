import { Link } from 'react-router-dom';
import { api } from '../lib/api';
import { useAsync } from '../lib/useAsync';

/** Runs submitted since the service started (the SDSF status display). */
export function RunsPage() {
  const { data, error } = useAsync(api.runs, []);
  return (
    <section>
      <h1>Run history</h1>
      {error && <p className="error">{error}</p>}
      {data?.length === 0 && <p className="muted">No runs yet.</p>}
      {!!data?.length && (
        <table className="grid" data-testid="runs-table">
          <thead><tr><th>Run</th><th>Chain</th><th>Case</th><th>Max RC</th><th>Parity</th><th>Duration</th></tr></thead>
          <tbody>
            {data.map((r) => (
              <tr key={r.id}>
                <td><Link className="mono" to={`/runs/${r.id}`}>{r.id}</Link></td>
                <td>{r.chain}</td><td className="mono">{r.caseName}</td>
                <td>{r.maxRc < 0 ? 'ABEND' : r.maxRc}</td>
                <td>{r.parity ? (r.parity.passed ? <span className="badge ok">PASS</span> : <span className="badge bad">FAIL</span>) : '-'}</td>
                <td>{r.durationMs} ms</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}
