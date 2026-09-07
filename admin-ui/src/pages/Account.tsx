import { useState, type FormEvent } from 'react';
import { api } from '../api';
import { useAuth } from '../auth';
import { OrderConnectorSection } from '../components/OrderConnector';
import { TelegramSection } from '../components/Telegram';
import { ErrorNote } from '../components/ui';

// The one page every role has for its own account: changing the password. The current
// password is asked for because a browser left signed in is not proof of knowing it; the
// confirmation is checked here so a typo is caught before anything is sent.
export function Account() {
  const { me } = useAuth();
  const [current, setCurrent] = useState('');
  const [next, setNext] = useState('');
  const [confirm, setConfirm] = useState('');
  const [status, setStatus] = useState('');
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setStatus(''); setError(null);
    if (next !== confirm) { setError(new Error('The new password and its confirmation do not match.')); return; }
    setBusy(true);
    try {
      await api.changeOwnPassword(current, next);
      setStatus('Password changed; your other sessions are signed out.');
      setCurrent(''); setNext(''); setConfirm('');
    } catch (err) { setError(err); } finally { setBusy(false); }
  };
  return (
    <>
    <section>
      <h2>Account</h2>
      <p className="hint">Signed in as <span className="mono">{me!.username}</span> ({me!.role}{me!.tenant ? `, tenant ${me!.tenant.name}` : ', platform'}). A new password is at least 12 characters and must differ from the current one. Changing it signs out every other session of this account; this one stays.</p>
      <form className="row" onSubmit={submit}>
        <label>Current password <input type="password" value={current} onChange={e => setCurrent(e.target.value)} required autoComplete="current-password" /></label>
        <label>New password <input type="password" value={next} onChange={e => setNext(e.target.value)} required minLength={12} autoComplete="new-password" /></label>
        <label>Confirm new password <input type="password" value={confirm} onChange={e => setConfirm(e.target.value)} required minLength={12} autoComplete="new-password" /></label>
        <button className="primary" disabled={busy}>Change password</button>
      </form>
      {status && <p className="note">{status}</p>}
      <ErrorNote error={error} />
    </section>
    {me!.role === 'admin' && me!.tenant && <OrderConnectorSection tenantId={me!.tenant.id} />}
    {me!.role === 'admin' && me!.tenant && <TelegramSection tenantId={me!.tenant.id} />}
    </>
  );
}
