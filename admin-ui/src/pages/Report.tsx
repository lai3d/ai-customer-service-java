import { useEffect, useState } from 'react';
import { useSearchParams } from 'react-router';
import { api, type PilotReport } from '../api';
import { useAuth } from '../auth';
import { TenantPicker } from '../components/TenantPicker';
import { ErrorNote } from '../components/ui';
import { when } from '../format';

/**
 * The numbers a pilot customer is shown: how often a conversation was settled without a
 * person, how often the answers were right, and what a conversation cost. One tenant, one
 * window, each number with its definition, and the whole thing as text to paste into a
 * message to the customer.
 */
const pct = (r: number | null) => (r === null ? '—' : `${Math.round(r * 1000) / 10}%`);
const usd = (v: number | null) => (v === null ? '—' : `$${v.toFixed(v < 0.1 ? 4 : 2)}`);

export function lines(r: PilotReport): string[] {
  const out = [
    `Pilot report · tenant ${r.tenant} · ${when(r.from)} to ${when(r.to)}`,
    `Conversations: ${r.conversations} (${r.turns} turns)`,
    `Settled without a person: ${pct(r.deflectionRate)} (${r.escalated} escalated, ${r.flagged} flagged by staff)`,
  ];
  if (r.evaluation) out.push(`Answer quality (evaluation run #${r.evaluation.runId}, ${when(r.evaluation.finishedAt)}): ${pct(r.evaluation.passRate)} of ${r.evaluation.cases} golden cases passed; retrieval ${pct(r.evaluation.retrievalHitRate)}, answers ${pct(r.evaluation.answerPassRate)}`);
  else out.push('Answer quality: no evaluation run yet');
  out.push(`Model spend: ${usd(r.costUsd)} (${r.inputTokens} input, ${r.outputTokens} output tokens${r.unmeteredTurns ? `, ${r.unmeteredTurns} turns without usage` : ''}${r.unpricedModels.length ? `; unpriced: ${r.unpricedModels.join(', ')}` : ''})`);
  out.push(`Per conversation: ${usd(r.costPerConversationUsd)}`);
  return out;
}

export function ReportPage() {
  const { me } = useAuth();
  const [params, setParams] = useSearchParams();
  const tenant = me?.tenant ? me.tenant.id : (params.get('tenant') || 'default');
  const [days, setDays] = useState(Number(params.get('days') ?? '30'));
  const [report, setReport] = useState<PilotReport | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [copied, setCopied] = useState(false);
  useEffect(() => { setReport(null); api.pilotReport(tenant, days).then(r => { setReport(r); setError(null); }, setError); }, [tenant, days]);
  const chooseTenant = (id: string) => { const p = new URLSearchParams(params); if (id === 'default') p.delete('tenant'); else p.set('tenant', id); setParams(p); };
  const copy = async () => { if (!report) return; try { await navigator.clipboard.writeText(lines(report).join('\n')); setCopied(true); } catch { setCopied(false); } };
  const card = (key: string, label: string, value: string) => (
    <div className="stat" key={key} title={report?.definitions[key]}><div className="g">Pilot</div><div className="v">{value}</div><div className="l">{label}</div></div>
  );
  return (
    <section>
      <h2>Pilot report</h2>
      <TenantPicker value={tenant} onChange={chooseTenant} />
      <p className="hint">What a customer pays for: how often a conversation was settled without a person, whether the answers were right, and what a conversation cost. Hover a number for its definition; the definitions are listed below.</p>
      <p className="row">
        <label>Window
          <select value={days} onChange={e => setDays(Number(e.target.value))}>
            {[7, 14, 30, 90].map(d => <option key={d} value={d}>last {d} days</option>)}
          </select>
        </label>
        {report && <span className="hint">{when(report.from)} – {when(report.to)}</span>}
        {report && <button type="button" onClick={() => void copy()}>{copied ? 'Copied' : 'Copy as text'}</button>}
      </p>
      <ErrorNote error={error} />
      {report && (
        <>
          <div className="stats">
            {card('conversations', 'conversations', String(report.conversations))}
            {card('deflectionRate', 'settled without a person', pct(report.deflectionRate))}
            {card('escalated', 'escalated to a person', String(report.escalated))}
            {card('flagged', 'answers flagged by staff', String(report.flagged))}
            {card('evaluation', report.evaluation ? `golden cases passed (run #${report.evaluation.runId})` : 'no evaluation run yet', report.evaluation ? pct(report.evaluation.passRate) : '—')}
            {card('costUsd', 'model spend', usd(report.costUsd))}
            {card('costPerConversationUsd', 'per conversation', usd(report.costPerConversationUsd))}
            {card('inputTokens', 'tokens in / out', `${report.inputTokens} / ${report.outputTokens}`)}
          </div>
          {report.unpricedModels.length > 0 && <p className="note error">No price is configured for {report.unpricedModels.join(', ')}; their spend is unknown, not zero (app.cost.prices).</p>}
          {report.unmeteredTurns > 0 && <p className="hint">{report.unmeteredTurns} turns ended without usage from the provider; their cost is unknown, not zero.</p>}
          <details>
            <summary className="hint">What each number means</summary>
            <dl className="facts">
              {Object.entries(report.definitions).map(([k, v]) => [<dt key={k + 't'}>{k}</dt>, <dd key={k + 'd'}>{v}</dd>])}
            </dl>
          </details>
        </>
      )}
    </section>
  );
}
