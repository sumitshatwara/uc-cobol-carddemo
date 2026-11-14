import { useEffect, useState } from 'react';
import { api, type Decoded, type Run } from '../../lib/api';
import type { SliceModule } from '../types';

// Interest slice (INTCALC / CBACT04C): a run panel that explains, per account and category,
// how the Java job arrived at each interest amount - balance, rate, the exact bal x rate / 1200,
// the truncated amount CBACT04C stores (no ROUNDED), and whether the account was rewritten.

const IN = {
  tcatbal: 'AWS.M2.CARDDEMO.TCATBALF.PS.txt',
  acct: 'AWS.M2.CARDDEMO.ACCTDATA.PS.txt',
  discgrp: 'AWS.M2.CARDDEMO.DISCGRP.PS.txt',
};
const OUT = {
  systran: 'datasets/AWS.M2.CARDDEMO.SYSTRAN.G0001V00.txt',
  acct: 'datasets/AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS.txt',
};

/** Decimal string ("-1235.50") -> integer in units of 10^-scale, exactly (no floating point). */
function scaled(v: string, scale: number): bigint {
  const neg = v.trim().startsWith('-');
  const [i, f = ''] = v.trim().replace(/^[-+]/, '').split('.');
  const n = BigInt((i || '0') + (f + '0'.repeat(scale)).slice(0, scale));
  return neg ? -n : n;
}

function fmt(v: bigint, scale: number): string {
  const neg = v < 0n;
  const s = (neg ? -v : v).toString().padStart(scale + 1, '0');
  return `${neg ? '-' : ''}${s.slice(0, s.length - scale)}.${s.slice(s.length - scale)}`;
}

interface Row {
  acct: string; type: string; cat: string; balance: string; group: string; groupUsed: string; rate: string;
  exact: string; inexact: boolean; truncated: string; subCent: boolean; skipped: boolean;
  javaAmt?: string; tranId?: string; updated: boolean; before: string; after?: string; lastAccount: boolean;
}

function breakdown(tcat: Decoded, acct: Decoded, disc: Decoded, systran: Decoded | undefined, acctOut: Decoded | undefined): Row[] {
  const rates = new Map(disc.records.map((r) => [`${r['DIS-ACCT-GROUP-ID']}|${r['DIS-TRAN-TYPE-CD']}|${Number(r['DIS-TRAN-CAT-CD'])}`, r['DIS-INT-RATE']]));
  const accounts = new Map(acct.records.map((r) => [Number(r['ACCT-ID']), r]));
  const after = new Map((acctOut?.records ?? []).map((r) => [Number(r['ACCT-ID']), r['ACCT-CURR-BAL']]));
  // TCATBALF is a KSDS: CBACT04C reads it in key order (account, type, category)
  const bals = [...tcat.records].sort((a, b) =>
    Number(a['TRANCAT-ACCT-ID']) - Number(b['TRANCAT-ACCT-ID']) || a['TRANCAT-TYPE-CD'].localeCompare(b['TRANCAT-TYPE-CD'])
    || Number(a['TRANCAT-CD']) - Number(b['TRANCAT-CD']));
  const lastAcct = bals.length ? Number(bals[bals.length - 1]['TRANCAT-ACCT-ID']) : -1;
  const trans = systran?.records ?? [];
  let t = 0;
  return bals.map((b) => {
    const id = Number(b['TRANCAT-ACCT-ID']);
    const a = accounts.get(id);
    const group = a?.['ACCT-GROUP-ID'] ?? '?';
    const type = b['TRANCAT-TYPE-CD'];
    const cat = Number(b['TRANCAT-CD']);
    let groupUsed = group;
    let rate = rates.get(`${group}|${type}|${cat}`);
    if (rate === undefined) { groupUsed = 'DEFAULT'; rate = rates.get(`DEFAULT|${type}|${cat}`); }
    const r = rate ?? '0';
    const product = scaled(b['TRAN-CAT-BAL'], 2) * scaled(r, 2); // scale 4
    const exact6 = (product * 100n) / 1200n;                      // scale 6, truncated for display
    const inexact = (product * 100n) % 1200n !== 0n;
    const cents = product / 120000n;                              // BigInt division truncates toward zero
    const skipped = scaled(r, 2) === 0n;
    const tran = skipped ? undefined : trans[t++];
    return {
      acct: String(id), type, cat: String(cat).padStart(4, '0'), balance: b['TRAN-CAT-BAL'], group, groupUsed, rate: r,
      exact: fmt(exact6, 6), inexact, truncated: fmt(cents, 2), subCent: product % 120000n !== 0n, skipped,
      javaAmt: tran?.['TRAN-AMT'], tranId: tran?.['TRAN-ID'],
      updated: id !== lastAcct, before: a?.['ACCT-CURR-BAL'] ?? '?', after: after.get(id), lastAccount: id === lastAcct,
    };
  });
}

