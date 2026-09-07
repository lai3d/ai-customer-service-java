import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { Link, useSearchParams } from 'react-router';
import { api, type CaseInput, type Deflection, type EvaluationRun, type GoldenCase, type RunDetail } from '../api';
import { useAuth } from '../auth';
import { TenantPicker } from '../components/TenantPicker';
import { Empty, ErrorNote, Pill } from '../components/ui';
import { millis, when } from '../format';
import { joinList, percent, splitList } from '../lists';

/**
 * The golden set and the runs that score it (docs/evaluation.md): a question and what a
 * correct answer must satisfy, asked through the real chat path and scored. Every case in a
 * run is a paid model call, so a run is started on purpose, by an admin, and polled like a
 * publication or an import. The deflection rate is the other number a customer pays for.
 */
const EMPTY: CaseInput = { question: '', language: 'en', expectedEntryIds: [], mustContain: [], anyOf: [], mustNotContain: [], expectTool: null, expectRefusal: false, enabled: true, note: null };

function CaseForm({ initial, onSave, onCancel }: { initial: CaseInput; onSave: (input: CaseInput) => Promise<void>; onCancel: () => void }) {
  const [question, setQuestion] = useState(initial.question);
  const [language, setLanguage] = useState(initial.language);
  const [expected, setExpected] = useState(joinList(initial.expectedEntryIds));
  const [must, setMust] = useState(joinList(initial.mustContain));
  const [any, setAny] = useState(joinList(initial.anyOf));
  const [mustNot, setMustNot] = useState(joinList(initial.mustNotContain));
  const [tool, setTool] = useState(initial.expectTool ?? '');
  const [refusal, setRefusal] = useState(initial.expectRefusal);
  const [enabled, setEnabled] = useState(initial.enabled);
  const [note, setNote] = useState(initial.note ?? '');
  const [error, setError] = useState<unknown>(null);
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    try {
      await onSave({ question: question.trim(), language: language.trim(), expectedEntryIds: splitList(expected), mustContain: splitList(must), anyOf: splitList(any),
        mustNotContain: splitList(mustNot), expectTool: tool.trim() || null, expectRefusal: refusal, enabled, note: note.trim() || null });
      setError(null);
    } catch (err) { setError(err); }
  };
  return (
    <form className="row" onSubmit={submit}>
      <label>Question <input value={question} onChange={e => setQuestion(e.target.value)} size={48} required autoFocus /></label>
      <label>Language <input value={language} onChange={e => setLanguage(e.target.value)} size={4} required /></label>
      <label>Expected entry ids <input value={expected} onChange={e => setExpected(e.target.value)} placeholder="shipping-cost, returns-window" size={30} title="every one must be among the passages retrieved" /></label>
      <label>Must contain <input value={must} onChange={e => setMust(e.target.value)} placeholder="30 days, prepaid" size={30} title="every phrase must appear in the answer" /></label>
      <label>Any of <input value={any} onChange={e => setAny(e.target.value)} placeholder="at least one of these" size={30} /></label>
      <label>Must not contain <input value={mustNot} onChange={e => setMustNot(e.target.value)} placeholder="none of these" size={30} /></label>
      <label>Expect tool
        <select value={tool} onChange={e => setTool(e.target.value)}>
          <option value="">none</option>
          <option value="lookup_order_status">lookup_order_status</option>
          <option value="create_support_ticket">create_support_ticket</option>
        </select>
      </label>
      <label><input type="checkbox" checked={refusal} onChange={e => setRefusal(e.target.checked)} /> expect a refusal (outside the knowledge)</label>
      <label><input type="checkbox" checked={enabled} onChange={e => setEnabled(e.target.checked)} /> enabled</label>
      <label>Note <input value={note} onChange={e => setNote(e.target.value)} size={30} /></label>
      <button className="primary">Save case</button>
      <button type="button" onClick={onCancel}>Cancel</button>
      <ErrorNote error={error} />
    </form>
  );
}

function expectations(c: GoldenCase): string {
  const parts: string[] = [];
  if (c.expectedEntryIds.length) parts.push(`retrieves ${c.expectedEntryIds.join(', ')}`);
  if (c.mustContain.length) parts.push(`says ${c.mustContain.join(' + ')}`);
  if (c.anyOf.length) parts.push(`one of ${c.anyOf.join(' | ')}`);
  if (c.mustNotContain.length) parts.push(`never ${c.mustNotContain.join(' | ')}`);
  if (c.expectTool) parts.push(`calls ${c.expectTool}`);
  if (c.expectRefusal) parts.push('refuses');
  return parts.join('; ') || 'nothing checked';
}

