import { useEffect, useState } from 'react';
import { api, type Tenant } from '../api';

/**
 * Which tenant a page is about. The list is an admin's to read; a support member sees the
 * one tenant every call defaults to, and the picker says so rather than failing the page.
 */
export function TenantPicker({ value, onChange }: { value: string; onChange: (id: string) => void }) {
  const [tenants, setTenants] = useState<Tenant[] | null>(null);
  const [denied, setDenied] = useState(false);
  useEffect(() => {
    api.tenants().then(setTenants, () => { setTenants([]); setDenied(true); });
  }, []);
  const options = tenants && tenants.length > 0 ? tenants : [{ id: 'default', name: 'Default tenant', enabled: true, createdAt: '' }];
  const known = options.some(t => t.id === value);
  return (
    <p className="row">
      <label>Tenant
        <select value={value} onChange={e => onChange(e.target.value)} disabled={denied}>
          {!known && <option value={value}>{value}</option>}
          {options.map(t => <option key={t.id} value={t.id}>{t.id}{t.name && t.id !== t.name ? ` · ${t.name}` : ''}{t.enabled ? '' : ' (disabled)'}</option>)}
        </select>
      </label>
      {denied && <span className="hint">Only admins can list tenants; this page shows the default tenant.</span>}
    </p>
  );
}
