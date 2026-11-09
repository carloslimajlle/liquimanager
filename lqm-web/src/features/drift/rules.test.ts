import { describe, expect, it } from 'vitest';

import { barWidths, differences, driftTone, itemsLabel, rowTone, trend } from './rules';

describe('itemsLabel', () => {
  it('shows each kind of change with its sign and leaves out the empty ones', () => {
    expect(itemsLabel({ added: 2, removed: 1, modified: 3 })).toBe('+2 −1 ~3');
    expect(itemsLabel({ added: 0, removed: 1, modified: 0 })).toBe('−1');
    expect(itemsLabel({ added: 0, removed: 0, modified: 0 })).toBe('0');
  });
});

describe('tones', () => {
  it('drift warns, clean is fine, and a failed run is neither', () => {
    expect(driftTone({ status: 'SUCCESS', hasDrift: true })).toBe('warning');
    expect(driftTone({ status: 'SUCCESS', hasDrift: false })).toBe('success');
    expect(driftTone({ status: 'FAILED', hasDrift: null })).toBe('neutral');
    expect(rowTone({ status: 'FAILED', hasDrift: null })).toBe('danger');
    expect(rowTone({ status: 'SUCCESS', hasDrift: true })).toBe('warning');
    expect(rowTone({ status: 'SUCCESS', hasDrift: false })).toBeUndefined();
  });
});

describe('trend', () => {
  const weeks = (rates: Array<number | null>) => rates.map((rate, i) => ({ week: i, from: '', items: 0, rate }));

  it('compares the first and the last week that had a rate', () => {
    expect(trend(weeks([50, null, 10]))).toEqual({ direction: 'down', percent: 80 });
    expect(trend(weeks([10, 20]))).toEqual({ direction: 'up', percent: 100 });
    expect(trend(weeks([20, 20]))).toEqual({ direction: 'flat', percent: 0 });
  });

  it('has nothing to say with fewer than two rated weeks, or when it started at zero and rose', () => {
    expect(trend(weeks([null, 10]))).toBeNull();
    expect(trend(weeks([0, 10]))).toBeNull();
  });
});

describe('barWidths', () => {
  it('scales to the biggest', () => {
    expect(barWidths([{ type: 'COLUMN', count: 4 }, { type: 'TABLE', count: 1 }]).map((b) => b.width)).toEqual([100, 25]);
  });

  it('survives an empty list', () => {
    expect(barWidths([])).toEqual([]);
  });
});

describe('differences', () => {
  it('reads the before and after of a modified item', () => {
    expect(differences({ type: { before: 'varchar(50)', after: 'varchar(120)' } }))
      .toEqual([{ field: 'type', before: 'varchar(50)', after: 'varchar(120)' }]);
  });

  it('ignores the definition of an added object and anything that is not a pair', () => {
    expect(differences({ type: 'varchar(100)', nullable: 'true' })).toEqual([]);
    expect(differences(null)).toEqual([]);
  });
});
