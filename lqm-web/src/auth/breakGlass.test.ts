import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

type Module = typeof import('./breakGlass');

/** The tests run in Node: a window with a sessionStorage of its own, and a fresh module (the session is cached in it) each time. */
function fakeWindow() {
  const store = new Map<string, string>();
  vi.stubGlobal('window', {
    sessionStorage: {
      getItem: (key: string) => store.get(key) ?? null,
      setItem: (key: string, value: string) => void store.set(key, value),
      removeItem: (key: string) => void store.delete(key),
    },
    location: { assign: vi.fn() },
  });
  return store;
}

describe('break-glass session', () => {
  let store: Map<string, string>;
  let mod: Module;

  beforeEach(async () => {
    store = fakeWindow();
    vi.resetModules();
    mod = await import('./breakGlass');
  });
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  const answer = (status: number, body: unknown = {}) =>
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })));

  it('keeps the token the API gave, for this tab only, and sends the three factors', async () => {
    answer(200, { token: 'lqm_bg_abc', expiresAt: new Date(Date.now() + 3_600_000).toISOString() });

    const session = await mod.signInBreakGlass('glass', 'pw', '123456');

    expect(session.token).toBe('lqm_bg_abc');
    expect(mod.breakGlassSession()?.username).toBe('glass');
    expect(store.get('lqm.breakGlass')).toContain('lqm_bg_abc');
    const call = (fetch as unknown as ReturnType<typeof vi.fn>).mock.calls[0]!;
    expect(JSON.parse((call[1] as RequestInit).body as string)).toEqual({ username: 'glass', password: 'pw', code: '123456' });
  });

  it.each([[401, 'refused'], [429, 'locked'], [404, 'disabled'], [500, 'error']])('a %i answer is %s and keeps nothing', async (status, reason) => {
    answer(status);

    await expect(mod.signInBreakGlass('glass', 'pw', '000000')).rejects.toMatchObject({ reason });
    expect(mod.breakGlassSession()).toBeNull();
    expect(store.has('lqm.breakGlass')).toBe(false);
  });

  it('a network failure is an error, not a crash', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => { throw new TypeError('network'); }));

    await expect(mod.signInBreakGlass('glass', 'pw', '000000')).rejects.toBeInstanceOf(mod.BreakGlassError);
  });

  it('an expired session is not a session, and signing out forgets it', async () => {
    answer(200, { token: 'lqm_bg_old', expiresAt: new Date(Date.now() - 1000).toISOString() });
    await mod.signInBreakGlass('glass', 'pw', '123456');
    expect(mod.breakGlassSession()).toBeNull();

    answer(200, { token: 'lqm_bg_ok', expiresAt: new Date(Date.now() + 3_600_000).toISOString() });
    await mod.signInBreakGlass('glass', 'pw', '123456');
    expect(mod.breakGlassSession()).not.toBeNull();
    await mod.signOutBreakGlass();
    expect(mod.breakGlassSession()).toBeNull();
    expect(store.has('lqm.breakGlass')).toBe(false);
  });
});