export function EvaluationPage() {
  const { me } = useAuth();
  const admin = me!.role === 'admin';
  const [params, setParams] = useSearchParams();
  const tenant = me?.tenant ? me.tenant.id : (params.get('tenant') || 'default');
  const chooseTenant = (id: string) => { const p = new URLSearchParams(params); if (id === 'default') p.delete('tenant'); else p.set('tenant', id); setParams(p); setOpenRun(null); setEditing(null); };
  const [cases, setCases] = useState<GoldenCase[] | null>(null);
  const [runs, setRuns] = useState<EvaluationRun[] | null>(null);
  const [deflection, setDeflection] = useState<Deflection | null>(null);
  const [days, setDays] = useState(7);
  const [openRun, setOpenRun] = useState<RunDetail | null>(null);
  const [editing, setEditing] = useState<'new' | GoldenCase | null>(null);
  const [runNote, setRunNote] = useState('');
  const [starting, setStarting] = useState(false);
  const [status, setStatus] = useState('');
  const [error, setError] = useState<unknown>(null);
  const load = useCallback(() => Promise.all([api.evaluationCases(tenant), api.runs(tenant), api.deflection(tenant, days)])
    .then(([c, r, d]) => { setCases(c); setRuns(r); setDeflection(d); setError(null); }, setError), [tenant, days]);
  useEffect(() => { void load(); }, [load]);
  // A run scores every case through the model; poll its row until it is done or failed.
  useEffect(() => {
    if (!runs?.some(r => r.state === 'running')) return;
    const timer = setInterval(() => { void load(); if (openRun?.run.state === 'running') api.run(tenant, openRun.run.id).then(setOpenRun, setError); }, 3000);
    return () => clearInterval(timer);
  }, [runs, openRun, tenant, load]);
  const start = async () => {
    setStarting(true);
    try { const run = await api.startRun(tenant, runNote.trim()); setRunNote(''); setStatus(`Run #${run.id} started over ${run.cases} cases; every case is a model call.`); setError(null); await load(); }
    catch (err) { setError(err); } finally { setStarting(false); }
  };
  const open = (run: EvaluationRun) => api.run(tenant, run.id).then(setOpenRun, setError);
  const save = async (input: CaseInput) => {
    if (editing === 'new') await api.createCase(tenant, input); else if (editing) await api.updateCase(tenant, editing.id, input);
    setEditing(null); setStatus('Case saved.'); await load();
  };
  const remove = async (c: GoldenCase) => {
    try { await api.deleteCase(c.id); setStatus(`Deleted case #${c.id}.`); setError(null); await load(); } catch (err) { setError(err); }
  };
  const enabledCount = cases?.filter(c => c.enabled).length ?? 0;
  return (
    <>
      <section>
        <h2>Evaluation</h2>
        <TenantPicker value={tenant} onChange={chooseTenant} />
        <p className="hint">Two numbers a customer pays for: how often the assistant answered correctly, measured by asking a golden set of questions through the real chat path and checking facts in the answers; and how often a conversation was settled without a person, the deflection rate.</p>
        {deflection && (
          <div className="stats">
            <div className="stat" title={deflection.definition}><div className="g">Deflection · {deflection.days} days</div><div className="v">{Math.round(deflection.deflectionRate * 1000) / 10}%</div><div className="l">{deflection.conversations} conversations, {deflection.escalated} escalated, {deflection.flagged} flagged</div></div>
            {runs && runs.find(r => r.state === 'done') && (() => { const r = runs.find(x => x.state === 'done')!; return (
              <div className="stat" title={`Run #${r.id}, ${when(r.finishedAt)}`}><div className="g">Latest run · #{r.id}</div><div className="v">{percent(r.passed, r.cases)}</div><div className="l">{r.passed} of {r.cases} passed · retrieval {percent(r.retrievalHits, r.cases)} · answers {percent(r.answerPasses, r.cases)}</div></div>
            ); })()}
          </div>
        )}
        <p className="row">
          <label>Window
            <select value={days} onChange={e => setDays(Number(e.target.value))}>
              {[7, 14, 30, 90].map(d => <option key={d} value={d}>{d} days</option>)}
            </select>
          </label>
          {deflection && <span className="hint">{deflection.definition}</span>}
        </p>
        {status && <p className="note">{status}</p>}
        <ErrorNote error={error} />
      </section>

      <section>
        <h2>Runs</h2>
        {admin && (
          <div className="row">
            <label>Note <input value={runNote} onChange={e => setRunNote(e.target.value)} placeholder="what changed since the last run (optional)" size={40} /></label>
            <button className="primary" disabled={starting || enabledCount === 0 || !!runs?.some(r => r.state === 'running')} onClick={() => void start()}>{starting ? 'Starting…' : `Run ${enabledCount} cases`}</button>
            <span className="hint">One run per tenant at a time; each case is a paid model call.</span>
          </div>
        )}
        {runs && runs.length === 0 && <Empty>No runs yet.</Empty>}
        {runs && runs.length > 0 && (
          <table>
            <thead><tr><th>#</th><th>State</th><th>Passed</th><th>Retrieval</th><th>Answers</th><th>Tools</th><th>Tokens</th><th>Model</th><th>Note</th><th>Started</th><th></th></tr></thead>
            <tbody>
              {runs.map(r => (
                <tr key={r.id}>
                  <td>#{r.id}</td>
                  <td><Pill kind={r.state}>{r.state}</Pill>{r.error && <div className="hint">{r.error}</div>}</td>
                  <td>{r.passed} / {r.cases}</td>
                  <td>{percent(r.retrievalHits, r.cases)}</td>
                  <td>{percent(r.answerPasses, r.cases)}</td>
                  <td>{percent(r.toolPasses, r.cases)}</td>
                  <td>{r.inputTokens} in / {r.outputTokens} out</td>
                  <td className="mono">{r.model ?? '—'}</td>
                  <td>{r.note ?? ''}</td>
                  <td>{r.requestedBy}, {when(r.startedAt)}{r.finishedAt ? ` · ${millis(r.startedAt, r.finishedAt)}` : ''}</td>
                  <td><button onClick={() => void open(r)}>Results</button></td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        {openRun && (
          <div>
            <h3>Run #{openRun.run.id} · {openRun.run.passed} of {openRun.run.cases} passed <button type="button" onClick={() => setOpenRun(null)}>Close</button></h3>
            {openRun.results.length === 0 ? <p className="hint">No results yet.</p> : (
              <table>
                <thead><tr><th>Case</th><th>Question</th><th>Retrieval</th><th>Answer</th><th>Tool</th><th>Failures</th><th>Answer given</th><th>Conversation</th></tr></thead>
                <tbody>
                  {openRun.results.map(x => (
                    <tr key={x.caseId} className={x.passed ? '' : 'miss'}>
                      <td>#{x.caseId}</td>
                      <td>{x.question}</td>
                      <td>{x.retrievalHit ? '✓' : '✗'} <span className="hint mono">{x.retrieved.join(' ')}</span></td>
                      <td>{x.answerPass ? '✓' : '✗'}</td>
                      <td>{x.toolPass ? '✓' : '✗'} <span className="hint mono">{x.tools.join(' ')}</span></td>
                      <td>{x.failures ?? ''}</td>
                      <td className="hint">{x.answer ? (x.answer.length > 160 ? x.answer.slice(0, 160) + '…' : x.answer) : '—'}</td>
                      <td><Link to={`/conversations/${x.conversationId}`} className="mono">open</Link></td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </div>
        )}
      </section>

      <section>
        <h2>Golden set</h2>
        <p className="hint">A question and what a correct answer must satisfy. Phrases are facts a wrong answer would get wrong, matched after normalisation; the rubric checks facts, not prose. Lists take commas between items.</p>
        {admin && !editing && <p className="row"><button className="primary" onClick={() => setEditing('new')}>Add a case</button></p>}
        {editing && <CaseForm initial={editing === 'new' ? EMPTY : editing} onSave={save} onCancel={() => setEditing(null)} />}
        {cases && cases.length === 0 && <Empty>No cases for this tenant. A tenant's set comes from its pilot's real questions.</Empty>}
        {cases && cases.length > 0 && (
          <table>
            <thead><tr><th>#</th><th>Question</th><th>Lang</th><th>Expects</th><th>Enabled</th><th>Note</th>{admin && <th></th>}</tr></thead>
            <tbody>
              {cases.map(c => (
                <tr key={c.id}>
                  <td>#{c.id}</td>
                  <td>{c.question}</td>
                  <td className="mono">{c.language}</td>
                  <td className="hint">{expectations(c)}</td>
                  <td>{c.enabled ? 'yes' : 'no'}</td>
                  <td>{c.note ?? ''}</td>
                  {admin && <td className="row"><button onClick={() => setEditing(c)}>Edit</button><button className="danger" onClick={() => void remove(c)}>Delete</button></td>}
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
    </>
  );
}
