import { describe, expect, it } from 'vitest';

import { toQuery } from './client';
import { toApiParams } from './operations';

const NOW = new Date('2026-11-02T12:00:00.000Z');

describe('toApiParams', () => {
  it('turns a symbolic period into a concrete `from`', () => {
    expect(toApiParams({ period: '7d' }, NOW).from).toBe('2026-10-26T12:00:00.000Z');
    expect(toApiParams({ period: '30d' }, NOW).from).toBe('2026-10-03T12:00:00.000Z');
  });

  it('sends no lower bound for "all time", and never leaks the symbolic period', () => {
    const params = toApiParams({ period: 'all', status: 'FAILURE' }, NOW);

    expect(params.from).toBeUndefined();
    expect(params).not.toHaveProperty('period');
    expect(params.status).toBe('FAILURE');
  });
});

describe('toQuery', () => {
  it('drops empty values so the URL only carries the filters that are set', () => {
    expect(toQuery({ status: 'FAILURE', env: '', q: undefined, page: 0 })).toBe('?status=FAILURE&page=0');
  });

  it('is empty when nothing is set', () => {
    expect(toQuery({})).toBe('');
  });

  it('encodes values', () => {
    expect(toQuery({ q: 'a b&c' })).toBe('?q=a+b%26c');
  });
});
