import type { ReactNode } from 'react';

export type JobInfo = {
  chain: string;
  description: string;
  jobs: string[];
  setup: string[];
  cases: string[];
  javaAvailable: boolean;
};

export type RunSummary = {
  runId: string;
  chain: string;
  caseName: string;
  status: string;
  maxRc: number;
  rc: Record<string, { maxrc: number; steps: Record<string, number> }>;
  startedAt: string;
  durationMs: number;
  files: string[];
};

export type SliceModule = {
  id: string;
  title: string;
  navLabel: string;
  routes: { path: string; element: ReactNode }[];
};
