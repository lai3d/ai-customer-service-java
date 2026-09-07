import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { api, type TelegramMode, type TelegramTest, type TelegramView } from '../api';
import { ErrorNote } from '../components/ui';
import { when } from '../format';

/**
 * A tenant's Telegram bot (docs/channels.md): one bot from BotFather, talking to the same
 * chat service as the widget. The token is checked against Telegram before it is stored and
 * shown masked from then on; polling suits a laptop or one chat process, webhooks a
 * deployment with a public origin. Shown to platform staff on the tenant's page and to a
 * tenant's own admins on their account page.
 */
export function TelegramSection({ tenantId }: { tenantId: string }) {
  const [view, setView] = useState<TelegramView | null | undefined>(undefined);
  const [editing, setEditing] = useState(false);
  const [token, setToken] = useState('');
  const [mode, setMode] = useState<TelegramMode>('polling');
  const [test, setTest] = useState<TelegramTest | null>(null);
  const [busy, setBusy] = useState(false);
  const [status, setStatus] = useState('');
  const [error, setError] = useState<unknown>(null);
  const load = useCallback(() => api.telegram(tenantId).then(v => { setView(v ?? null); setError(null); }, setError), [tenantId]);
  useEffect(() => { void load(); setTest(null); setEditing(false); }, [load]);
  const save = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    try { const saved = await api.saveTelegram(tenantId, token.trim(), mode); setEditing(false); setToken(''); setTest(null); setStatus(`Bot @${saved.bot.botUsername} connected in ${saved.bot.mode} mode.`); setError(null); await load(); }
    catch (err) { setError(err); } finally { setBusy(false); }
  };
  const runTest = async () => {
    setBusy(true);
    try { setTest(await api.testTelegram(tenantId)); setError(null); } catch (err) { setError(err); } finally { setBusy(false); }
  };
  const remove = async () => {
    setBusy(true);
    try { await api.deleteTelegram(tenantId); setTest(null); setStatus('Bot removed; polling stopped or the webhook deleted.'); setError(null); await load(); }
    catch (err) { setError(err); } finally { setBusy(false); }
  };
  const startEdit = () => { setMode(view?.bot.mode ?? 'polling'); setToken(''); setEditing(true); setStatus(''); };
  return (
    <section id="telegram">
      <h3>Telegram</h3>
      <p className="hint">One bot per tenant, from BotFather, answering on the same chat service as the widget. The token is checked against Telegram before it is stored and shown masked from then on. <b>Polling</b> suits a laptop or a single chat process; <b>webhooks</b> need the deployment's public address and suit replicas.</p>
      {view === undefined && <p className="hint">Loading…</p>}
      {view === null && !editing && <p className="hint">No bot connected.</p>}
      {view && !editing && (
        <dl className="facts">
          <dt>Bot</dt><dd className="mono">@{view.bot.botUsername}</dd>
          <dt>Token</dt><dd className="mono">{view.bot.botToken}</dd>
          <dt>Mode</dt><dd>{view.bot.mode}{view.bot.mode === 'polling' ? (view.polling ? ' · polling now' : ' · not polling in this process') : ''}</dd>
          {view.webhookUrl && <><dt>Webhook</dt><dd className="mono">{view.webhookUrl}</dd></>}
          <dt>Configured</dt><dd>{when(view.bot.configuredAt)} by {view.bot.configuredBy}</dd>
        </dl>
      )}
      {!editing && view !== undefined && (
        <div className="row">
          <button type="button" className="primary" onClick={startEdit}>{view ? 'Change…' : 'Connect a bot…'}</button>
          {view && <button type="button" onClick={() => void runTest()} disabled={busy}>Test</button>}
          {view && <button type="button" className="danger" onClick={() => void remove()} disabled={busy}>Remove</button>}
        </div>
      )}
      {editing && (
        <form className="row" onSubmit={save}>
          <label>Bot token <input type="password" value={token} onChange={e => setToken(e.target.value)} placeholder="123456:ABC-…" size={40} required autoComplete="off" autoFocus /></label>
          <label>Mode
            <select value={mode} onChange={e => setMode(e.target.value as TelegramMode)}>
              <option value="polling">polling (this process asks Telegram)</option>
              <option value="webhook">webhook (Telegram posts to the public address)</option>
            </select>
          </label>
          <button className="primary" disabled={busy}>{busy ? 'Checking with Telegram…' : 'Connect'}</button>
          <button type="button" onClick={() => setEditing(false)}>Cancel</button>
        </form>
      )}
      {test && (test.ok
        ? <p className="note">Telegram answered: <b>@{test.botUsername}</b>.</p>
        : <p className="note error">Telegram did not answer: {test.error}</p>)}
      {status && <p className="note">{status}</p>}
      <ErrorNote error={error} />
      <details>
        <summary className="hint">How the tenant gets a bot token</summary>
        <ol className="hint">
          <li>In Telegram, talk to <span className="mono">@BotFather</span>: <span className="mono">/newbot</span>, a name, a username ending in <span className="mono">bot</span>.</li>
          <li>BotFather answers with the token, <span className="mono">123456:ABC-…</span>; paste it here and choose the mode.</li>
          <li>Press Test: Telegram answers with the bot's username. Customers then write to the bot; <span className="mono">/start</span> is greeted in their language, <span className="mono">/new</span> starts a new conversation, anything else is a turn.</li>
        </ol>
      </details>
    </section>
  );
}
