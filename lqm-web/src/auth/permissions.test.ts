import { describe, expect, it } from 'vitest';

import { can, covers, hasNoAccess } from './permissions';

describe('covers', () => {
  it('matches the same permission, ignoring case', () => {
    expect(covers('catalog:edit', 'catalog:edit')).toBe(true);
    expect(covers('Catalog:Edit', 'catalog:edit')).toBe(true);
  });

  it('lets an environment list cover one of its environments', () => {
    expect(covers('deploy:dev,tst', 'deploy:tst')).toBe(true);
    expect(covers('deploy:dev,tst', 'deploy:prd')).toBe(false);
  });

  it('lets a wildcard cover every action of the same resource only', () => {
    expect(covers('rollback:*', 'rollback:prd')).toBe(true);
    expect(covers('rollback:*', 'rollback:approve')).toBe(true);
    expect(covers('rollback:*', 'deploy:prd')).toBe(false);
  });

  it('treats *:* as everything', () => {
    expect(covers('*:*', 'token:manage')).toBe(true);
  });

  it('does not let one resource cover another', () => {
    expect(covers('assignment:view', 'assignment:edit')).toBe(false);
    expect(covers('catalog', 'catalog:edit')).toBe(false);
  });
});

describe('can', () => {
  it('is true when any held permission covers it', () => {
    expect(can(['assignment:view', 'promote:hml,prd'], 'promote:prd')).toBe(true);
  });

  it('is false for nothing held', () => {
    expect(can([], 'catalog:edit')).toBe(false);
  });
});

describe('hasNoAccess', () => {
  it('is a signed-in person with no permission at all', () => {
    expect(hasNoAccess({ devMode: false, permissions: [] })).toBe(true);
  });

  it('is not anybody who holds something, however little', () => {
    expect(hasNoAccess({ devMode: false, permissions: ['assignment:view'] })).toBe(false);
  });

  it('is not the development chain, where nobody is identified and everything is allowed', () => {
    expect(hasNoAccess({ devMode: true, permissions: [] })).toBe(false);
  });
});
