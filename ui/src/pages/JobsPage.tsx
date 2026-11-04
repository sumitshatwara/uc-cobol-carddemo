import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import type { JobInfo } from '../types';

export function JobsPage() {
  const [jobs, setJobs] = useState<JobInfo[]>([]);
  const [selectedCases, setSelectedCases] = useState<Record<string, string>>({});
  const [error, setError] = useState('');
  const [running, setRunning] = useState<string | null>(null);
  const navigate = useNavigate();

  useEffect(() => {
    fetch('/api/jobs')
      .then((response) => {
        if (!response.ok) throw new Error('Could not connect to the Java operations API.');
        return response.json() as Promise<JobInfo[]>;
      })
      .then(setJobs)
      .catch((reason: Error) => setError(reason.message));
  }, []);

  async function run(job: JobInfo) {
    setRunning(job.chain);
    setError('');
    try {
      const response = await fetch('/api/runs', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ chain: job.chain, caseName: selectedCases[job.chain] ?? job.cases[0] }),
      });
      const summary = await response.json();
      if (!response.ok) throw new Error(summary.error ?? 'Run failed');
      navigate(`/runs/${summary.runId}`);
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : 'Run failed');
    } finally {
      setRunning(null);
    }
  }

  return (
    <section>
      <div className="page-heading">
        <div><div className="eyebrow">BATCH WORKSPACE</div><h1>Jobs</h1><p>Run a fixture-backed batch chain and inspect its output.</p></div>
        <div className="live-pill"><span className="status-dot" /> ESTATE ONLINE</div>
      </div>
      {error && <div className="alert-error" role="alert">{error}</div>}
      <div className="section-heading"><div><h2>Available chains</h2><p>JCL workflows registered in the legacy estate</p></div><span className="count-pill">{jobs.length} CHAINS</span></div>
      <div className="table-card">
        <table data-testid="jobs-table">
          <thead><tr><th>CHAIN</th><th>DESCRIPTION</th><th>JCL JOBS</th><th>FIXTURE CASE</th><th>JAVA</th><th /></tr></thead>
          <tbody>
            {jobs.map((job) => (
              <tr key={job.chain} data-testid={`job-row-${job.chain}`}>
                <td><span className="chain-name">{job.chain}</span></td>
                <td className="description-cell">{job.description}</td>
                <td><div className="job-tags">{job.jobs.map((name) => <span key={name}>{name}</span>)}</div></td>
                <td>
                  <select
                    aria-label={`Fixture for ${job.chain}`}
                    data-testid={`case-select-${job.chain}`}
                    value={selectedCases[job.chain] ?? job.cases[0] ?? ''}
                    onChange={(event) => setSelectedCases({ ...selectedCases, [job.chain]: event.target.value })}
                  >
                    {job.cases.map((name) => <option key={name} value={name}>{name}</option>)}
                  </select>
                </td>
                <td><span className={`badge ${job.javaAvailable ? 'badge-green' : 'badge-muted'}`}>
                  <span className="badge-dot" />{job.javaAvailable ? 'AVAILABLE' : 'FOUNDATION'}
                </span></td>
                <td><button
                  className="button-primary button-small"
                  data-testid={`run-button-${job.chain}`}
                  disabled={!job.javaAvailable || running === job.chain}
                  onClick={() => void run(job)}
                >{running === job.chain ? 'Running…' : 'Run chain'} <span>↗</span></button></td>
              </tr>
            ))}
            {jobs.length === 0 && <tr><td colSpan={6} className="loading-row">{error ? 'Jobs unavailable' : 'Loading chains…'}</td></tr>}
          </tbody>
        </table>
      </div>
      <div className="notice-card">
        <span className="notice-icon">i</span>
        <div><strong>Java handlers are ready to plug in</strong><p>Chains become runnable as their Java slice handlers are registered. Fixture cases and legacy metadata are available now.</p></div>
      </div>
    </section>
  );
}
