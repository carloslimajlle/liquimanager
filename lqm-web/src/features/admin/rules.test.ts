import { describe, expect, it } from 'vitest';

import { moveItem, stateOf, toggle } from './rules';

const NOW = Date.parse('2026-11-02T12:00:00Z');

describe('stateOf', () => {
  it('is active before the expiry', () => {
    expect(stateOf({ expiresAt: '2026-11-03T00:00:00Z' }, NOW)).toBe('active');
  });

  it('is expired at and after the expiry', () => {
    expect(stateOf({ expiresAt: '2026-11-02T12:00:00Z' }, NOW)).toBe('expired');
    expect(stateOf({ expiresAt: '2026-10-01T00:00:00Z' }, NOW)).toBe('expired');
  });

  it('is active when there is no expiry', () => {
    expect(stateOf({}, NOW)).toBe('active');
  });

  it('is revoked even when it also expired', () => {
    expect(stateOf({ revokedAt: '2026-10-02T00:00:00Z', expiresAt: '2026-10-01T00:00:00Z' }, NOW)).toBe('revoked');
  });
});

describe('toggle', () => {
  it('adds what is missing and removes what is there, leaving the original alone', () => {
    const list = ['a', 'b'];
    expect(toggle(list, 'c')).toEqual(['a', 'b', 'c']);
    expect(toggle(list, 'a')).toEqual(['b']);
    expect(list).toEqual(['a', 'b']);
  });
});

describe('moveItem', () => {
  it('moves an item up and down', () => {
    expect(moveItem(['DEV', 'HML', 'PRD'], 2, 0)).toEqual(['PRD', 'DEV', 'HML']);
    expect(moveItem(['DEV', 'HML', 'PRD'], 0, 1)).toEqual(['HML', 'DEV', 'PRD']);
  });

  it('clamps to the ends and ignores a move that goes nowhere or starts outside', () => {
    expect(moveItem(['a', 'b', 'c'], 0, -1)).toEqual(['a', 'b', 'c']);
    expect(moveItem(['a', 'b', 'c'], 2, 9)).toEqual(['a', 'b', 'c']);
    expect(moveItem(['a', 'b', 'c'], 5, 0)).toEqual(['a', 'b', 'c']);
    expect(moveItem(['a', 'b', 'c'], 0, 9)).toEqual(['b', 'c', 'a']);
  });

  it('does not change the list it is given', () => {
    const list = ['a', 'b'];
    moveItem(list, 0, 1);
    expect(list).toEqual(['a', 'b']);
  });
});
