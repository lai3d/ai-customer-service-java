import { useEffect, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router';
import { api, type TicketPage } from '../api';
import { useAuth } from '../auth';
import { Empty, ErrorNote, Pager, Pill } from '../components/ui';
import { when } from '../format';

export function Tickets() {
  const { me } = useAuth();
  const [params, setParams] = useSearchParams();
  const navigate = useNavigate();
  const state = params.get('state') ?? '';
  const owner = params.get('owner') ?? '';
  const page = Number(params.get('page') ?? '0');
  const [data, setData] = useState<TicketPage | null>(null);
  const [error, setError] = useState<unknown>(null);
  useEffect(() => {
    setData(null);
    api.tickets({ state: state || undefined, owner: owner || undefined, page, size: 25 }).then(setData, setError);
  }, [state, owner, page]);
  const set = (k: string, v: string) => { const p = new URLSearchParams(params); if (v) p.set(k, v); else p.delete(k); p.delete('page'); setParams(p); };
  return (
    <section>
      <h2>Tickets</h2>
      <div className="toolbar">
        <label>State
          <select value={state} onChange={e => set('state', e.target.value)}>
            <option value="">any</option>
            {['open', 'claimed', 'resolved', 'closed'].map(s => <option key={s} value={s}>{s}</option>)}
          </select>
        </label>
        <label>Owner
          <select value={owner} onChange={e => set('owner', e.target.value)}>
            <option value="">anyone</option>
            <option value={me!.username}>mine</option>
            <option value="-">unassigned</option>
          </select>
        </label>
      </div>
      <ErrorNote error={error} />
      {data && data.tickets.length === 0 && <Empty>No tickets. One appears when the assistant raises it.</Empty>}
      {data && data.tickets.length > 0 && (
        <table>
          <thead><tr><th>Ticket</th><th>State</th><th>Owner</th><th>Category</th><th>Summary</th><th>Updated</th></tr></thead>
          <tbody>
            {data.tickets.map(t => (
              <tr key={t.ticketNumber} className="link" onClick={() => navigate(`/tickets/${t.ticketNumber}`)}>
                <td className="mono">{t.ticketNumber}</td>
                <td><Pill kind={t.state}>{t.state}</Pill></td>
                <td className="mono">{t.owner ?? '—'}</td>
                <td>{t.category}</td>
                <td>{t.summary}</td>
                <td>{when(t.updatedAt)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {data && <Pager page={data.page} size={data.size} total={data.total} onPage={p => { const q = new URLSearchParams(params); q.set('page', String(p)); setParams(q); }} />}
    </section>
  );
}
