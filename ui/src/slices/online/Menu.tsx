import { useEffect, useState } from 'react';
import type { FormEvent } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { onlineApi } from './api';
import type { MenuOption, MenuSelectResponse } from './api';
import { Header, Msg, routeFor } from './common';
import { getSession, setSession, updateCommarea, userType } from './store';

/** COMEN01C / map COMEN1A: main menu. */
export function Menu() {
  const nav = useNavigate();
  const session = getSession();
  const [options, setOptions] = useState<MenuOption[]>([]);
  const [loadError, setLoadError] = useState<string>();
  const [option, setOption] = useState('');
  const [result, setResult] = useState<MenuSelectResponse | null>(null);
  const [error, setError] = useState<string>();
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    onlineApi.menu(userType()).then((r) => setOptions(r.options), (e: Error) => setLoadError(e.message));
  }, []);

  const select = async (action: string, opt: string) => {
    setBusy(true);
    setError(undefined);
    try {
      const r = await onlineApi.menuSelect(action, opt, userType(), getSession()?.commarea ?? null);
      setResult(r);
      updateCommarea(r.commarea);
      const to = r.next === 'COACTVWC' || r.next === 'COACTUPC' || r.next === 'COSGN00C' ? routeFor(r.next) : null;
      if (r.next === 'COSGN00C') setSession(null);
      if (to) nav(to);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };
  const submit = (e: FormEvent) => { e.preventDefault(); void select('ENTER', option); };
  const exit = () => { setSession(null); nav('/online/signon'); };

  return (
    <section>
      <Header tran="CM00" prog="COMEN01C" title="Main menu">
        {session?.commarea?.userId ? <>Signed on as <span className="mono">{session.commarea.userId}</span>. </> : null}
        Please select an option and press ENTER.
      </Header>
      {!session && <p className="warn small">Not signed on. <Link to="/online/signon">Sign on</Link> first to carry a COMMAREA.</p>}
      {session?.next === 'COADM01C' && (
        <p className="online-note" data-testid="menu-admin-note">Admin menu (COADM01C) is not part of this slice; showing the main menu COMEN01C.</p>
      )}
      {loadError && <p className="error">{loadError}</p>}
      <ul className="online-menu" data-testid="menu-options">
        {options.map((o) => {
          const n = String(o.num).padStart(2, '0');
          return (
            <li key={o.num}>
              <button type="button" data-testid={`menu-option-${n}`} title={o.program} onClick={() => setOption(String(o.num))}>{o.text}</button>
            </li>
          );
        })}
      </ul>
      <form className="online-form" onSubmit={submit}>
        <label>Please select an option
          <input data-testid="menu-option-input" maxLength={2} value={option} onChange={(e) => setOption(e.target.value)} />
        </label>
        <div className="online-keys">
          <button type="submit" data-testid="menu-enter" disabled={busy}>ENTER</button>
          <button type="button" className="pf" data-testid="menu-pf3" disabled={busy} onClick={exit}>PF3 Exit</button>
        </div>
      </form>
      {error && <p className="error">{error}</p>}
      <Msg testId="menu-message" text={result?.message} color={result?.messageColor} />
    </section>
  );
}
