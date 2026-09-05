import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { Link, useParams } from 'react-router';
import { api, type TicketConversation, type TicketDetail } from '../api';
import { useAuth } from '../auth';
import { Markdown } from '../components/Markdown';
import { Empty, ErrorNote, Notice, Pill } from '../components/ui';
import { when } from '../format';

type Form = null | 'resolve' | 'assign' | 'note';

export function TicketDetailPage() {
  const { number = '' } = useParams();
  const { me } = useAuth();
  const [data, setData] = useState<TicketDetail | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [form, setForm] = useState<Form>(null);
  const [text, setText] = useState('');
  const [conversation, setConversation] = useState<TicketConversation | null>(null);
  const load = useCallback(() => api.ticket(number).then(d => { setData(d); setError(null); }, setError), [number]);
  useEffect(() => { void load(); }, [load]);
  if (!data) return <><ErrorNote error={error} /><Empty>Loading…</Empty></>;
  const t = data.ticket;
  const mine = t.owner === me!.username;
  const may = t.owner === null || mine || me!.role === 'admin';
  const act = async (action: string, extra: { assignee?: string; text?: string } = {}) => {
    try {
      await api.ticketAction(t.ticketNumber, action, t.version, extra);
      setForm(null); setText('');
      await load();
    } catch (err) {
      setError(err);
      if (err instanceof Error && 'status' in err && (err as { status: number }).status === 409) await load();
    }
  };
  const submit = (e: FormEvent) => {
    e.preventDefault();
    if (form === 'resolve') void act('resolve', { text });
    if (form === 'assign') void act('assign', { assignee: text });
    if (form === 'note') void act('note', { text });
  };
  const openConversation = () => api.ticketConversation(t.ticketNumber).then(setConversation, setError);
  return (
    <>
      <section>
        <h2>Ticket <span className="mono">{t.ticketNumber}</span></h2>
        <h3>{t.summary}</h3>
        <dl className="facts">
          <dt>State</dt><dd><Pill kind={t.state}>{t.state}</Pill></dd>
          <dt>Owner</dt><dd>{t.owner ?? 'nobody'}</dd>
          <dt>Category</dt><dd>{t.category}</dd>
          <dt>Order</dt><dd className="mono">{t.orderNumber ?? '—'}</dd>
          <dt>Conversation</dt><dd className="mono"><Link to={`/conversations/${t.conversationId}`}>{t.conversationId}</Link></dd>
          <dt>Created</dt><dd>{when(t.createdAt)} by the assistant</dd>
          <dt>Updated</dt><dd>{when(t.updatedAt)} (version {t.version})</dd>
        </dl>
        <div className="row">
          {t.state === 'open' && <button className="primary" onClick={() => void act('claim')}>Claim</button>}
          {t.state === 'claimed' && may && <button className="primary" onClick={() => { setForm('resolve'); setText(''); }}>Resolve…</button>}
          {t.state === 'claimed' && may && <button onClick={() => void act('release')}>Release</button>}
          {(t.state === 'claimed' || t.state === 'resolved') && may && <button className="danger" onClick={() => void act('close')}>Close</button>}
          {(t.state === 'resolved' || t.state === 'closed') && <button onClick={() => void act('reopen')}>Reopen</button>}
          {(t.state === 'open' || t.state === 'claimed') && may && <button onClick={() => { setForm('assign'); setText(''); }}>Assign…</button>}
          <button onClick={() => { setForm('note'); setText(''); }}>Add note…</button>
          {t.state === 'claimed' && !may && <span className="hint">Owned by {t.owner}; only the owner or an admin can change it.</span>}
        </div>
        {form && (
          <form className="row" onSubmit={submit}>
            {form === 'resolve' && <label>Conclusion: what was done for the customer (required)<textarea value={text} onChange={e => setText(e.target.value)} required autoFocus /></label>}
            {form === 'assign' && <label>Assign to<input value={text} onChange={e => setText(e.target.value)} placeholder="username" required autoFocus /></label>}
            {form === 'note' && <label>Internal note<textarea value={text} onChange={e => setText(e.target.value)} placeholder="Visible to staff only" required autoFocus /></label>}
            <button className="primary">{form === 'resolve' ? 'Resolve' : form === 'assign' ? 'Assign' : 'Add note'}</button>
            <button type="button" onClick={() => setForm(null)}>Cancel</button>
          </form>
        )}
        <ErrorNote error={error} />
        <h4>History</h4>
        <ul className="events">
          {data.history.length === 0 && <li className="who">Nobody has touched this ticket yet.</li>}
          {data.history.map(e => (
            <li key={e.id}>
              <div>{e.kind} by {e.actor}{e.toState && e.kind !== 'note' ? `: ${e.fromState ?? '?'} → ${e.toState}` : ''}{(e.kind === 'assigned' || e.kind === 'claimed') ? `, owner ${e.toOwner ?? 'nobody'}` : ''}</div>
              <div className="who">{when(e.occurredAt)}</div>
              {e.note && <div className="text">{e.note}</div>}
            </li>
          ))}
        </ul>
        <div className="row"><button onClick={() => void openConversation()}>Open the conversation</button></div>
      </section>
      {conversation && (
        <section>
          <h2>Conversation <span className="mono">{conversation.conversationId}</span></h2>
          <Notice>Opening this conversation was recorded against your account. {conversation.notPersisted}</Notice>
          <p className="hint">{conversation.tickets.length === 0 ? 'No tickets in this conversation.' : `Tickets raised in this conversation: ${conversation.tickets.map(x => x.ticketNumber).join(', ')}`}</p>
          <div className="transcript">
            {conversation.messages.length === 0 && <Empty>No messages are stored for this conversation.</Empty>}
            {conversation.messages.map((m, i) => (
              <div key={i} className={`msg ${m.type}`}>
                <span className="at">{m.type.toLowerCase()} · {when(m.at)}</span>
                {m.type === 'ASSISTANT' ? <Markdown text={m.content} /> : m.content}
              </div>
            ))}
          </div>
        </section>
      )}
    </>
  );
}
