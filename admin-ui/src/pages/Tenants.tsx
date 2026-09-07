import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import { api, type IssuedKey, type KeyKind, type Tenant, type TenantDetail } from '../api';
import { Empty, ErrorNote, Pill } from '../components/ui';
import { OrderConnectorSection } from '../components/OrderConnector';
import { TelegramSection } from '../components/Telegram';
import { Onboarding } from '../components/Onboarding';
import { when } from '../format';

/**
 * Tenants and their API keys (ADR 002). A key is shown once, in the response that issued
 * it, and never again: the server keeps only its hash, so the page keeps the issued key on
 * screen until the admin dismisses it and offers a copy button.
 */
export function TenantsPage() {
  const { id } = useParams();
  return id ? <TenantDetailPage id={id} /> : <TenantList />;
}

function TenantList() {
  const navigate = useNavigate();
  const [tenants, setTenants] = useState<Tenant[] | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [newId, setNewId] = useState('');
  const [name, setName] = useState('');
  const load = useCallback(() => api.tenants().then(t => { setTenants(t); setError(null); }, setError), []);
  useEffect(() => { void load(); }, [load]);
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    try {
      const created = await api.createTenant(newId.trim(), name.trim());
      setNewId(''); setName(''); setError(null);
      navigate(`/tenants/${encodeURIComponent(created.id)}`);
    } catch (err) { setError(err); }
  };
  return (
    <section>
      <h2>Tenants</h2>
      <p className="hint">A tenant is a customer of this deployment: its own API keys on the public chat API, its own conversations, tickets and knowledge. The id is the identity and the metric label; it cannot change. <span className="mono">default</span> is the deployment itself and cannot be disabled.</p>
      {!tenants ? <><ErrorNote error={error} /><Empty>Loading…</Empty></> : (
        <table>
          <thead><tr><th>Id</th><th>Name</th><th>Enabled</th><th>Created</th></tr></thead>
          <tbody>
            {tenants.map(t => (
              <tr key={t.id} className="link" onClick={() => navigate(`/tenants/${encodeURIComponent(t.id)}`)}>
                <td className="mono">{t.id}</td>
                <td>{t.name}</td>
                <td>{t.enabled ? 'yes' : 'no'}</td>
                <td>{when(t.createdAt)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      <form className="row" onSubmit={submit}>
        <label>Id <input value={newId} onChange={e => setNewId(e.target.value)} required minLength={2} maxLength={36} pattern="[a-z0-9][a-z0-9\-]{1,35}" title="2 to 36 lower-case letters, digits or hyphens" autoComplete="off" /></label>
        <label>Name <input value={name} onChange={e => setName(e.target.value)} required maxLength={200} autoComplete="off" /></label>
        <button className="primary">Create tenant</button>
      </form>
      {tenants && <ErrorNote error={error} />}
    </section>
  );
}

function TenantDetailPage({ id }: { id: string }) {
  const [data, setData] = useState<TenantDetail | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [label, setLabel] = useState('');
  const [kind, setKind] = useState<KeyKind>('secret');
  const [origins, setOrigins] = useState('');
  const [issued, setIssued] = useState<IssuedKey | null>(null);
  const [copied, setCopied] = useState(false);
  const [status, setStatus] = useState('');
  const [revision, setRevision] = useState(0);
  const changed = () => setRevision(r => r + 1);
  const load = useCallback(() => api.tenant(id).then(d => { setData(d); setError(null); }, setError), [id]);
  useEffect(() => { void load(); }, [load]);
  const run = async (work: () => Promise<unknown>, done: string) => {
    try { await work(); setStatus(done); setError(null); await load(); changed(); } catch (err) { setError(err); }
  };
  const issue = async (e: FormEvent) => {
    e.preventDefault();
    try {
      const list = origins.split(/[\s,]+/).map(o => o.trim()).filter(Boolean);
      const key = await api.issueTenantKey(id, label.trim(), kind, list);
      setIssued(key); setCopied(false); setLabel(''); setOrigins(''); setStatus(''); setError(null);
      await load(); changed();
    } catch (err) { setError(err); }
  };
  const copy = async () => {
    if (!issued) return;
    try { await navigator.clipboard.writeText(issued.key); setCopied(true); } catch { setCopied(false); }
  };
  if (!data) return <><ErrorNote error={error} /><Empty>Loading…</Empty></>;
  const t = data.tenant;
  const live = data.keys.filter(k => !k.revokedAt);
  const revoked = data.keys.filter(k => k.revokedAt);
  return (
    <>
      <section>
        <p><Link to="/tenants">← Tenants</Link></p>
        <h2>Tenant <span className="mono">{t.id}</span></h2>
        <dl className="facts">
          <dt>Name</dt><dd>{t.name}</dd>
          <dt>Enabled</dt><dd><Pill kind={t.enabled ? 'active' : 'retired'}>{t.enabled ? 'yes' : 'no'}</Pill></dd>
          <dt>Created</dt><dd>{when(t.createdAt)}</dd>
        </dl>
        <div className="row">
          {t.enabled && t.id !== 'default' && <button className="danger" onClick={() => void run(() => api.setTenantEnabled(id, false), `Disabled ${id}; its keys are refused on the public API.`)}>Disable</button>}
          {!t.enabled && <button onClick={() => void run(() => api.setTenantEnabled(id, true), `Enabled ${id}.`)}>Enable</button>}
          {t.id === 'default' && <span className="hint">The default tenant is the deployment itself and stays enabled.</span>}
        </div>
        {status && <p className="note">{status}</p>}
      </section>
      <Onboarding tenantId={id} revision={revision} />
      <section id="keys">
        <h3>API keys</h3>
        <p className="hint">A key is the tenant's identity on <span className="mono">/api/v1/**</span>. A <b>secret</b> key is for a server the tenant controls (<span className="mono">Authorization: Bearer</span>); a <b>widget</b> key is pasted into the tenant's web page and works only from browsers on the origins it was issued for. Either is shown once, when issued; the server keeps only its hash. Revoking takes effect on the next request.</p>
        {issued && (
          <div className="notice">
            <div>New {issued.kind} key for <span className="mono">{id}</span> ({issued.label}){issued.kind === 'widget' && <> for {issued.origins.join(', ')}</>}. Copy it now; it will not be shown again.</div>
            <div className="row">
              <code className="mono">{issued.key}</code>
              <button type="button" onClick={() => void copy()}>{copied ? 'Copied' : 'Copy'}</button>
              <button type="button" onClick={() => setIssued(null)}>I have saved it</button>
            </div>
            {issued.kind === 'widget' && (
              <div>
                <div className="hint">The tag for the tenant's page; the host is this deployment's public address (see docs/widget.md). On a panel's own pages add <span className="mono">data-customer-token-key</span> (the localStorage key holding the signed-in customer's token) so the assistant can read that customer's account:</div>
                <code className="mono">{`<script src="https://<this deployment>/widget.js" data-key="${issued.key}" async></script>`}</code>
              </div>
            )}
          </div>
        )}
        {live.length === 0 ? <p className="hint">No live keys; this tenant cannot talk to the public API.</p> : (
          <table>
            <thead><tr><th>Key id</th><th>Kind</th><th>Label</th><th>Origins</th><th>Issued</th><th></th></tr></thead>
            <tbody>
              {live.map(k => (
                <tr key={k.keyId}>
                  <td className="mono">{k.keyId}</td>
                  <td><Pill kind={k.kind}>{k.kind}</Pill></td>
                  <td>{k.label}</td>
                  <td className="mono">{k.kind === 'widget' ? k.origins.join(' ') : '—'}</td>
                  <td>{when(k.createdAt)}</td>
                  <td><button className="danger" onClick={() => void run(() => api.revokeTenantKey(id, k.keyId), `Revoked ${k.keyId}.`)}>Revoke</button></td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        <form className="row" onSubmit={issue}>
          <label>Kind
            <select value={kind} onChange={e => setKind(e.target.value as KeyKind)}>
              <option value="secret">secret (a server)</option>
              <option value="widget">widget (a web page)</option>
            </select>
          </label>
          <label>Label <input value={label} onChange={e => setLabel(e.target.value)} placeholder="what this key is for" maxLength={200} autoComplete="off" /></label>
          {kind === 'widget' && <label>Origins <input value={origins} onChange={e => setOrigins(e.target.value)} placeholder="https://shop.example.com https://www.example.com" size={48} required autoComplete="off" /></label>}
          <button className="primary">Issue a {kind} key</button>
        </form>
        {revoked.length > 0 && (
          <>
            <h4>Revoked</h4>
            <table>
              <thead><tr><th>Key id</th><th>Kind</th><th>Label</th><th>Issued</th><th>Revoked</th></tr></thead>
              <tbody>{revoked.map(k => <tr key={k.keyId}><td className="mono">{k.keyId}</td><td>{k.kind}</td><td>{k.label}</td><td>{when(k.createdAt)}</td><td>{when(k.revokedAt!)}</td></tr>)}</tbody>
            </table>
          </>
        )}
        <ErrorNote error={error} />
      </section>
      <OrderConnectorSection tenantId={id} onChanged={changed} />
      <TelegramSection tenantId={id} onChanged={changed} />
    </>
  );
}
