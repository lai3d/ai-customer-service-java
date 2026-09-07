import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { api, type ConnectorTest, type OrderConnector } from '../api';
import { ErrorNote } from '../components/ui';
import { when } from '../format';

/**
 * A tenant's order system (docs/connectors.md): the Shopify store the lookup_order_status
 * tool reads real orders from. The token is written once and shown masked; the test asks
 * the store its name with the stored token. Shown to platform staff on the tenant's page
 * and to a tenant's own admins on their account page.
 */
export function OrderConnectorSection({ tenantId }: { tenantId: string }) {
  const [connector, setConnector] = useState<OrderConnector | null | undefined>(undefined);
  const [editing, setEditing] = useState(false);
  const [domain, setDomain] = useState('');
  const [token, setToken] = useState('');
  const [version, setVersion] = useState('');
  const [test, setTest] = useState<ConnectorTest | null>(null);
  const [busy, setBusy] = useState(false);
  const [status, setStatus] = useState('');
  const [error, setError] = useState<unknown>(null);
  const load = useCallback(() => api.orderConnector(tenantId).then(c => { setConnector(c ?? null); setError(null); }, setError), [tenantId]);
  useEffect(() => { void load(); setTest(null); setEditing(false); }, [load]);
  const save = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    try { await api.saveOrderConnector(tenantId, domain.trim(), token.trim(), version.trim()); setEditing(false); setToken(''); setTest(null); setStatus('Connector saved; the token is stored and shown masked from now on.'); setError(null); await load(); }
    catch (err) { setError(err); } finally { setBusy(false); }
  };
  const runTest = async () => {
    setBusy(true);
    try { setTest(await api.testOrderConnector(tenantId)); setError(null); } catch (err) { setError(err); } finally { setBusy(false); }
  };
  const remove = async () => {
    setBusy(true);
    try { await api.deleteOrderConnector(tenantId); setTest(null); setStatus('Connector removed; the assistant will offer a ticket instead of an order lookup.'); setError(null); await load(); }
    catch (err) { setError(err); } finally { setBusy(false); }
  };
  const startEdit = () => { setDomain(connector?.shopDomain ?? ''); setVersion(connector?.apiVersion ?? ''); setToken(''); setEditing(true); setStatus(''); };
  return (
    <section>
      <h3>Order system</h3>
      <p className="hint">Where the assistant looks up a customer's real orders: the tenant's Shopify store, read with a custom app's Admin API token that has only <span className="mono">read_orders</span>. Without one, the assistant offers a ticket instead of guessing.</p>
      {connector === undefined && <p className="hint">Loading…</p>}
      {connector === null && !editing && <p className="hint">No order system connected.</p>}
      {connector && !editing && (
        <dl className="facts">
          <dt>Store</dt><dd className="mono">{connector.shopDomain}</dd>
          <dt>Token</dt><dd className="mono">{connector.accessToken}</dd>
          <dt>API version</dt><dd className="mono">{connector.apiVersion}</dd>
          <dt>Configured</dt><dd>{when(connector.configuredAt)} by {connector.configuredBy}</dd>
        </dl>
      )}
      {!editing && connector !== undefined && (
        <div className="row">
          <button type="button" className="primary" onClick={startEdit}>{connector ? 'Change…' : 'Connect a Shopify store…'}</button>
          {connector && <button type="button" onClick={() => void runTest()} disabled={busy}>Test</button>}
          {connector && <button type="button" className="danger" onClick={() => void remove()} disabled={busy}>Disconnect</button>}
        </div>
      )}
      {editing && (
        <form className="row" onSubmit={save}>
          <label>Shop domain <input value={domain} onChange={e => setDomain(e.target.value)} placeholder="my-store.myshopify.com" size={32} required autoFocus /></label>
          <label>Admin API access token <input type="password" value={token} onChange={e => setToken(e.target.value)} placeholder="shpat_…" size={36} required autoComplete="off" /></label>
          <label>API version <input value={version} onChange={e => setVersion(e.target.value)} placeholder="default" size={10} /></label>
          <button className="primary" disabled={busy}>Save</button>
          <button type="button" onClick={() => setEditing(false)}>Cancel</button>
        </form>
      )}
      {test && (test.ok
        ? <p className="note">The store answered: <b>{test.shopName}</b> ({test.shopDomain}).</p>
        : <p className="note error">The store did not answer for {test.shopDomain}: {test.error}</p>)}
      {status && <p className="note">{status}</p>}
      <ErrorNote error={error} />
      <details>
        <summary className="hint">How the store owner gets a token</summary>
        <ol className="hint">
          <li>In the store's admin: Settings → Apps and sales channels → Develop apps → Create an app.</li>
          <li>Configure Admin API scopes: <span className="mono">read_orders</span> (and <span className="mono">read_fulfillments</span> where it is separate). Nothing else; the connector only reads.</li>
          <li>Install the app, then API credentials → Admin API access token: shown once, <span className="mono">shpat_…</span>.</li>
          <li>Paste the shop domain and the token here and press Test; the store answers with its name.</li>
        </ol>
      </details>
    </section>
  );
}
