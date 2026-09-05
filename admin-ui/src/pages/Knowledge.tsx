import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { api, type KnowledgeEntry, type KnowledgeRevision, type Passage, type Versions } from '../api';
import { useAuth } from '../auth';
import { Empty, ErrorNote, Pill } from '../components/ui';
import { when } from '../format';

function LanguageForm({ entry, language, draft, published, onSaved }: {
  entry: KnowledgeEntry; language: string; draft: KnowledgeRevision | null; published: KnowledgeRevision | null; onSaved: () => void;
}) {
  const current = draft ?? published;
  const [question, setQuestion] = useState(current?.question ?? '');
  const [answer, setAnswer] = useState(current?.answer ?? '');
  const [note, setNote] = useState('');
  const [error, setError] = useState<unknown>(null);
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    try { await api.saveDraft(entry.entryId, language, question, answer, note); setError(null); onSaved(); } catch (err) { setError(err); }
  };
  return (
    <form className="turn" onSubmit={submit}>
      <h4>{language} · {draft ? 'draft' : published ? 'published' : 'new'}</h4>
      {published && draft && <p className="hint">Published text: {published.question} — {published.answer}</p>}
      <label>Question <input value={question} onChange={e => setQuestion(e.target.value)} size={60} required /></label>
      <label>Answer <textarea value={answer} onChange={e => setAnswer(e.target.value)} required /></label>
      <label>Note <input value={note} onChange={e => setNote(e.target.value)} placeholder="why this change (optional)" size={40} /></label>
      <div className="row">
        <button className="primary">Save draft</button>
        {draft && <button type="button" onClick={() => api.discardDraft(entry.entryId, language).then(onSaved, setError)}>Discard draft</button>}
      </div>
      <ErrorNote error={error} />
    </form>
  );
}

