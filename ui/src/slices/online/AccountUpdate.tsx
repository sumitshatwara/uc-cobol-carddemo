import { useState } from 'react';
import type { FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { onlineApi } from './api';
import type { UpdateFields, UpdateResponse } from './api';
import { Header, Msg, routeFor } from './common';
import { getSession, updateCommarea } from './store';

/** Editable fields of map CACTUPA in screen order, with their legacy labels. */
const FIELDS: [string, string][] = [
  ['acctStatus', 'Active Y/N'],
  ['openYear', 'Opened (yyyy)'], ['openMon', 'Opened (mm)'], ['openDay', 'Opened (dd)'],
  ['creditLimit', 'Credit Limit'],
  ['expYear', 'Expiry (yyyy)'], ['expMon', 'Expiry (mm)'], ['expDay', 'Expiry (dd)'],
  ['cashCreditLimit', 'Cash credit Limit'],
  ['reissueYear', 'Reissue (yyyy)'], ['reissueMon', 'Reissue (mm)'], ['reissueDay', 'Reissue (dd)'],
  ['currBal', 'Current Balance'], ['currCycCredit', 'Current Cycle Credit'], ['currCycDebit', 'Current Cycle Debit'],
  ['groupId', 'Account Group'],
  ['custId', 'Customer id'],
  ['ssn1', 'SSN (999)'], ['ssn2', 'SSN (99)'], ['ssn3', 'SSN (9999)'],
  ['dobYear', 'Date of birth (yyyy)'], ['dobMon', 'Date of birth (mm)'], ['dobDay', 'Date of birth (dd)'],
  ['fico', 'FICO Score'],
  ['firstName', 'First Name'], ['middleName', 'Middle Name'], ['lastName', 'Last Name'],
  ['addrLine1', 'Address'], ['addrLine2', 'Address (line 2)'], ['city', 'City'],
  ['state', 'State'], ['zip', 'Zip'], ['country', 'Country'],
  ['phone1a', 'Phone 1 (area)'], ['phone1b', 'Phone 1 (prefix)'], ['phone1c', 'Phone 1 (line)'],
  ['phone2a', 'Phone 2 (area)'], ['phone2b', 'Phone 2 (prefix)'], ['phone2c', 'Phone 2 (line)'],
  ['govtId', 'Government Issued Id Ref'], ['eftAccountId', 'EFT Account Id'], ['priCardHolder', 'Primary Card Holder Y/N'],
];

/** COACTUPC / map CACTUPA: fetch, edit, confirm (PF5) or cancel (PF12) an account + customer update. */
export function AccountUpdate() {
  const nav = useNavigate();
  const [acctId, setAcctId] = useState('');
  const [state, setState] = useState(' ');
  const [snapshot, setSnapshot] = useState<unknown>(null);
  const [fields, setFields] = useState<UpdateFields | null>(null);
  const [r, setR] = useState<UpdateResponse | null>(null);
  const [error, setError] = useState<string>();
  const [busy, setBusy] = useState(false);

  const send = async (action: string) => {
    setBusy(true);
    setError(undefined);
    const body = { action, state, snapshot, fields, commarea: getSession()?.commarea ?? null };
    try {
      const res = action === 'PF5' ? await onlineApi.accountSave(acctId, body) : await onlineApi.accountChanges(acctId, body);
      setR(res);
      updateCommarea(res.commarea);
      setState(res.state ?? ' ');
      setSnapshot(res.snapshot ?? null);
      setFields(res.fields ? { ...res.fields } : null);
      const to = routeFor(res.next);
      if (to && to !== '/online/accounts/update') { nav(to); return; }
      if (action === 'PF3' && !res.next) nav('/online/menu');
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };
  const submit = (e: FormEvent) => { e.preventDefault(); void send('ENTER'); };
  const setField = (k: string, v: string) => setFields((f) => ({ ...(f ?? {}), [k]: v }));

  return (
    <section>
      <Header tran="CAUP" prog="COACTUPC" title="Update Account">
        Enter an account number and press ENTER to fetch it, change fields and press ENTER to validate, then PF5 to save or PF12 to cancel.
      </Header>
      <form className="online-form" onSubmit={submit}>
        <label>Account Number :
          <input data-testid="upd-acctid" maxLength={11} size={13} value={acctId} onChange={(e) => setAcctId(e.target.value)} />
        </label>
        <div className="online-keys">
          <button type="submit" data-testid="upd-enter" disabled={busy}>ENTER Process</button>
          <button type="button" data-testid="upd-pf5" disabled={busy || !r?.showSaveKeys} onClick={() => send('PF5')}>PF5 Save</button>
          <button type="button" className="pf" data-testid="upd-pf12" disabled={busy} onClick={() => send('PF12')}>PF12 Cancel</button>
          <button type="button" className="pf" data-testid="upd-pf3" disabled={busy} onClick={() => send('PF3')}>PF3 Exit</button>
        </div>
        <span className="muted small mono" data-testid="upd-state">state '{state}'</span>
      </form>
      {error && <p className="error">{error}</p>}
      <Msg testId="upd-error" text={r?.errorMessage} color="RED" />
      <Msg testId="upd-info" text={r?.infoMessage} />
      {r?.abend && (
        <p className="error" data-testid="upd-abend">ABEND {r.abend.code} in {r.abend.culprit}: {r.abend.message}</p>
      )}
      {fields && (
        <div className="online-upd" data-testid="upd-fields">
          {FIELDS.filter(([k]) => k in fields).concat(Object.keys(fields).filter((k) => !FIELDS.some(([f]) => f === k)).map((k) => [k, k]))
            .map(([k, label]) => (
              <label key={k}>{label}
                <input data-testid={`upd-field-${k}`} value={String(fields[k] ?? '')} onChange={(e) => setField(k, e.target.value)} />
              </label>
            ))}
        </div>
      )}
    </section>
  );
}
