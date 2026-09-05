import { useEffect, useState, type FormEvent } from 'react';
import { api, type Role, type StaffAccount } from '../api';
import { ErrorNote, Pill } from '../components/ui';
import { when } from '../format';

export function Staff() {
  const [accounts, setAccounts] = useState<StaffAccount[] | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [role, setRole] = useState<Role>('support');
  const [status, setStatus] = useState('');
  const load = () => api.staff().then(setAccounts, setError);
  useEffect(() => { void load(); }, []);
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    try { await api.createStaff(username, password, role); setStatus(`Created ${username}.`); setUsername(''); setPassword(''); setError(null); await load(); } catch (err) { setError(err); }
  };
  return (
    <section>
      <h2>Staff accounts</h2>
      <p className="hint">Admins manage accounts; support staff handle tickets and see the conversations behind them. Usernames are lower case; passwords are at least 12 characters.</p>
      {accounts && (
        <table>
          <thead><tr><th>Username</th><th>Role</th><th>Created</th><th>By</th></tr></thead>
          <tbody>
            {accounts.map(a => (
              <tr key={a.username}>
                <td className="mono">{a.username}{a.enabled ? '' : ' (disabled)'}</td>
                <td><Pill kind={a.role}>{a.role}</Pill></td>
                <td>{when(a.createdAt)}</td>
                <td>{a.createdBy ?? ''}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      <form className="row" onSubmit={submit}>
        <label>Username <input value={username} onChange={e => setUsername(e.target.value)} required minLength={3} maxLength={64} autoComplete="off" /></label>
        <label>Password <input type="password" value={password} onChange={e => setPassword(e.target.value)} required minLength={12} autoComplete="new-password" /></label>
        <label>Role
          <select value={role} onChange={e => setRole(e.target.value as Role)}>
            <option value="support">support</option>
            <option value="admin">admin</option>
          </select>
        </label>
        <button className="primary">Create account</button>
      </form>
      {status && <p className="note">{status}</p>}
      <ErrorNote error={error} />
    </section>
  );
}
