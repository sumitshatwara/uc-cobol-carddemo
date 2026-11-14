import { api, type Decoded, type Run } from '../../lib/api';
import { useAsync } from '../../lib/useAsync';
import type { SliceModule } from '../types';

const REJECTS = 'datasets/AWS.M2.CARDDEMO.DALYREJS.G0001V00.txt';
const REPORT = 'datasets/AWS.M2.CARDDEMO.TRANREPT.G0001V00.txt';

/** POSTTRAN / TRANREPT results: CBTRN02C rejects (reason code + text) and the CBTRN03C report listing. */
function PostingPanel({ run }: { run: Run }) {
  const hasRejects = run.files.includes(REJECTS);
  const rejects = useAsync<Decoded | null>(
    () => (hasRejects ? api.decodedRun(run.id, REJECTS, 'REJTRAN') : Promise.resolve(null)), [run.id, hasRejects]);
  const report = useAsync<string>(
    () => (run.files.includes(REPORT) ? api.runFile(run.id, REPORT) : Promise.resolve('')), [run.id]);
  const step15 = run.rc?.POSTTRAN?.steps?.STEP15;
  const recs = rejects.data?.records ?? [];

  return (
    <section className="card" data-testid="posting-panel" style={{ margin: '14px 0' }}>
      <h3 style={{ marginTop: 0 }}>Daily posting (CBTRN02C) and transaction report (CBTRN03C)</h3>
      <p className="small muted">
        POSTTRAN STEP15 RC <b data-testid="posting-step15-rc" className={step15 === 0 ? 'ok' : 'warnc'}>{step15 ?? 'n/a'}</b>
        {' '}· rejects written to DALYREJS(+1): <b data-testid="posting-reject-count">{recs.length}</b>
      </p>
      <h4>Rejected transactions</h4>
      {rejects.error && <p className="error">{rejects.error}</p>}
      {recs.length === 0 ? (
        <p className="muted small" data-testid="posting-no-rejects">No rejects: every daily transaction posted.</p>
      ) : (
        <table className="grid compact" data-testid="posting-rejects">
          <thead>
            <tr><th>Transaction ID</th><th>Card</th><th>Amount</th><th>Reason</th><th>Description</th></tr>
          </thead>
          <tbody>
            {recs.map((r, i) => (
              <tr key={i} data-testid={`posting-reject-${i}`}>
                <td className="mono">{r['DALYTRAN-ID']}</td>
                <td className="mono">{r['DALYTRAN-CARD-NUM']}</td>
                <td className="mono">{r['DALYTRAN-AMT']}</td>
                <td className="mono" data-testid={`posting-reject-reason-${i}`}>{r['REJ-FAIL-REASON']}</td>
                <td>{r['REJ-FAIL-REASON-DESC']}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      <h4>TRANREPT(+1) report listing</h4>
      <pre className="listing" data-testid="posting-report">{report.data || '(empty report)'}</pre>
    </section>
  );
}

const posting: SliceModule = {
  id: 'posting',
  title: 'Posting',
  runPanels: { posting: PostingPanel },
};

export default posting;
