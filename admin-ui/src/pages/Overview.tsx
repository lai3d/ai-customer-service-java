import { useEffect, useState, type FormEvent } from 'react';
import { api, type Overview, type Stat } from '../api';
import { ErrorNote } from '../components/ui';
import { TenantPicker } from '../components/TenantPicker';
import { localToIso, when } from '../format';

export function OverviewPage() {
  const [data, setData] = useState<Overview | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [tenant, setTenant] = useState('');
  const load = (f?: string, t?: string, tenantId?: string) => api.overview({ from: f, to: t, tenant: tenantId || undefined }).then(setData, setError);
  useEffect(() => { void load(undefined, undefined, tenant); }, [tenant]);
  const submit = (e: FormEvent) => { e.preventDefault(); void load(localToIso(from), localToIso(to), tenant); };
  const groups: [string, Stat[]][] = data ? [['Turns', data.turns], ['Tickets', data.tickets], ['Feedback', data.feedback], ['Knowledge', data.knowledge], ['Staff', data.staff]] : [];
  return (
    <section>
      <h2>Overview</h2>
      <TenantPicker value={tenant} onChange={setTenant} allowAll />
      <form className="row" onSubmit={submit}>
        <label>From <input type="datetime-local" value={from} onChange={e => setFrom(e.target.value)} /></label>
        <label>To <input type="datetime-local" value={to} onChange={e => setTo(e.target.value)} /></label>
        <button className="primary">Show</button>
        {data && <span className="hint">{when(data.from)} – {when(data.to)}</span>}
      </form>
      <ErrorNote error={error} />
      <div className="stats">
        {groups.flatMap(([group, stats]) => stats.map(s => (
          <div className="stat" key={s.key} title={s.definition}>
            <div className="g">{group}</div>
            <div className="v">{s.value === null ? '—' : `${s.value}${s.key.endsWith('Rate') ? '%' : ''}`}</div>
            <div className="l">{s.label}</div>
          </div>
        )))}
      </div>
      {data && (
        <details>
          <summary className="hint">What each number means</summary>
          <dl className="facts">
            {groups.flatMap(([group, stats]) => stats.map(s => [<dt key={s.key + 't'}>{group} · {s.label}</dt>, <dd key={s.key + 'd'}>{s.definition}</dd>]))}
          </dl>
        </details>
      )}
    </section>
  );
}
