import { describe, expect, it } from 'vitest';
import type { KnowledgeEntry } from './api';
import { filterEntries, pageOf, sourcesOf } from './knowledgeFilter';

const entry = (entryId: string, category: string, source: string | null): KnowledgeEntry =>
  ({ entryId, category, retired: false, createdAt: '', createdBy: 'x', revisions: [], sourceKind: source ? (source.endsWith('.pdf') ? 'pdf' : 'url') : null, source });

const entries = [
  entry('shipping-cost', 'shipping', null),
  entry('doc-a-1', 'document', 'https://shop.example.com/faq'),
  entry('doc-a-2', 'document', 'https://shop.example.com/faq'),
  entry('doc-b-1', 'document', 'returns.pdf'),
];

describe('filterEntries', () => {
  it('shows everything when nothing is asked', () => { expect(filterEntries(entries, '', '')).toHaveLength(4); });
  it('narrows to typed entries, or to one source', () => {
    expect(filterEntries(entries, '', 'typed').map(e => e.entryId)).toEqual(['shipping-cost']);
    expect(filterEntries(entries, '', 'returns.pdf').map(e => e.entryId)).toEqual(['doc-b-1']);
  });
  it('matches the text against id, category and source, case-insensitively', () => {
    expect(filterEntries(entries, 'SHOP.EXAMPLE', '').map(e => e.entryId)).toEqual(['doc-a-1', 'doc-a-2']);
    expect(filterEntries(entries, 'ship', '')).toHaveLength(1);
  });
});

describe('sourcesOf and pageOf', () => {
  it('lists each source once, in first-seen order', () => { expect(sourcesOf(entries)).toEqual(['https://shop.example.com/faq', 'returns.pdf']); });
  it('pages and clamps', () => {
    expect(pageOf([1, 2, 3, 4, 5], 1, 2)).toEqual({ items: [3, 4], page: 1, pages: 3 });
    expect(pageOf([1, 2, 3, 4, 5], 9, 2)).toEqual({ items: [5], page: 2, pages: 3 });
    expect(pageOf([], 0, 2)).toEqual({ items: [], page: 0, pages: 1 });
  });
});
