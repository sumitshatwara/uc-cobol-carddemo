import { useState } from 'react';
import type { FormEvent } from 'react';
import { onlineApi } from './api';
import type { FeeResponse } from './api';
import { Header } from './common';

interface Example { n: number; groupId: string; cycleBal: string; lateDays: number; cycleDays: number; fee: string; avg: string; rc: number; why: string }

/** Worked examples documented in docs/LEGACY-LANGUAGES.md (CBFEECAL). */
export const FEE_EXAMPLES: Example[] = [
  { n: 1, groupId: 'STANDARD', cycleBal: '1234.56', lateDays: 10, cycleDays: 30, fee: '30.86', avg: '41.15', rc: 0, why: '30.864 truncated, not 30.87' },
  { n: 2, groupId: 'STANDARD', cycleBal: '100.00', lateDays: 10, cycleDays: 30, fee: '5.00', avg: '3.33', rc: 0, why: '2.50 lifted to the 5.00 floor' },
  { n: 3, groupId: 'STANDARD', cycleBal: '5000.00', lateDays: 10, cycleDays: 30, fee: '35.00', avg: '166.66', rc: 0, why: '125.00 capped at 35.00' },
  { n: 4, groupId: 'GOLD', cycleBal: '80.00', lateDays: 10, cycleDays: 30, fee: '0.00', avg: '2.66', rc: 4, why: 'waived: GOLD below 100.00' },
  { n: 5, groupId: 'STANDARD', cycleBal: '1000.00', lateDays: 10, cycleDays: 0, fee: '0.00', avg: '0.00', rc: 8, why: 'ZERODIVIDE in the average-daily division' },
  { n: 6, groupId: 'GOLD', cycleBal: '0.00', lateDays: 0, cycleDays: 30, fee: '0.00', avg: '0.00', rc: 0, why: 'nothing overdue' },
];

/** PL/I CBFEECAL late-fee calculation, as called by CBFEEDRV. */
export function Fees() {
  const [acctId, setAcctId] = useState('00000000099');
  const [groupId, setGroupId] = useState('STANDARD');
  const [cycleBal, setCycleBal] = useState('1234.56');
  const [lateDays, setLateDays] = useState('10');
  const [cycleDays, setCycleDays] = useState('30');
  const [r, setR] = useState<FeeResponse | null>(null);
  const [error, setError] = useState<string>();
  const [busy, setBusy] = useState(false);

  const calc = async (e?: FormEvent) => {
    e?.preventDefault();
    setBusy(true);
    setError(undefined);
    try {
      setR(await onlineApi.fees({ acctId, groupId, cycleBal, lateDays: Number(lateDays), cycleDays: Number(cycleDays) }));
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setBusy(false);
    }
  };
  const fill = (x: Example) => {
    setGroupId(x.groupId); setCycleBal(x.cycleBal); setLateDays(String(x.lateDays)); setCycleDays(String(x.cycleDays)); setR(null);
  };

  return (
    <section>
      <Header tran="FEECALC" prog="CBFEECAL" title="Late-fee calculator">
        PL/I CBFEECAL: fee = cycle balance × 2.5 % truncated to cents, floor 5.00, cap 35.00; GOLD below 100.00 is waived (RC 4);
        0 cycle days raises ZERODIVIDE (RC 8).
      </Header>
      <form className="online-form" onSubmit={calc}>
        <label>Account id<input data-testid="fee-acctid" maxLength={11} size={13} value={acctId} onChange={(e) => setAcctId(e.target.value)} /></label>
        <label>Group id<input data-testid="fee-groupid" maxLength={10} size={12} value={groupId} onChange={(e) => setGroupId(e.target.value)} /></label>
        <label>Cycle balance<input data-testid="fee-cyclebal" size={12} value={cycleBal} onChange={(e) => setCycleBal(e.target.value)} /></label>
        <label>Late days<input data-testid="fee-latedays" size={5} value={lateDays} onChange={(e) => setLateDays(e.target.value)} /></label>
        <label>Cycle days<input data-testid="fee-cycledays" size={5} value={cycleDays} onChange={(e) => setCycleDays(e.target.value)} /></label>
        <div className="online-keys"><button type="submit" data-testid="fee-calc" disabled={busy}>Calculate</button></div>
      </form>
      {error && <p className="error" data-testid="fee-error">{error}</p>}
      {r && (
        <>
          <div className="summary" data-testid="fee-result">
            <div><div className="muted small">FEE_AMT</div><b className="mono" data-testid="fee-amt">{r.feeAmt}</b></div>
            <div><div className="muted small">FEE_AVG_DAILY</div><b className="mono" data-testid="fee-avg">{r.avgDaily}</b></div>
            <div><div className="muted small">FEE_RC</div><b className={`mono ${r.rc === 0 ? 'ok' : r.rc === 4 ? 'warnc' : 'bad'}`} data-testid="fee-rc">{r.rc}</b></div>
          </div>
          <h4>SYSPRINT</h4>
          <pre className="listing" data-testid="fee-sysprint">{r.sysprint.length ? r.sysprint.join('\n') : '(empty)'}</pre>
          <h4>Fee line (80 bytes)</h4>
          <pre className="listing" data-testid="fee-line">{r.feeLine}</pre>
        </>
      )}
      <h2>Documented worked examples</h2>
      <table className="grid" data-testid="fee-examples">
        <thead><tr><th>#</th><th>Group</th><th>Cycle bal</th><th>Late days</th><th>Cycle days</th><th>Fee</th><th>Avg daily</th><th>RC</th><th>Why</th><th /></tr></thead>
        <tbody>
          {FEE_EXAMPLES.map((x) => (
            <tr key={x.n} data-testid={`fee-example-row-${x.n}`}>
              <td>{x.n}</td><td className="mono">{x.groupId}</td><td className="mono">{x.cycleBal}</td><td className="mono">{x.lateDays}</td>
              <td className="mono">{x.cycleDays}</td><td className="mono strong">{x.fee}</td><td className="mono">{x.avg}</td><td className="mono">{x.rc}</td>
              <td className="small">{x.why}</td>
              <td><button type="button" data-testid={`fee-example-${x.n}`} onClick={() => fill(x)}>Use</button></td>
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  );
}
