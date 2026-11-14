import { useState } from 'react';
import type { FormEvent } from 'react';
import { onlineApi } from './api';
import type { DateResponse } from './api';
import { Header } from './common';

/** HLASM COBDATFT date reformatting (CODATECN-REC). */
export function Dates() {
  const [inType, setInType] = useState('1');
  const [inDate, setInDate] = useState('20250315');
  const [outType, setOutType] = useState('1');
  const [r, setR] = useState<DateResponse | null>(null);
  const [error, setError] = useState<string>();
  const [busy, setBusy] = useState(false);

  const convert = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(undefined);
    try { setR(await onlineApi.convertDate({ inType, inDate, outType })); } catch (err) { setError((err as Error).message); } finally { setBusy(false); }
  };

  return (
    <section>
      <Header tran="CBACT01C" prog="COBDATFT" title="Date converter">
        HLASM COBDATFT: type 1 = YYYYMMDD, type 2 = YYYY-MM-DD. A type/format mismatch sets INVALID INPUT in the message field.
      </Header>
      <form className="online-form" onSubmit={convert}>
        <label>Input type (COINTYPE)
          <select data-testid="date-intype" value={inType} onChange={(e) => setInType(e.target.value)}>
            <option value="1">1 = YYYYMMDD</option><option value="2">2 = YYYY-MM-DD</option>
          </select>
        </label>
        <label>Input date (COINPDT)
          <input data-testid="date-indate" maxLength={10} size={12} value={inDate} onChange={(e) => setInDate(e.target.value)} />
        </label>
        <label>Output type (COOUTYPE)
          <select data-testid="date-outtype" value={outType} onChange={(e) => setOutType(e.target.value)}>
            <option value="1">1</option><option value="2">2</option>
          </select>
        </label>
        <div className="online-keys"><button type="submit" data-testid="date-convert" disabled={busy}>Convert</button></div>
      </form>
      {error && <p className="error">{error}</p>}
      {r && (
        <div className="summary" data-testid="date-result">
          <div><div className="muted small">COOUTDT</div><b className="mono" data-testid="date-out">{r.outDate}</b></div>
          <div><div className="muted small">COERMSG</div><b className="mono bad" style={{ whiteSpace: 'pre-wrap' }} data-testid="date-error">{r.errorMsg}</b></div>
          <div><div className="muted small">Return code</div><b className="mono" data-testid="date-rc">{r.returnCode}</b></div>
        </div>
      )}
    </section>
  );
}
