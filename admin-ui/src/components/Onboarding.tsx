import { useEffect, useState } from 'react';
import { Link } from 'react-router';
import { api } from '../api';

/**
 * What a pilot tenant needs before it can go live, read from what already exists rather
 * than kept as a separate state: a key to talk to the API, staff to work the tickets,
 * knowledge published, an order system, an evaluation run, and a channel. Each item
 * links to where it is done.
 */
type Step = { key: string; label: string; done: boolean; detail: string; to: string; optional?: boolean };

export function Onboarding({ tenantId }: { tenantId: string }) {
  const [steps, setSteps] = useState<Step[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    const t = tenantId === 'default' ? '' : `?tenant=${encodeURIComponent(tenantId)}`;
    Promise.all([
      api.tenant(tenantId), api.staff(tenantId), api.versions(tenantId), api.entries(tenantId, { size: 1 }),
      api.orderConnector(tenantId), api.runs(tenantId), api.telegram(tenantId),
    ]).then(([detail, staff, versions, entries, connector, runs, telegram]) => {
      const liveKeys = detail.keys.filter(k => !k.revokedAt);
      const admins = staff.filter(s => s.enabled && s.role === 'admin');
      const support = staff.filter(s => s.enabled && s.role === 'support');
      const done = runs.filter(r => r.state === 'done');
      setSteps([
        { key: 'key', label: 'An API key issued', done: liveKeys.length > 0, detail: liveKeys.length ? `${liveKeys.length} live (${liveKeys.map(k => k.kind).join(', ')})` : 'none live; the public API and the widget need one', to: '#keys' },
        { key: 'staff', label: "The tenant's own staff", done: admins.length > 0, detail: `${admins.length} admin, ${support.length} support`, to: `/staff${t}` },
        { key: 'knowledge', label: 'Knowledge published', done: !!versions.active && entries.total > 0, detail: versions.active ? `${entries.total} entries, active version ${versions.active}` : `${entries.total} entries, nothing published yet`, to: `/knowledge${t}` },
        { key: 'orders', label: 'An order system connected', done: !!connector, detail: connector ? `${connector.kind}: ${connector.shopDomain ?? connector.baseUrl}` : 'none; the assistant offers a ticket instead of an order lookup', to: '#orders', optional: true },
        { key: 'evaluation', label: 'An evaluation run', done: done.length > 0, detail: done.length ? `run #${done[0].id}: ${done[0].passed} of ${done[0].cases} passed` : 'none yet; the golden set proves the answers before customers do', to: `/evaluation${t}` },
        { key: 'telegram', label: 'A Telegram bot', done: !!telegram, detail: telegram ? `@${telegram.bot.botUsername}, ${telegram.bot.mode}` : 'none; the widget is the other channel', to: '#telegram', optional: true },
      ]);
      setError(null);
    }, err => setError(err instanceof Error ? err.message : String(err)));
  }, [tenantId]);
  if (error) return <p className="note error">Could not read the tenant's state: {error}</p>;
  if (!steps) return <p className="hint">Reading the tenant's state…</p>;
  const required = steps.filter(s => !s.optional);
  const ready = required.every(s => s.done);
  return (
    <section>
      <h3>Going live</h3>
      <p className="hint">{ready ? 'Everything a pilot needs is in place.' : `${required.filter(s => s.done).length} of ${required.length} required steps done.`} Optional steps depend on what the tenant sells and where its customers write.</p>
      <ul className="checklist">
        {steps.map(s => (
          <li key={s.key} className={s.done ? 'done' : 'todo'}>
            <span className="mark">{s.done ? '✓' : '○'}</span>
            {s.to.startsWith('#') ? <a href={s.to}>{s.label}</a> : <Link to={s.to}>{s.label}</Link>}
            {s.optional && <span className="hint"> (optional)</span>}
            <span className="hint"> · {s.detail}</span>
          </li>
        ))}
      </ul>
    </section>
  );
}
