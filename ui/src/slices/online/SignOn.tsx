import { useState } from 'react';
import type { FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { onlineApi } from './api';
import { Header, Msg } from './common';
import { setSession } from './store';

/** COSGN00C / map COSGN0A: sign on to CardDemo. */
export function SignOn() {
  const nav = useNavigate();
  const [userId, setUserId] = useState('');
  const [password, setPassword] = useState('');
  const [message, setMessage] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const send = async (action: string) => {
    setBusy(true);
    try {
      const r = await onlineApi.signon(action, userId, password);
      setMessage(r.message);
      if (action === 'PF3') setSession(null);
      if (r.next === 'COMEN01C' || r.next === 'COADM01C') {
        setSession({ commarea: r.commarea, next: r.next });
        nav('/online/menu');
        return;
      }
      if (r.cursor === 'PASSWD') document.getElementById('online-signon-password')?.focus();
      else if (r.cursor === 'USERID') document.getElementById('online-signon-userid')?.focus();
    } catch (e) {
      setMessage((e as Error).message);
    } finally {
      setBusy(false);
    }
  };
  const submit = (e: FormEvent) => { e.preventDefault(); void send('ENTER'); };

  return (
    <section>
      <Header tran="CC00" prog="COSGN00C" title="Sign on">
        Type your User ID and Password, then press ENTER. Replaces the 3270 COSGN00 map.
      </Header>
      <form className="online-form" onSubmit={submit}>
        <label>User ID
          <input id="online-signon-userid" data-testid="signon-userid" maxLength={8} autoComplete="off"
            value={userId} onChange={(e) => setUserId(e.target.value)} />
        </label>
        <label>Password
          <input id="online-signon-password" data-testid="signon-password" type="password" maxLength={8} autoComplete="off"
            value={password} onChange={(e) => setPassword(e.target.value)} />
        </label>
        <div className="online-keys">
          <button type="submit" data-testid="signon-enter" disabled={busy}>ENTER</button>
          <button type="button" className="pf" data-testid="signon-pf3" disabled={busy} onClick={() => send('PF3')}>PF3 Exit</button>
        </div>
      </form>
      <Msg testId="signon-message" text={message} color="RED" />
    </section>
  );
}
