import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import type { RunSummary } from '../types';

export function RunsPage() {
  const [runs, setRuns] = useState<RunSummary[]>([]);
  useEffect(() => { fetch('/api/runs').then((response) => response.json()).then(setRuns).catch(() => setRuns([])); }, []);
  return (
    <section>
      <div className="page-heading"><div><div className="eyebrow">EXECUTION HISTORY</div><h1>Runs</h1><p>Recent fixture-backed Java chain executions.</p></div></div>
      <div className="table-card">
        <table><thead><tr><th>RUN</th><th>CHAIN</th><th>FIXTURE</th><th>STATUS</th><th>MAX RC</th><th>STARTED</th></tr></thead>
          <tbody>{runs.map((run) => <tr key={run.runId}>
            <td><Link className="mono-link" to={`/runs/${run.runId}`}>{run.runId.slice(0, 8)}</Link></td>
            <td>{run.chain}</td><td>{run.caseName}</td><td><span className="badge badge-green">{run.status}</span></td>
            <td>{run.maxRc}</td><td>{new Date(run.startedAt).toLocaleString()}</td>
          </tr>)}
          {runs.length === 0 && <tr><td colSpan={6} className="empty-row">No Java runs recorded yet.</td></tr>}</tbody>
        </table>
      </div>
    </section>
  );
}
