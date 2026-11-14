// Typed client for the Spring Boot job-launch API (java/app, /api/*).

export interface JclStep { step: string; program: string }
export interface JclJob { job: string; path: string | null; steps: JclStep[] }
export interface CaseInfo { name: string; readme: string }
export interface JobInfo {
  chain: string;
  description: string;
  clock: string;
  jobs: JclJob[];
  setup: string[];
  inputs: Record<string, number>;
  outputs: Record<string, string>;
  sysout: string[];
  tables: string[];
  implemented: boolean;
  caseCount: number;
  cases?: CaseInfo[];
}
export interface RcJob { maxrc: number; steps: Record<string, number> }
export interface Parity { passed: boolean; summary: string; report: string }
export interface Run {
  id: string;
  chain: string;
  caseName: string;
  startedAt: string;
  durationMs: number;
  maxRc: number;
  error?: string;
  rc?: Record<string, RcJob>;
  files: string[];
  outDir: string;
  parity?: Parity;
}
export interface Decoded { layout: string; fields: string[]; records: Record<string, string>[] }
export interface ReportInfo { name: string; modified: string; size: number }

async function json<T>(res: Response): Promise<T> {
  if (!res.ok) {
    let msg = `${res.status} ${res.statusText}`;
    try { msg = (await res.json()).error ?? msg; } catch { /* not json */ }
    throw new Error(msg);
  }
  return res.json() as Promise<T>;
}

async function text(res: Response): Promise<string> {
  if (!res.ok) throw new Error(`${res.status} ${res.statusText}`);
  return res.text();
}

const q = encodeURIComponent;

export const api = {
  health: () => fetch('/api/health').then(json<{ status: string; implementedChains: string[] }>),
  jobs: () => fetch('/api/jobs').then(json<JobInfo[]>),
  job: (chain: string) => fetch(`/api/jobs/${q(chain)}`).then(json<JobInfo>),
  caseFiles: (chain: string, c: string, kind: 'input' | 'expected') =>
    fetch(`/api/jobs/${q(chain)}/cases/${q(c)}/files?kind=${kind}`).then(json<string[]>),
  caseFile: (chain: string, c: string, kind: 'input' | 'expected', path: string) =>
    fetch(`/api/jobs/${q(chain)}/cases/${q(c)}/file?kind=${kind}&path=${q(path)}`).then(text),
  submit: (chain: string, caseName: string) =>
    fetch('/api/runs', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ chain, caseName }) }).then(json<Run>),
  runs: () => fetch('/api/runs').then(json<Run[]>),
  run: (id: string) => fetch(`/api/runs/${q(id)}`).then(json<Run>),
  runFile: (id: string, path: string) => fetch(`/api/runs/${q(id)}/file?path=${q(path)}`).then(text),
  decodedRun: (id: string, path: string, layout: string) =>
    fetch(`/api/runs/${q(id)}/decoded?path=${q(path)}&layout=${q(layout)}`).then(json<Decoded>),
  decodedCase: (chain: string, c: string, kind: 'input' | 'expected', path: string, layout: string) =>
    fetch(`/api/jobs/${q(chain)}/cases/${q(c)}/decoded?kind=${kind}&path=${q(path)}&layout=${q(layout)}`).then(json<Decoded>),
  reports: () => fetch('/api/parity/reports').then(json<ReportInfo[]>),
  report: (name: string) => fetch(`/api/parity/reports/${q(name)}`).then(text),
};