function InterestPanel({ run }: { run: Run }) {
  const [rows, setRows] = useState<Row[]>();
  const [error, setError] = useState<string>();
  useEffect(() => {
    const has = (p: string) => run.files.includes(p);
    Promise.all([
      api.decodedCase(run.chain, run.caseName, 'input', IN.tcatbal, 'CVTRA01Y'),
      api.decodedCase(run.chain, run.caseName, 'input', IN.acct, 'CVACT01Y'),
      api.decodedCase(run.chain, run.caseName, 'input', IN.discgrp, 'CVTRA02Y'),
      has(OUT.systran) ? api.decodedRun(run.id, OUT.systran, 'CVTRA05Y') : Promise.resolve(undefined),
      has(OUT.acct) ? api.decodedRun(run.id, OUT.acct, 'CVACT01Y') : Promise.resolve(undefined),
    ]).then(([tc, ac, dg, st, ao]) => setRows(breakdown(tc, ac, dg, st, ao))).catch((e: Error) => setError(e.message));
  }, [run]);

  return (
    <section data-testid="interest-panel" className="interest-panel">
      <h2>Interest breakdown <span className="muted small">CBACT04C · INTCALC STEP15</span></h2>
      <p className="lede small">
        Monthly interest = balance × annual rate / 1200, <b>truncated</b> to cents (the COBOL COMPUTE has no ROUNDED).
        Rows marked <span className="warnc">sub-cent dropped</span> lose a fraction of a cent. The account balance is
        updated at the next account break, so the last account on the balance file is never updated (legacy behaviour).
      </p>
      {error && <p className="error">{error}</p>}
      {!rows && !error && <p className="muted">Loading…</p>}
      {rows && (
        <table className="grid compact" style={{ width: '100%' }} data-testid="interest-table">
          <thead>
            <tr>
              <th>Account</th><th>Type / cat</th><th>Category balance</th><th>Group → rate</th>
              <th>bal × rate / 1200</th><th>Interest (truncated)</th><th>Java SYSTRAN</th><th>Account balance</th><th>Account update</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r) => (
              <tr key={`${r.acct}-${r.type}-${r.cat}`} data-testid={`interest-row-${r.acct}-${r.type}-${r.cat}`}>
                <td className="mono">{r.acct}</td>
                <td className="mono">{r.type} / {r.cat}</td>
                <td className="mono">{r.balance}</td>
                <td className="mono">
                  {r.groupUsed !== r.group ? <>{r.group} <span className="warnc">→ DEFAULT</span></> : r.group} · {r.rate}%
                </td>
                <td className="mono" data-testid="interest-exact">{r.skipped ? '—' : `${r.exact}${r.inexact ? '…' : ''}`}</td>
                <td className="mono" data-testid="interest-truncated">
                  {r.skipped ? <span className="muted">zero rate · skipped</span> : (
                    <>
                      <b className={r.subCent ? 'warnc' : ''}>{r.truncated}</b>
                      {r.subCent && <> <span className="badge warnc" style={{ whiteSpace: 'nowrap' }} data-testid="interest-subcent">sub-cent dropped</span></>}
                    </>
                  )}
                </td>
                <td className="mono" data-testid="interest-java-amt">
                  {r.skipped ? '—' : <><span className={r.javaAmt === r.truncated ? 'ok' : 'bad'}>{r.javaAmt ?? 'missing'}</span> <span className="muted small">{r.tranId}</span></>}
                </td>
                <td className="mono">{r.before} → {r.after ?? '?'}</td>
                <td data-testid="interest-updated">
                  {r.updated
                    ? <span className="ok">updated</span>
                    : <span className="badge warnc" style={{ whiteSpace: 'nowrap' }}>not updated - legacy end-of-file behaviour</span>}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}

const slice: SliceModule = {
  id: 'interest',
  title: 'Interest (INTCALC)',
  runPanels: { interest: InterestPanel },
};

export default slice;
