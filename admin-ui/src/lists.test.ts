import { describe, expect, it } from 'vitest';
import { joinList, percent, splitList } from './lists';

describe('splitList', () => {
  it('splits on commas and newlines, trims, drops blanks', () => {
    expect(splitList(' 30 days, prepaid ,\n PayPal ,, ')).toEqual(['30 days', 'prepaid', 'PayPal']);
    expect(splitList('')).toEqual([]);
  });
  it('round-trips through joinList', () => { expect(splitList(joinList(['a', 'b c']))).toEqual(['a', 'b c']); });
});

describe('percent', () => {
  it('rounds to one decimal and dashes an empty whole', () => {
    expect(percent(1, 3)).toBe('33.3%');
    expect(percent(0, 0)).toBe('—');
    expect(percent(43, 43)).toBe('100%');
  });
});
