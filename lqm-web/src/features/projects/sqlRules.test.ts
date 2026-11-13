import { describe, expect, it } from 'vitest';

import { canGenerate, sqlFileName, sqlParams } from './sqlRules';

describe('sqlParams', () => {
  it('asks by the mode chosen and leaves the dialect out while it is automatic', () => {
    expect(sqlParams('release', ' release-2.1.0 ', '')).toEqual({ release: 'release-2.1.0' });
    expect(sqlParams('notIn', 'hml', '')).toEqual({ notIn: 'hml' });
    expect(sqlParams('tag', 'v1.0', 'postgresql')).toEqual({ tag: 'v1.0', dialect: 'postgresql' });
    expect(sqlParams('ids', 'abc', 'mssql')).toEqual({ ids: 'abc', dialect: 'mssql' });
  });
});

describe('canGenerate', () => {
  it('needs a value, and a release or tag without characters the API refuses', () => {
    expect(canGenerate('release', '')).toBe(false);
    expect(canGenerate('release', 'release 2')).toBe(false);
    expect(canGenerate('release', 'release-2.1.0')).toBe(true);
    expect(canGenerate('tag', 'v1/0')).toBe(false);
    expect(canGenerate('notIn', 'HML')).toBe(true);
    expect(canGenerate('notIn', ' ')).toBe(false);
  });
});

describe('sqlFileName', () => {
  it('is safe on every system and says what it holds', () => {
    expect(sqlFileName('payments', 'release', 'release-2.1.0')).toBe('payments-release-2.1.0.sql');
    expect(sqlFileName('payments', 'notIn', 'HML')).toBe('payments-pendentes-HML.sql');
    expect(sqlFileName('payments', 'ids', 'uuid')).toBe('payments-changeset.sql');
    expect(sqlFileName('a b/c', 'tag', 'v1')).toBe('a-b-c-v1.sql');
  });
});
