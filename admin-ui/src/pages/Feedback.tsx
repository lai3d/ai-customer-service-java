import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { useNavigate, useSearchParams } from 'react-router';
import { api, type Feedback, type FeedbackPage } from '../api';
import { Empty, ErrorNote, Pager, Pill } from '../components/ui';
import { when } from '../format';

export function FeedbackPage() {
  const [params, setParams] = useSearchParams();
  const navigate = useNavigate();
  const state = params.get('state') ?? 'open';
  const page = Number(params.get('page') ?? '0');
  const [data, setData] = useState<FeedbackPage | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [closing, setClosing] = useState<{ f: Feedback; state: 'handled' | 'dismissed' } | null>(null);
  const [conclusion, setConclusion] = useState('');
  const load = useCallback(() => api.feedback({ state: state === 'all' ? undefined : state, page, size: 25 }).then(setData, setError), [state, page]);
  useEffect(() => { setData(null); void load(); }, [load]);
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    if (!closing) return;
    try { await api.handleFeedback(closing.f.id, closing.state, conclusion, closing.f.version); setClosing(null); setConclusion(''); setError(null); await load(); }
    catch (err) { setError(err); if ((err as { status?: number }).status === 409) await load(); }
  };
  return (
    <section>
      <h2>Answer feedback</h2>
      <div className="toolbar">
        <label>State
          <select value={state} onChange={e => { const p = new URLSearchParams(); p.set('state', e.target.value); setParams(p); }}>
            {['open', 'handled', 'dismissed', 'all'].map(s => <option key={s} value={s}>{s}</option>)}
          </select>
        </label>
      </div>
      <ErrorNote error={error} />
      {data && data.reports.length === 0 && <Empty>Nothing flagged.</Empty>}
      {data && data.reports.length > 0 && (
        <table>
          <thead><tr><th>#</th><th>Issue</th><th>Note</th><th>Conversation</th><th>Reported</th><th>State</th><th></th></tr></thead>
          <tbody>
            {data.reports.map(f => (
              <tr key={f.id}>
                <td>#{f.id}</td>
                <td>{f.issue}</td>
                <td>{f.note ?? ''}</td>
                <td><button onClick={() => navigate(`/conversations/${f.conversationId}`)}>Open</button></td>
                <td>{f.reportedBy}, {when(f.reportedAt)}</td>
                <td><Pill kind={f.state}>{f.state}</Pill>{f.conclusion && <div className="hint">{f.conclusion}{f.handledBy ? ` — ${f.handledBy}` : ''}{f.revisionId ? ` (revision ${f.revisionId})` : ''}</div>}</td>
                <td>{f.state === 'open' && <>
                  <button className="primary" onClick={() => { setClosing({ f, state: 'handled' }); setConclusion(''); }}>Handle…</button>{' '}
                  <button onClick={() => { setClosing({ f, state: 'dismissed' }); setConclusion(''); }}>Dismiss…</button>
                </>}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {closing && (
        <form className="row" onSubmit={submit}>
          <label>{closing.state === 'handled' ? `What was done about #${closing.f.id}? (required)` : `Why does #${closing.f.id} need no change? (optional)`}
            <textarea value={conclusion} onChange={e => setConclusion(e.target.value)} required={closing.state === 'handled'} autoFocus /></label>
          <button className="primary">{closing.state === 'handled' ? 'Mark handled' : 'Dismiss'}</button>
          <button type="button" onClick={() => setClosing(null)}>Cancel</button>
        </form>
      )}
      {data && <Pager page={data.page} size={data.size} total={data.total} onPage={p => { const q = new URLSearchParams(params); q.set('page', String(p)); setParams(q); }} />}
    </section>
  );
}
