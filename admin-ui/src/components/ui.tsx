import type { ReactNode } from 'react';
import { ApiError } from '../api';

export function ErrorNote({ error }: { error: unknown }) {
  if (!error) return null;
  const message = error instanceof Error ? error.message : String(error);
  const conflict = error instanceof ApiError && error.status === 409;
  return <p className={conflict ? 'note conflict' : 'note error'} role="alert">{message}{conflict ? ' Reloaded.' : ''}</p>;
}

export function Pill({ kind, children }: { kind: string; children: ReactNode }) {
  return <span className={`pill ${kind}`}>{children}</span>;
}

export function Pager({ page, size, total, onPage }: { page: number; size: number; total: number; onPage: (p: number) => void }) {
  const pages = Math.max(1, Math.ceil(total / size));
  const first = total === 0 ? 0 : page * size + 1;
  const last = Math.min(total, (page + 1) * size);
  return (
    <div className="pager">
      <button disabled={page <= 0} onClick={() => onPage(page - 1)} aria-label="previous page">‹</button>
      <span>{total === 0 ? 'nothing' : `${first}–${last} of ${total}`}</span>
      <button disabled={page + 1 >= pages} onClick={() => onPage(page + 1)} aria-label="next page">›</button>
    </div>
  );
}

export function Empty({ children }: { children: ReactNode }) {
  return <p className="empty">{children}</p>;
}

export function Notice({ children }: { children: ReactNode }) {
  return <p className="notice">{children}</p>;
}
