import { useEffect, useState } from 'react';
import { api } from '../lib/api';
import { Markdown } from '../lib/Markdown';
import { useAsync } from '../lib/useAsync';

/** Parity reports from `make parity` (legacy rerun) and `make parity-java` (Java vs golden). */
export function ParityPage() {
  const { data, error } = useAsync(api.reports, []);
  const [sel, setSel] = useState<string>();
  const [md, setMd] = useState('');
  useEffect(() => {
    if (!sel && data?.length) setSel(data.find((r) => r.name === 'report-java.md')?.name ?? data[0].name);
  }, [data, sel]);
  useEffect(() => {
    if (sel) api.report(sel).then(setMd).catch((e: Error) => setMd(e.message));
  }, [sel]);
  return (
    <section>
      <h1>Parity reports</h1>
      <p className="lede">Field-level comparison through the copybooks (COMP-3, zoned overpunch, EBCDIC), SYSOUT, return codes and table snapshots, by <span className="mono">tools/parity/compare.py</span>.</p>
      {error && <p className="error">{error}</p>}
      {data?.length === 0 && <p className="muted">No reports yet. Run <span className="mono">make parity</span> or <span className="mono">make parity-java</span>.</p>}
      <div className="tabs">
        {data?.map((r) => (
          <button key={r.name} className={r.name === sel ? 'active' : ''} data-testid={`report-${r.name}`} onClick={() => setSel(r.name)}>{r.name}</button>
        ))}
      </div>
      {md && <Markdown source={md} testId="parity-markdown" />}
    </section>
  );
}