export function KnowledgePage() {
  const { me } = useAuth();
  const admin = me!.role === 'admin';
  const [entries, setEntries] = useState<KnowledgeEntry[] | null>(null);
  const [versions, setVersions] = useState<Versions | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [status, setStatus] = useState('');
  const [current, setCurrent] = useState<string | null>(null);
  const [newId, setNewId] = useState('');
  const [newCategory, setNewCategory] = useState('');
  const [newLanguage, setNewLanguage] = useState('');
  const [extraLanguages, setExtraLanguages] = useState<string[]>([]);
  const [publishing, setPublishing] = useState(false);
  const [publishNote, setPublishNote] = useState('');
  const [previewText, setPreviewText] = useState('');
  const [previewVersion, setPreviewVersion] = useState('');
  const [preview, setPreview] = useState<Passage[] | null>(null);

  const load = useCallback(() => Promise.all([api.entries(), api.versions()]).then(([e, v]) => { setEntries(e); setVersions(v); setError(null); }, setError), []);
  useEffect(() => { void load(); }, [load]);

  const entry = entries?.find(e => e.entryId === current) ?? null;
  const languages = entry ? Array.from(new Set([...entry.revisions.map(r => r.language), ...extraLanguages])) : [];

  const create = async () => {
    try { const e = await api.createEntry(newId.trim(), newCategory.trim()); setNewId(''); setNewCategory(''); setCurrent(e.entryId); setExtraLanguages([]); await load(); } catch (err) { setError(err); }
  };
  const retire = async () => {
    if (!entry) return;
    try { await api.retire(entry.entryId, !entry.retired); await load(); } catch (err) { setError(err); }
  };
  const publish = async (e: FormEvent) => {
    e.preventDefault();
    try {
      await api.publish(publishNote, versions?.active ?? null);
      setPublishing(false); setPublishNote(''); setStatus('Publishing… embedding the documents.');
      const started = Date.now();
      const poll = async () => {
        const v = await api.versions();
        setVersions(v);
        if (v.versions.some(x => x.state === 'building') && Date.now() - started < 120000) { setTimeout(() => void poll(), 1500); return; }
        await load();
        setStatus(`Publication finished. Active version: ${v.active ?? 'none'}.`);
      };
      setTimeout(() => void poll(), 1500);
    } catch (err) { setError(err); if ((err as { status?: number }).status === 409) await load(); }
  };
  const activate = async (version: string) => {
    try { await api.rollback(version, versions?.active ?? null); setStatus(`Activated ${version}.`); await load(); } catch (err) { setError(err); if ((err as { status?: number }).status === 409) await load(); }
  };
  const runPreview = async (e: FormEvent) => {
    e.preventDefault();
    try { setPreview(await api.preview(previewText, previewVersion || null)); setError(null); } catch (err) { setError(err); }
  };

  return (
    <>
      <section>
        <h2>Knowledge</h2>
        <p className="hint">{versions ? (versions.active ? `Active version: ${versions.active}. Drafts change nothing until published.` : 'No active version.') : ''}</p>
        <div className="row">
          <label>New entry id <input value={newId} onChange={e => setNewId(e.target.value)} placeholder="e.g. gift-wrap" /></label>
          <label>Category <input value={newCategory} onChange={e => setNewCategory(e.target.value)} placeholder="e.g. orders" /></label>
          <button type="button" onClick={() => void create()}>Create entry</button>
          {admin && !publishing && <button type="button" className="primary" onClick={() => setPublishing(true)}>Publish…</button>}
        </div>
        {publishing && (
          <form className="row" onSubmit={publish}>
            <label>What does this publication change? (optional) <input value={publishNote} onChange={e => setPublishNote(e.target.value)} size={50} autoFocus /></label>
            <button className="primary">Publish</button>
            <button type="button" onClick={() => setPublishing(false)}>Cancel</button>
          </form>
        )}
        {status && <p className="note">{status}</p>}
        <ErrorNote error={error} />
        {entries && (
          <table>
            <thead><tr><th>Entry</th><th>Category</th><th>Languages</th><th></th></tr></thead>
            <tbody>
              {entries.map(e => (
                <tr key={e.entryId}>
                  <td className="mono">{e.entryId}{e.retired ? ' (retired)' : ''}</td>
                  <td>{e.category}</td>
                  <td>{e.revisions.map(r => <Pill key={r.id} kind={r.state}>{r.language} {r.state}</Pill>).reduce<React.ReactNode[]>((acc, p, i) => (i ? [...acc, ' ', p] : [p]), [])}</td>
                  <td><button onClick={() => { setCurrent(e.entryId); setExtraLanguages([]); }}>Edit</button></td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        {entry && (
          <div>
            <h3>{entry.entryId} · {entry.category}{entry.retired ? ' · retired' : ''}</h3>
            {languages.map(language => {
              const draft = entry.revisions.find(r => r.language === language && r.state === 'draft') ?? null;
              const published = entry.revisions.find(r => r.language === language && r.state === 'published') ?? null;
              return <LanguageForm key={`${entry.entryId}:${language}:${draft?.id ?? 0}:${published?.id ?? 0}`} entry={entry} language={language} draft={draft} published={published} onSaved={() => void load()} />;
            })}
            <div className="row">
              <label>Add a language <input value={newLanguage} onChange={e => setNewLanguage(e.target.value)} placeholder="en, zh, …" size={6} /></label>
              <button type="button" onClick={() => { if (newLanguage.trim()) { setExtraLanguages([...extraLanguages, newLanguage.trim()]); setNewLanguage(''); } }}>Add</button>
              {admin && <button type="button" className="danger" onClick={() => void retire()}>{entry.retired ? 'Un-retire' : 'Retire'}</button>}
            </div>
          </div>
        )}
      </section>
      <section>
        <h2>Versions</h2>
        {versions && versions.versions.length === 0 && <Empty>No versions yet.</Empty>}
        {versions && versions.versions.length > 0 && (
          <table>
            <thead><tr><th>Version</th><th>State</th><th>Docs</th><th>Created</th><th>Note / error</th><th></th></tr></thead>
            <tbody>
              {versions.versions.map(v => (
                <tr key={v.version}>
                  <td className="mono">{v.version}</td>
                  <td><Pill kind={v.state}>{v.state}</Pill></td>
                  <td>{v.documentCount ?? ''}</td>
                  <td>{when(v.createdAt)} by {v.createdBy}</td>
                  <td className={v.error ? 'failure' : ''}>{v.error ?? v.note ?? ''}</td>
                  <td>{v.state === 'ready' && admin && <button onClick={() => void activate(v.version)}>Activate</button>}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
      <section>
        <h2>Retrieval preview</h2>
        <form className="row" onSubmit={runPreview}>
          <label>Question <input value={previewText} onChange={e => setPreviewText(e.target.value)} size={40} placeholder="what a customer might ask" required /></label>
          <label>Version
            <select value={previewVersion} onChange={e => setPreviewVersion(e.target.value)}>
              <option value="">active</option>
              {versions?.versions.filter(v => v.state === 'ready' || v.state === 'active').map(v => <option key={v.version} value={v.version}>{v.version} ({v.state})</option>)}
            </select>
          </label>
          <button>Search</button>
        </form>
        {preview && preview.length === 0 && <Empty>Nothing found.</Empty>}
        {preview && preview.length > 0 && (
          <table>
            <thead><tr><th>#</th><th>Entry</th><th>Language</th><th>Score</th><th>Text</th></tr></thead>
            <tbody>
              {preview.map((p, i) => (
                <tr key={p.id}><td>{i + 1}</td><td className="mono">{String(p.metadata.entry_id)}</td><td>{String(p.metadata.language ?? '')}</td><td>{(p.score ?? 0).toFixed(4)}</td><td>{p.text}</td></tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
    </>
  );
}
