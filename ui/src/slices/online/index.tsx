import { useState, type CSSProperties, type FormEvent, type ReactNode } from 'react';
import type { SliceModule } from '../../types';

type Header = { title01: string; title02: string; tranId: string; pgmName: string; curDate: string; curTime: string };
type SignOnResponse = { header?: Header; errorMessage?: string; nextProgram?: string | null; commarea?: { userId?: string; userType?: string } };
type AccountViewResponse = {
  header?: Header;
  errorMessage?: string;
  infoMessage?: string;
  acctIdError?: boolean;
  account?: Record<string, string> | null;
  customer?: Record<string, string> | null;
};
type FeeResponse = { feeAmt: string; avgDaily: string; rc: number; sysprint?: string[] };

const inputStyle: CSSProperties = {
  border: '1px solid #344550', borderRadius: 5, color: '#c9d4da', background: '#1b2933',
  padding: '8px 9px', fontSize: 11, fontFamily: 'ui-monospace, monospace', minWidth: 190,
};
const formStyle: CSSProperties = { display: 'flex', flexWrap: 'wrap', gap: 14, alignItems: 'flex-end', padding: 16 };
const labelStyle: CSSProperties = { display: 'flex', flexDirection: 'column', gap: 6, fontSize: 9, color: '#95a8b3', letterSpacing: '.45px', fontWeight: 700 };
const infoStyle: CSSProperties = { border: '1px solid #2c4548', borderRadius: 6, margin: '0 0 19px', padding: '10px 12px', color: '#a6d8d0', background: '#173033', fontSize: 11 };

async function postJson<T>(url: string, body: unknown): Promise<T> {
  const response = await fetch(url, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
  if (!response.ok) throw new Error(`HTTP ${response.status}`);
  return response.json() as Promise<T>;
}

function Heading({ eyebrow, title, text }: { eyebrow: string; title: string; text: string }) {
  return <div className="page-heading"><div><div className="eyebrow">{eyebrow}</div><h1>{title}</h1><p>{text}</p></div></div>;
}

function Field({ label, children }: { label: string; children: ReactNode }) {
  return <label style={labelStyle}>{label}{children}</label>;
}

function Messages({ error, info, testId }: { error?: string; info?: string; testId: string }) {
  return <>
    {error ? <div className="alert-error" style={{ margin: '0 0 19px' }} data-testid={`${testId}-error`}>{error}</div> : null}
    {info ? <div style={infoStyle} data-testid={`${testId}-info`}>{info}</div> : null}
  </>;
}

function ValueTable({ rows, testId }: { rows: [string, string | undefined][]; testId: string }) {
  return <table className="decoded-table" data-testid={testId}><tbody>
    {rows.map(([name, value]) => <tr key={name}><th>{name}</th><td data-testid={`${testId}-${name}`}>{value ?? ''}</td></tr>)}
  </tbody></table>;
}

function SignOnPage() {
  const [userId, setUserId] = useState('');
  const [password, setPassword] = useState('');
  const [result, setResult] = useState<SignOnResponse | null>(null);
  const [failure, setFailure] = useState('');

  async function submit(event: FormEvent) {
    event.preventDefault();
    setFailure('');
    try {
      setResult(await postJson<SignOnResponse>('/api/online/signon', { aid: 'ENTER', userId, password }));
    } catch (error) {
      setResult(null);
      setFailure(String(error));
    }
  }

  return <section>
    <Heading eyebrow="ONLINE · COSGN00C" title="Sign-on" text="CardDemo sign-on screen (CC00) converted from CICS: user lookup on the security file and menu routing." />
    <Messages error={result?.errorMessage || failure} testId="signon" />
    <div className="table-card">
      <form style={formStyle} onSubmit={submit} data-testid="signon-form">
        <Field label="USER ID"><input style={inputStyle} maxLength={8} value={userId} onChange={(e) => setUserId(e.target.value)} data-testid="signon-userid" /></Field>
        <Field label="PASSWORD"><input style={inputStyle} type="password" maxLength={8} value={password} onChange={(e) => setPassword(e.target.value)} data-testid="signon-password" /></Field>
        <button className="button-primary" type="submit" data-testid="signon-submit">ENTER · Sign on</button>
      </form>
    </div>
    {result?.nextProgram ? <div className="output-card" style={{ marginTop: 19 }} data-testid="signon-result">
      <h3>Signed on</h3>
      <ValueTable testId="signon-commarea" rows={[
        ['nextProgram', result.nextProgram],
        ['userId', result.commarea?.userId],
        ['userType', result.commarea?.userType],
      ]} />
    </div> : null}
  </section>;
}

const accountFields = ['acctId', 'activeStatus', 'currBal', 'creditLimit', 'cashCreditLimit', 'currCycCredit', 'currCycDebit', 'openDate', 'expirationDate', 'reissueDate', 'groupId'];
const customerFields = ['custId', 'ssn', 'firstName', 'middleName', 'lastName', 'addrLine1', 'addrLine2', 'addrLine3', 'stateCd', 'zip', 'countryCd', 'phone1', 'phone2', 'govtId', 'dob', 'eftAccountId', 'priCardHolder', 'ficoScore'];

function AccountViewPage() {
  const [acctId, setAcctId] = useState('');
  const [result, setResult] = useState<AccountViewResponse | null>(null);
  const [failure, setFailure] = useState('');

  async function submit(event: FormEvent) {
    event.preventDefault();
    setFailure('');
    try {
      const response = await fetch(`/api/online/accounts?acctId=${encodeURIComponent(acctId)}`);
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      setResult(await response.json() as AccountViewResponse);
    } catch (error) {
      setResult(null);
      setFailure(String(error));
    }
  }

  return <section>
    <Heading eyebrow="ONLINE · COACTVWC" title="Account view" text="Account inquiry (CAVW) converted from CICS: card cross-reference, account and customer lookup." />
    <Messages error={result?.errorMessage || failure} info={result?.infoMessage} testId="account" />
    <div className="table-card">
      <form style={formStyle} onSubmit={submit} data-testid="account-form">
        <Field label="ACCOUNT NUMBER"><input style={inputStyle} maxLength={11} value={acctId} onChange={(e) => setAcctId(e.target.value)} data-testid="account-id" /></Field>
        <button className="button-primary" type="submit" data-testid="account-submit">ENTER · View</button>
      </form>
    </div>
    {result?.account || result?.customer ? <div className="output-grid" style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16, marginTop: 19 }}>
      <div className="output-card" data-testid="account-details"><h3>Account</h3>
        <ValueTable testId="account-field" rows={accountFields.map((name) => [name, result.account?.[name]])} />
      </div>
      <div className="output-card" data-testid="customer-details"><h3>Customer</h3>
        <ValueTable testId="customer-field" rows={customerFields.map((name) => [name, result.customer?.[name]])} />
      </div>
    </div> : null}
  </section>;
}

