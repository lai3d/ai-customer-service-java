import { useEffect, useState } from 'react';
import { api, type Tenant } from '../api';
import { useAuth } from '../auth';

/**
 * Which tenant a page is about. Platform staff choose; a tenant's own staff see nothing here,
 * because every call is scoped to their tenant by the server. With {@code allowAll} the empty
 * value means every tenant, which is what a platform list shows by default.
 */
export function TenantPicker({ value, onChange, allowAll = false }: { value: string; onChange: (id: string) => void; allowAll?: boolean }) {
  const { me } = useAuth();
  const [tenants, setTenants] = useState<Tenant[] | null>(null);
  const platform = me?.tenant === null;
  useEffect(() => {
    if (platform) api.tenants().then(setTenants, () => setTenants([]));
  }, [platform]);
  if (!platform) return null;
  const options = tenants ?? [];
  const known = value === '' || options.some(t => t.id === value);
  return (
    <p className="row">
      <label>Tenant
        <select value={value} onChange={e => onChange(e.target.value)}>
          {allowAll && <option value="">every tenant</option>}
          {!known && <option value={value}>{value}</option>}
          {options.map(t => <option key={t.id} value={t.id}>{t.id}{t.name && t.id !== t.name ? ` · ${t.name}` : ''}{t.enabled ? '' : ' (disabled)'}</option>)}
        </select>
      </label>
    </p>
  );
}
