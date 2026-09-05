import { useState, type FormEvent } from 'react';
import { useAuth } from '../auth';
import { ErrorNote } from '../components/ui';

export function Login() {
  const { login } = useAuth();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true); setError(null);
    try { await login(username, password); } catch (err) { setError(err); } finally { setBusy(false); }
  };
  return (
    <form className="login" onSubmit={submit}>
      <h1>Operations admin</h1>
      <p className="hint">Staff sign in. Customer conversations are behind this door.</p>
      <ErrorNote error={error} />
      <label>Username <input value={username} onChange={e => setUsername(e.target.value)} autoComplete="username" required autoFocus /></label>
      <label>Password <input type="password" value={password} onChange={e => setPassword(e.target.value)} autoComplete="current-password" required /></label>
      <button className="primary" disabled={busy}>Sign in</button>
    </form>
  );
}
