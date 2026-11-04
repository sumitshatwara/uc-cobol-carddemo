import { useEffect, useState } from 'react';
import ReactMarkdown from 'react-markdown';

export function ParityPage() {
  const [report, setReport] = useState('');
  useEffect(() => {
    fetch('/api/parity/report').then(async (response) => {
      if (response.ok) setReport(await response.text());
      else setReport('# No Java parity report yet\n\nRun `make parity-java` to generate a report.');
    }).catch(() => setReport('# No Java parity report yet'));
  }, []);
  return <section><div className="page-heading"><div><div className="eyebrow">REGRESSION ANALYSIS</div><h1>Parity report</h1><p>Field-level comparison against the legacy fixture baselines.</p></div></div>
    <article className="parity-card markdown-body"><ReactMarkdown>{report}</ReactMarkdown></article>
  </section>;
}
