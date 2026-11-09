import { describe, expect, it } from 'vitest';

import type { ActivityRow } from '../../api/dashboard';
import { newRowIds, signed, timeOfDay, trendHigherIsBetter, trendLowerIsBetter } from './rules';

const plain = (n: number) => String(n);

describe('trendHigherIsBetter', () => {
  it('is good when the number rises and bad when it falls', () => {
    expect(trendHigherIsBetter(100, plain)).toEqual({ value: '100', direction: 'up', good: true });
    expect(trendHigherIsBetter(-20, plain)).toEqual({ value: '-20', direction: 'down', good: false });
  });

  it('shows nothing without a base to compare with, or with no change', () => {
    expect(trendHigherIsBetter(null, plain)).toBeUndefined();
    expect(trendHigherIsBetter(undefined, plain)).toBeUndefined();
    expect(trendHigherIsBetter(0, plain)).toBeUndefined();
  });
});

describe('trendLowerIsBetter', () => {
  it('points where the number went, but a rise is the bad news', () => {
    expect(trendLowerIsBetter(25, plain)).toEqual({ value: '25', direction: 'up', good: false });
    expect(trendLowerIsBetter(-4.3, plain)).toEqual({ value: '-4.3', direction: 'down', good: true });
  });

  it('shows nothing without a base to compare with, or with no change', () => {
    expect(trendLowerIsBetter(null, plain)).toBeUndefined();
    expect(trendLowerIsBetter(0, plain)).toBeUndefined();
  });
});

describe('signed', () => {
  it('puts a plus on a rise and leaves a fall to carry its own minus', () => {
    expect(signed(12, plain)).toBe('+12');
    expect(signed(-3, plain)).toBe('-3');
  });
});

describe('newRowIds', () => {
  const row = (id: string): ActivityRow => ({
    id, startedAt: '2026-11-02T10:00:00Z', connection: 'c', environment: 'DEV', command: 'update', status: 'SUCCESS',
  });

  it('marks only what was not on screen before', () => {
    const fresh = newRowIds(new Set(['a', 'b']), [row('c'), row('a'), row('b')]);
    expect([...fresh]).toEqual(['c']);
  });

  it('marks everything on the first load, when nothing was shown before', () => {
    expect(newRowIds(new Set(), [row('a'), row('b')]).size).toBe(2);
  });
});

describe('timeOfDay', () => {
  it('shows the hour in the display timezone, not the machine\'s or UTC', () => {
    // 19:07 UTC is 16:07 in Sao Paulo (UTC-3, no daylight saving).
    expect(timeOfDay('2026-09-15T19:07:03Z', 'pt-BR')).toBe('16:07');
  });
});
