import type { ReactNode } from 'react';

/** Map a menu/screen program name to the console route that renders it (null when not in this slice). */
export function routeFor(program: string | null | undefined): string | null {
  switch ((program ?? '').trim()) {
    case 'COSGN00C': return '/online/signon';
    case 'COMEN01C':
    case 'COADM01C': return '/online/menu';
    case 'COACTVWC': return '/online/accounts';
    case 'COACTUPC': return '/online/accounts/update';
    default: return null;
  }
}

/** A BMS message line shown verbatim (double spaces preserved). */
export function Msg({ text, color, testId }: { text: string | null | undefined; color?: string | null; testId: string }) {
  const cls = color === 'RED' ? 'bad' : color === 'GREEN' ? 'ok' : '';
  return <div className={`online-msg ${cls}`} data-testid={testId}>{text ?? ''}</div>;
}

export function Header({ tran, prog, title, children }: { tran: string; prog: string; title: string; children?: ReactNode }) {
  return (
    <>
      <p className="crumbs">Online (CICS) / <span className="mono">{tran}</span> · <span className="mono">{prog}</span></p>
      <h1>{title}</h1>
      {children && <p className="lede">{children}</p>}
    </>
  );
}

export function FieldRow({ label, value, testId }: { label: string; value: ReactNode; testId: string }) {
  return (
    <div className="row"><span>{label}</span><span data-testid={testId}>{value}</span></div>
  );
}
