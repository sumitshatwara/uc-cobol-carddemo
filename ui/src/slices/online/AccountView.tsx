import { useState } from 'react';
import type { FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { onlineApi } from './api';
import type { AccountBlock, AccountViewResponse, CustomerBlock } from './api';
import { FieldRow, Header, Msg } from './common';
import { getSession } from './store';

const ACCOUNT_LABELS: [keyof AccountBlock, string][] = [
  ['status', 'Active Y/N:'],
  ['openDate', 'Opened:'],
  ['creditLimit', 'Credit Limit        :'],
  ['expiryDate', 'Expiry:'],
  ['cashCreditLimit', 'Cash credit Limit   :'],
  ['reissueDate', 'Reissue:'],
  ['currBal', 'Current Balance     :'],
  ['currCycCredit', 'Current Cycle Credit:'],
  ['groupId', 'Account Group:'],
  ['currCycDebit', 'Current Cycle Debit :'],
];

const CUSTOMER_LABELS: [keyof CustomerBlock, string][] = [
  ['custId', 'Customer id  :'],
  ['ssn', 'SSN:'],
  ['dob', 'Date of birth:'],
  ['fico', 'FICO Score:'],
  ['firstName', 'First Name'],
  ['middleName', 'Middle Name:'],
  ['lastName', 'Last Name :'],
  ['addrLine1', 'Address:'],
  ['addrLine2', 'Address (line 2):'],
  ['city', 'City'],
  ['state', 'State'],
  ['zip', 'Zip'],
  ['country', 'Country'],
  ['phone1', 'Phone 1:'],
  ['phone2', 'Phone 2:'],
  ['govtId', 'Government Issued Id Ref    :'],
  ['eftAccountId', 'EFT Account Id:'],
  ['priCardHolder', 'Primary Card Holder Y/N:'],
];

/** COACTVWC / map CACTVWA: view an account and its customer. */
export function AccountView() {
  const nav = useNavigate();
  const initial = getSession()?.commarea?.acctId;
  const [acctId, setAcctId] = useState(initial && Number(initial) > 0 ? String(initial).padStart(11, '0') : '');
  const [r, setR] = useState<AccountViewResponse | null>(null);
  const [error, setError] = useState<string>();
  const [busy, setBusy] = useState(false);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(undefined);
    try { setR(await onlineApi.account(acctId)); } catch (err) { setError((err as Error).message); } finally { setBusy(false); }
  };

  return (
    <section>
      <Header tran="CAVW" prog="COACTVWC" title="View Account">Enter an 11-digit account number and press ENTER.</Header>
      <form className="online-form" onSubmit={submit}>
        <label>Account Number :
          <input data-testid="view-acctid" maxLength={11} size={13} value={acctId} onChange={(e) => setAcctId(e.target.value)} />
        </label>
        <div className="online-keys">
          <button type="submit" data-testid="view-enter" disabled={busy}>ENTER</button>
          <button type="button" className="pf" data-testid="view-pf3" onClick={() => nav('/online/menu')}>PF3 Exit</button>
        </div>
      </form>
      {error && <p className="error">{error}</p>}
      <Msg testId="view-error" text={r?.errorMessage} color="RED" />
      <Msg testId="view-info" text={r?.infoMessage} />
      {r?.account && (
        <>
          <h2>Account <span className="mono">{r.acctIdField}</span></h2>
          <div className="online-fields" data-testid="view-account">
            {ACCOUNT_LABELS.map(([k, l]) => <FieldRow key={k} label={l} value={r.account![k]} testId={`view-field-${k}`} />)}
          </div>
        </>
      )}
      {r?.customer && (
        <>
          <h2>Customer Details</h2>
          <div className="online-fields" data-testid="view-customer">
            {CUSTOMER_LABELS.map(([k, l]) => <FieldRow key={k} label={l} value={r.customer![k]} testId={`view-field-${k}`} />)}
          </div>
        </>
      )}
    </section>
  );
}
