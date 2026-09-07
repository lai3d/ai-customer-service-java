import { useEffect, useState, type FormEvent } from 'react';
import { useNavigate, useSearchParams } from 'react-router';
import { api, type ConversationPage } from '../api';
import { Empty, ErrorNote, Pager, Pill } from '../components/ui';
import { TenantPicker } from '../components/TenantPicker';
import { useAuth } from '../auth';
import { localToIso, when } from '../format';

export function Conversations() {
  const { me } = useAuth();
  const platform = me!.tenant === null;
  const [params, setParams] = useSearchParams();
  const navigate = useNavigate();
  const page = Number(params.get('page') ?? '0');
  const [id, setId] = useState(params.get('conversationId') ?? '');
  const [outcome, setOutcome] = useState(params.get('outcome') ?? '');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [data, setData] = useState<ConversationPage | null>(null);
  const [error, setError] = useState<unknown>(null);
  useEffect(() => {
    setData(null);
    api.conversations({ conversationId: params.get('conversationId') ?? undefined, outcome: params.get('outcome') ?? undefined,
      from: params.get('from') ?? undefined, to: params.get('to') ?? undefined, page, size: 25, tenant: params.get('tenant') ?? undefined }).then(setData, setError);
  }, [params, page]);
  const submit = (e: FormEvent) => {
    e.preventDefault();
    const p = new URLSearchParams();
    if (id.trim()) p.set('conversationId', id.trim());
    if (outcome) p.set('outcome', outcome);
    if (localToIso(from)) p.set('from', localToIso(from));
    if (localToIso(to)) p.set('to', localToIso(to));
    if (params.get('tenant')) p.set('tenant', params.get('tenant')!);
    setParams(p);
  };
  const chooseTenant = (v: string) => { const p = new URLSearchParams(params); if (v) p.set('tenant', v); else p.delete('tenant'); p.delete('page'); setParams(p); };
  return (
    <section>
      <h2>Conversations</h2>
      <TenantPicker value={params.get('tenant') ?? ''} onChange={chooseTenant} allowAll />
      <form className="toolbar" onSubmit={submit}>
        <label>Conversation id <input value={id} onChange={e => setId(e.target.value)} placeholder="ours or the customer's, exact" /></label>
        <label>Outcome
          <select value={outcome} onChange={e => setOutcome(e.target.value)}>
            <option value="">any</option>
            {['completed', 'failed', 'interrupted', 'unknown', 'running'].map(o => <option key={o} value={o}>{o}</option>)}
          </select>
        </label>
        <label>From <input type="datetime-local" value={from} onChange={e => setFrom(e.target.value)} /></label>
        <label>To <input type="datetime-local" value={to} onChange={e => setTo(e.target.value)} /></label>
        <button className="primary">Show</button>
      </form>
      <ErrorNote error={error} />
      {data && data.conversations.length === 0 && <Empty>No conversations recorded.</Empty>}
      {data && data.conversations.length > 0 && (
        <table>
          <thead><tr><th>Conversation</th><th>Customer's id</th>{platform && <th>Tenant</th>}<th>Turns</th><th>Last turn</th><th>Last outcome</th><th>Failed / interrupted / unknown</th></tr></thead>
          <tbody>
            {data.conversations.map(c => (
              <tr key={c.conversationId} className="link" onClick={() => navigate(`/conversations/${c.conversationId}`)}>
                <td className="mono">{c.conversationId}</td>
                <td className="mono">{c.externalId ?? '—'}</td>
                {platform && <td className="mono">{c.tenantId}</td>}
                <td>{c.turns}</td>
                <td>{when(c.lastAt)}</td>
                <td><Pill kind={c.lastOutcome}>{c.lastOutcome}</Pill></td>
                <td>{c.failed} / {c.interrupted} / {c.unknown}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {data && <Pager page={data.page} size={data.size} total={data.total} onPage={p => { const q = new URLSearchParams(params); q.set('page', String(p)); setParams(q); }} />}
    </section>
  );
}
