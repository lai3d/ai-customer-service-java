import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { Link, useParams } from 'react-router';
import { api, type ConversationDetail, type Turn } from '../api';
import { Markdown } from '../components/Markdown';
import { Empty, ErrorNote, Notice, Pill } from '../components/ui';
import { millis, when } from '../format';

function TurnView({ t, flags, onFlagged }: { t: Turn; flags: ConversationDetail['feedback']; onFlagged: () => void }) {
  const [issue, setIssue] = useState('incorrect');
  const [note, setNote] = useState('');
  const [error, setError] = useState<unknown>(null);
  const mine = flags.filter(f => f.turnId === t.turnId);
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    try { await api.flag(t.turnId, issue, note); setNote(''); setError(null); onFlagged(); } catch (err) { setError(err); }
  };
  return (
    <div className="turn">
      <div className="msg customer"><span className="at">customer · {when(t.startedAt)}</span>{t.question}</div>
      <div className="meta">
        <Pill kind={t.outcome}>{t.outcome}</Pill>
        <span>{t.path}</span>
        {t.model && <span>{t.model}</span>}
        {t.inputTokens !== null && <span>{t.inputTokens} in / {t.outputTokens} out</span>}
        {t.endedAt && <span>{millis(t.startedAt, t.endedAt)}</span>}
        {t.traceId && <span className="mono">trace {t.traceId}</span>}
      </div>
      {t.answer ? <div className="msg assistant"><span className="at">assistant</span><Markdown text={t.answer} /></div>
        : <p className="hint">{t.outcome === 'running' ? 'Still running.' : 'No answer was recorded.'}</p>}
      {t.failure && <div className="failure">{t.failure}</div>}
      <h4>Retrieved ({t.retrieval.length})</h4>
      {t.retrieval.length === 0 ? <p className="hint">Nothing recorded for this turn.</p> : (
        <table>
          <thead><tr><th>#</th><th>Entry</th><th>Language</th><th>Score</th></tr></thead>
          <tbody>{t.retrieval.map(r => <tr key={r.rank}><td>{r.rank}</td><td className="mono">{r.entryId}</td><td>{r.language ?? ''}</td><td>{r.score.toFixed(4)}</td></tr>)}</tbody>
        </table>
      )}
      <h4>Tools ({t.toolCalls.length})</h4>
      {t.toolCalls.length === 0 ? <p className="hint">None.</p> : t.toolCalls.map((c, i) => <div key={i} className="mono">{c.tool} → {c.outcome}  {when(c.at)}</div>)}
      <h4>Feedback</h4>
      {mine.map(f => <div key={f.id}>#{f.id} {f.issue}{f.note ? `: ${f.note}` : ''} — {f.reportedBy}, {when(f.reportedAt)} · {f.state}{f.conclusion ? ` (${f.conclusion})` : ''}</div>)}
      {t.outcome !== 'running' && (
        <form className="row" onSubmit={submit}>
          <label>Flag this answer as
            <select value={issue} onChange={e => setIssue(e.target.value)}>
              {['incorrect', 'incomplete', 'unhelpful', 'other'].map(i => <option key={i} value={i}>{i}</option>)}
            </select>
          </label>
          <label>Note <input value={note} onChange={e => setNote(e.target.value)} placeholder="what is wrong (optional)" size={40} /></label>
          <button>Flag</button>
        </form>
      )}
      <ErrorNote error={error} />
    </div>
  );
}

export function ConversationPage() {
  const { id = '' } = useParams();
  const [data, setData] = useState<ConversationDetail | null>(null);
  const [error, setError] = useState<unknown>(null);
  const load = useCallback(() => api.conversation(id).then(setData, setError), [id]);
  useEffect(() => { void load(); }, [load]);
  if (!data) return <><ErrorNote error={error} /><Empty>Loading…</Empty></>;
  return (
    <section>
      <h2>Conversation <span className="mono">{data.conversationId}</span></h2>
      <Notice>Opening this conversation was recorded against your account. {data.notPersisted}</Notice>
      <p className="hint">{data.tickets.length === 0 ? 'No tickets in this conversation.' : <>Tickets raised in this conversation: {data.tickets.map((x, i) => <span key={x.ticketNumber}>{i > 0 && ', '}<Link to={`/tickets/${x.ticketNumber}`}>{x.ticketNumber}</Link></span>)}</>}</p>
      {data.turns.map(t => <TurnView key={t.turnId} t={t} flags={data.feedback} onFlagged={() => void load()} />)}
    </section>
  );
}
