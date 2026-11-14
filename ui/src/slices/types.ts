import type { ComponentType, ReactElement } from 'react';
import type { Run } from '../lib/api';

/**
 * Extension point for slice screens.  A slice adds ui/src/slices/<id>/index.tsx whose default
 * export is a SliceModule; the shell discovers it at build time (import.meta.glob) so slices
 * never edit the shell.  Routes live under /<path> inside the console's hash router.
 */
export interface SliceModule {
  id: string;
  title: string;
  /** Entries for the left navigation. */
  nav?: { label: string; path: string; testId?: string }[];
  /** Extra screens, e.g. { path: '/online/signon', element: <SignOn/> }. */
  routes?: { path: string; element: ReactElement }[];
  /** A panel shown on the run screen for runs of the given chain (e.g. an interest breakdown). */
  runPanels?: Record<string, ComponentType<{ run: Run }>>;
}
