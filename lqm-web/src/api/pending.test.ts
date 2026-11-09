import { describe, expect, it } from 'vitest';

import { itemKey, unseen, type PendingItem } from './pending';

const item = (id: string): PendingItem => ({ kind: 'PROMOTION', id, requestedBy: 'ana', at: '2026-01-01T00:00:00Z' });

describe('unseen', () => {
  it('keeps what the person has not dismissed, so a new request brings the notice back', () => {
    const seen = new Set([itemKey(item('a'))]);

    expect(unseen([item('a'), item('b')], seen).map((i) => i.id)).toEqual(['b']);
  });

  it('is empty when everything open was dismissed', () => {
    expect(unseen([item('a')], new Set([itemKey(item('a'))]))).toEqual([]);
  });
});
