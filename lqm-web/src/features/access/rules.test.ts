import { describe, expect, it } from 'vitest';

import { effective, needsApproval, splitList, violatesSod3 } from './rules';

describe('effective', () => {
  it('drops what the binding denies itself', () => {
    expect(effective(['deploy:*', 'exception:approve'], ['drift:run'], ['exception:approve']).sort())
      .toEqual(['deploy:*', 'drift:run']);
  });

  it('a wildcard denial removes everything it covers', () => {
    expect(effective(['deploy:dev,tst', 'assignment:view'], [], ['deploy:*'])).toEqual(['assignment:view']);
  });
});

describe('violatesSod3', () => {
  it('flags deploy to production together with approval', () => {
    expect(violatesSod3(['deploy:*', 'exception:approve'])).toBe(true);
    expect(violatesSod3(['deploy:prd', 'exception:approve'])).toBe(true);
  });

  it('leaves alone a deploy that cannot reach production, or no approval', () => {
    expect(violatesSod3(['deploy:dev,tst', 'exception:approve'])).toBe(false);
    expect(violatesSod3(['deploy:*'])).toBe(false);
    expect(violatesSod3(['exception:approve'])).toBe(false);
  });
});

describe('needsApproval', () => {
  it('wants an approver for a production environment and for break-glass', () => {
    expect(needsApproval('ENVIRONMENT', true)).toBe(true);
    expect(needsApproval('WORKSPACE', false, 'BREAKGLASS')).toBe(true);
    expect(needsApproval('ENVIRONMENT', false)).toBe(false);
    expect(needsApproval('PROJECT', false)).toBe(false);
  });
});

describe('splitList', () => {
  it('splits and trims', () => {
    expect(splitList(' a:b , c:d,, ')).toEqual(['a:b', 'c:d']);
  });
});
