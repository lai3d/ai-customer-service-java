import { useEffect, useState, type FormEvent } from 'react';
import { api, type Role, type StaffAccount, type Tenant } from '../api';
import { useAuth } from '../auth';
import { ErrorNote, Pill } from '../components/ui';
import { when } from '../format';

export function Staff() {
  const { me } = useAuth();
  const [accounts, setAccounts] = useState<StaffAccount[] | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [role, setRole] = useState<Role>('support');
  const [tenants, setTenants] = useState<Tenant[]>([]);
  const [newTenant, setNewTenant] = useState('');
  const platform = me!.tenant === null;
  const [status, setStatus] = useState('');
  const [resetting, setResetting] = useState<string | null>(null);
  const [newPassword, setNewPassword] = useState('');
  const load = () => api.staff().then(setAccounts, setError);
  useEffect(() => { void load(); if (platform) api.tenants().then(setTenants, () => setTenants([])); }, [platform]);
  const run = async (work: () => Promise<unknown>, done: string) => {
    try { await work(); setStatus(done); setError(null); await load(); } catch (err) { setError(err); }
  };
  const submit = (e: FormEvent) => {
    e.preventDefault();
    void run(() => api.createStaff(username, password, role, platform ? (newTenant || undefined) : undefined), `Created ${username}.`).then(() => { setUsername(''); setPassword(''); });
  };
  const submitReset = (e: FormEvent) => {
    e.preventDefault();
    if (!resetting) return;
    const target = resetting;
    void run(() => api.resetStaffPassword(target, newPassword), `Password reset for ${target}; their other sessions are signed out.`)
      .then(() => { setResetting(null); setNewPassword(''); });
  };
  return (
    <section>
      <h2>Staff accounts</h2>
      <p className="hint">{platform ? 'Platform staff run the deployment and see every tenant; a tenant\'s admins manage their own tenant\'s accounts. ' : `Accounts of tenant ${me!.tenant!.name}. `}Admins manage accounts; support staff handle tickets and see the conversations behind them. Usernames are lower case; passwords are at least 12 characters. Disabling an account or changing its role signs it out everywhere; you cannot do either to your own account, and the last enabled admin stays an admin.</p>
      {accounts && (
        <table>
          <thead><tr><th>Username</th>{platform && <th>Tenant</th>}<th>Role</th><th>Enabled</th><th>Created</th><th>By</th><th>Actions</th></tr></thead>
          <tbody>
            {accounts.map(a => {
              const self = a.username === me!.username;
              return (
                <tr key={a.username}>
                  <td className="mono">{a.username}{self ? ' (you)' : ''}</td>
                  {platform && <td className="mono">{a.tenantId ?? 'platform'}</td>}
                  <td><Pill kind={a.role}>{a.role}</Pill></td>
                  <td>{a.enabled ? 'yes' : 'no'}</td>
                  <td>{when(a.createdAt)}</td>
                  <td>{a.createdBy ?? ''}</td>
                  <td className="row">
                    {!self && (a.enabled
                      ? <button className="danger" onClick={() => void run(() => api.setStaffEnabled(a.username, false), `Disabled ${a.username}; signed out everywhere.`)}>Disable</button>
                      : <button onClick={() => void run(() => api.setStaffEnabled(a.username, true), `Enabled ${a.username}.`)}>Enable</button>)}
                    {!self && !(a.tenantId === null && a.role === 'admin') && <button onClick={() => void run(() => api.setStaffRole(a.username, a.role === 'admin' ? 'support' : 'admin'), `${a.username} is now ${a.role === 'admin' ? 'support' : 'admin'}; signed out everywhere.`)}>Make {a.role === 'admin' ? 'support' : 'admin'}</button>}
                    <button onClick={() => { setResetting(a.username); setNewPassword(''); }}>Reset password…</button>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      )}
      {resetting && (
        <form className="row" onSubmit={submitReset}>
          <label>New password for <span className="mono">{resetting}</span> <input type="password" value={newPassword} onChange={e => setNewPassword(e.target.value)} required minLength={12} autoComplete="new-password" autoFocus /></label>
          <button className="primary">Reset password</button>
          <button type="button" onClick={() => setResetting(null)}>Cancel</button>
        </form>
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
        {platform && (
          <label>Tenant
            <select value={newTenant} onChange={e => setNewTenant(e.target.value)}>
              <option value="">{role === 'admin' ? 'platform' : 'default'}</option>
              {role === 'admin' && <option value="platform">platform</option>}
              {tenants.map(t => <option key={t.id} value={t.id}>{t.id}{t.name && t.id !== t.name ? ` · ${t.name}` : ''}</option>)}
            </select>
          </label>
        )}
        <button className="primary">Create account</button>
      </form>
      {status && <p className="note">{status}</p>}
      <ErrorNote error={error} />
    </section>
  );
}
