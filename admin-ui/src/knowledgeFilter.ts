import type { KnowledgeEntry } from './api';

/**
 * The entries table after an import can hold hundreds of chunks, so it is filtered and
 * paged on the page: a text over the id, category and source, and a source to narrow to
 * (\'typed\' for entries a person wrote or the bundled corpus, or one source's own name).
 */
export const PAGE = 25;

export function filterEntries(entries: KnowledgeEntry[], text: string, source: string): KnowledgeEntry[] {
  const needle = text.trim().toLowerCase();
  return entries.filter(e => {
    if (source === 'typed' && e.source) return false;
    if (source && source !== 'typed' && e.source !== source) return false;
    if (!needle) return true;
    return [e.entryId, e.category, e.source ?? ''].some(v => v.toLowerCase().includes(needle));
  });
}

/** The distinct sources among the entries, in first-seen order. */
export function sourcesOf(entries: KnowledgeEntry[]): string[] {
  const seen: string[] = [];
  for (const e of entries) if (e.source && !seen.includes(e.source)) seen.push(e.source);
  return seen;
}

export function pageOf<T>(items: T[], page: number, size = PAGE): { items: T[]; page: number; pages: number } {
  const pages = Math.max(1, Math.ceil(items.length / size));
  const p = Math.min(Math.max(page, 0), pages - 1);
  return { items: items.slice(p * size, p * size + size), page: p, pages };
}
