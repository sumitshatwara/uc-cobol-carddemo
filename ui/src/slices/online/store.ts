import type { Commarea } from './api';

// Tiny session store for the CICS COMMAREA carried between screens (module state + sessionStorage).

export interface Session { commarea: Commarea | null; next: string | null }

const KEY = 'carddemo.online.session';
let current: Session | null = null;

export function getSession(): Session | null {
  if (current) return current;
  try {
    const raw = sessionStorage.getItem(KEY);
    if (raw) current = JSON.parse(raw) as Session;
  } catch { /* storage unavailable */ }
  return current;
}

export function setSession(s: Session | null): void {
  current = s;
  try {
    if (s) sessionStorage.setItem(KEY, JSON.stringify(s));
    else sessionStorage.removeItem(KEY);
  } catch { /* storage unavailable */ }
}

export function updateCommarea(c: Commarea | null | undefined): void {
  if (!c) return;
  const s = getSession();
  setSession({ commarea: c, next: s?.next ?? null });
}

export const userType = (): string => getSession()?.commarea?.userType?.trim() || 'U';
