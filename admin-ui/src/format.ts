export function when(iso: string | null | undefined): string {
  if (!iso) return '';
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleString();
}

export function ago(iso: string | null | undefined, now: number = Date.now()): string {
  if (!iso) return '';
  const ms = now - new Date(iso).getTime();
  if (!Number.isFinite(ms) || ms < 0) return when(iso);
  const s = Math.round(ms / 1000);
  if (s < 60) return `${s}s ago`;
  const m = Math.round(s / 60);
  if (m < 60) return `${m}m ago`;
  const h = Math.round(m / 60);
  if (h < 48) return `${h}h ago`;
  return `${Math.round(h / 24)}d ago`;
}

export function short(id: string | null | undefined, length = 8): string {
  return id ? id.slice(0, length) : '';
}

export function millis(from: string, to: string | null): string {
  if (!to) return '';
  const ms = new Date(to).getTime() - new Date(from).getTime();
  return ms >= 0 ? `${ms} ms` : '';
}

/** Turns a datetime-local input's value into the ISO instant the API wants, or an empty string. */
export function localToIso(value: string): string {
  if (!value) return '';
  const d = new Date(value);
  return Number.isNaN(d.getTime()) ? '' : d.toISOString();
}
