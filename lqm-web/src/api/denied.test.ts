import { describe, expect, it, vi } from 'vitest';

import { deniedCount, reportDenied, subscribeDenied } from './denied';

describe('denied', () => {
  it('counts every refusal and tells the subscribers', () => {
    const listener = vi.fn();
    const stop = subscribeDenied(listener);
    const before = deniedCount();
    reportDenied();
    reportDenied();
    expect(deniedCount()).toBe(before + 2);
    expect(listener).toHaveBeenCalledTimes(2);
    stop();
    reportDenied();
    expect(listener).toHaveBeenCalledTimes(2);
  });
});