function LateFeePage() {
  const [form, setForm] = useState({ acctId: '00000000001', groupId: 'STANDARD', cycleBal: '1234.56', lateDays: '10', cycleDays: '30' });
  const [result, setResult] = useState<FeeResponse | null>(null);
  const [failure, setFailure] = useState('');
  const update = (name: keyof typeof form) => (event: { target: { value: string } }) => setForm({ ...form, [name]: event.target.value });

  async function submit(event: FormEvent) {
    event.preventDefault();
    setFailure('');
    try {
      setResult(await postJson<FeeResponse>('/api/online/fees', {
        acctId: form.acctId, groupId: form.groupId, cycleBal: form.cycleBal,
        lateDays: Number(form.lateDays), cycleDays: Number(form.cycleDays),
      }));
    } catch (error) {
      setResult(null);
      setFailure(String(error));
    }
  }

  return <section>
    <Heading eyebrow="ONLINE · CBFEECAL" title="Late-fee calculator" text="PL/I late-fee rule converted to Java: 2.5% of the cycle balance, truncated, floor 5.00, cap 35.00, GOLD waiver." />
    <Messages error={failure} testId="fee" />
    <div className="table-card">
      <form style={formStyle} onSubmit={submit} data-testid="fee-form">
        <Field label="ACCOUNT"><input style={inputStyle} maxLength={11} value={form.acctId} onChange={update('acctId')} data-testid="fee-acct-id" /></Field>
        <Field label="GROUP"><input style={inputStyle} maxLength={10} value={form.groupId} onChange={update('groupId')} data-testid="fee-group-id" /></Field>
        <Field label="CYCLE BALANCE"><input style={inputStyle} value={form.cycleBal} onChange={update('cycleBal')} data-testid="fee-cycle-bal" /></Field>
        <Field label="LATE DAYS"><input style={inputStyle} value={form.lateDays} onChange={update('lateDays')} data-testid="fee-late-days" /></Field>
        <Field label="CYCLE DAYS"><input style={inputStyle} value={form.cycleDays} onChange={update('cycleDays')} data-testid="fee-cycle-days" /></Field>
        <button className="button-primary" type="submit" data-testid="fee-submit">Calculate</button>
      </form>
    </div>
    {result ? <div className="output-card" style={{ marginTop: 19 }} data-testid="fee-result">
      <h3>Result</h3>
      <ValueTable testId="fee-out" rows={[['feeAmt', result.feeAmt], ['avgDaily', result.avgDaily], ['rc', String(result.rc)]]} />
      {result.sysprint?.length ? <pre data-testid="fee-sysprint" style={{ fontSize: 10, color: '#c9d4da', whiteSpace: 'pre' }}>{result.sysprint.join('\n')}</pre> : null}
    </div> : null}
  </section>;
}

export const slice: SliceModule = {
  id: 'online',
  title: 'Online (CICS)',
  navLabel: 'Online',
  routes: [
    { path: '/online/signon', element: <SignOnPage /> },
    { path: '/online/accounts', element: <AccountViewPage /> },
    { path: '/online/fees', element: <LateFeePage /> },
  ],
};
