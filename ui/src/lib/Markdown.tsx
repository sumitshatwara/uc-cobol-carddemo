import { marked } from 'marked';
import { useMemo } from 'react';

/** Renders trusted Markdown produced by this repository's own tools (parity reports, READMEs). */
export function Markdown({ source, testId }: { source: string; testId?: string }) {
  const html = useMemo(() => marked.parse(source, { async: false }) as string, [source]);
  return <div className="markdown" data-testid={testId} dangerouslySetInnerHTML={{ __html: html }} />;
}
