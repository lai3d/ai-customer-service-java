import { describe, expect, it } from 'vitest';
import { ago, localToIso, millis, short } from './format';

describe('ago', () => {
  const now = Date.parse('2026-09-06T00:00:00Z');
  it('rounds to the largest unit that reads naturally', () => {
    expect(ago('2026-09-05T23:59:30Z', now)).toBe('30s ago');
    expect(ago('2026-09-05T23:45:00Z', now)).toBe('15m ago');
    expect(ago('2026-09-05T20:00:00Z', now)).toBe('4h ago');
    expect(ago('2026-09-01T00:00:00Z', now)).toBe('5d ago');
  });
  it('is empty for nothing', () => { expect(ago(null)).toBe(''); });
});

describe('short and millis', () => {
  it('shortens ids and measures a turn', () => {
    expect(short('c0ffee00-1111-2222')).toBe('c0ffee00');
    expect(millis('2026-09-06T00:00:00.000Z', '2026-09-06T00:00:03.250Z')).toBe('3250 ms');
    expect(millis('2026-09-06T00:00:00.000Z', null)).toBe('');
  });
});

describe('localToIso', () => {
  it('passes an empty field through and keeps a real one an instant', () => {
    expect(localToIso('')).toBe('');
    expect(localToIso('2026-09-06T10:30')).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/);
  });
});
