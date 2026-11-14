import type { SliceModule } from './types';

const modules = import.meta.glob<{ default: SliceModule }>('./*/index.tsx', { eager: true });

/** Every slice module found under ui/src/slices/<id>/index.tsx, sorted by id. */
export const slices: SliceModule[] = Object.values(modules)
  .map((m) => m.default)
  .sort((a, b) => a.id.localeCompare(b.id));
