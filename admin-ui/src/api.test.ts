import { describe, expect, it } from 'vitest';
import { csrfToken, query } from './api';

describe('csrfToken', () => {
  it('reads the XSRF-TOKEN cookie among others and decodes it', () => {
    expect(csrfToken('SESSION=abc; XSRF-TOKEN=a1b2%2Dc3; other=1')).toBe('a1b2-c3');
    expect(csrfToken('XSRF-TOKEN=only')).toBe('only');
  });
  it('is empty when the cookie is absent, so the first request can be the one that fetches it', () => {
    expect(csrfToken('SESSION=abc')).toBe('');
    expect(csrfToken('')).toBe('');
  });
});

describe('query', () => {
  it('drops empty, null and undefined values and keeps zero', () => {
    expect(query({ state: 'open', owner: '', page: 0, size: undefined, from: null })).toBe('?state=open&page=0');
    expect(query({})).toBe('');
  });
});
