/**
 * The emergency session (docs/09 § 8.4): for when the identity provider is down. The person types the account name, the password and a code
 * from their authenticator at /break-glass; the API answers with a short-lived token, which is kept in `sessionStorage` (closing the tab
 * ends it) and sent as the bearer instead of an access token. Everything done with it is audited on the server.
 */
export const BREAK_GLASS_PATH = '/break-glass';

const KEY = 'lqm.breakGlass';

export type BreakGlassSession = { token: string; expiresAt: string; username: string };

let current: BreakGlassSession | null | undefined;
const listeners = new Set<() => void>();

function readStored(): BreakGlassSession | null {
  try {
    const raw = window.sessionStorage.getItem(KEY);
    if (!raw) {
      return null;
    }
    const parsed = JSON.parse(raw) as BreakGlassSession;
    if (typeof parsed.token !== 'string' || Date.parse(parsed.expiresAt) <= Date.now()) {
      window.sessionStorage.removeItem(KEY);
      return null;
    }
    return parsed;
  } catch {
    return null;
  }
}

/** The emergency session in this tab, or null (also when it has expired). The same object until it changes. */
export function breakGlassSession(): BreakGlassSession | null {
  if (current === undefined || (current !== null && Date.parse(current.expiresAt) <= Date.now())) {
    current = readStored();
  }
  return current;
}

export function subscribeBreakGlass(listener: () => void): () => void {
  listeners.add(listener);
  return () => { listeners.delete(listener); };
}

function setSession(session: BreakGlassSession | null) {
  current = session;
  try {
    if (session) {
      window.sessionStorage.setItem(KEY, JSON.stringify(session));
    } else {
      window.sessionStorage.removeItem(KEY);
    }
  } catch {
    // Without storage the session lives only as long as the page; that is still enough to act.
  }
  listeners.forEach((listener) => listener());
}

/** Why a sign-in was not accepted, in terms the screen can word: wrong credentials, locked, or not enabled at all. */
export type BreakGlassFailure = 'refused' | 'locked' | 'disabled' | 'error';

export class BreakGlassError extends Error {
  readonly reason: BreakGlassFailure;

  constructor(reason: BreakGlassFailure) {
    super(reason);
    this.reason = reason;
  }
}

export async function signInBreakGlass(username: string, password: string, code: string): Promise<BreakGlassSession> {
  let response: Response;
  try {
    response = await fetch('/api/v1/auth/break-glass', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
      body: JSON.stringify({ username, password, code }),
    });
  } catch {
    throw new BreakGlassError('error');
  }
  if (response.status === 401) throw new BreakGlassError('refused');
  if (response.status === 429) throw new BreakGlassError('locked');
  if (response.status === 404) throw new BreakGlassError('disabled');
  if (!response.ok) throw new BreakGlassError('error');
  const body = (await response.json()) as { token: string; expiresAt: string };
  const session = { token: body.token, expiresAt: body.expiresAt, username };
  setSession(session);
  return session;
}

/** Ends the session on the server (so the token stops working at once) and here. Never leaves the person stuck: the local end happens anyway. */
export async function signOutBreakGlass(): Promise<void> {
  const session = breakGlassSession();
  if (session) {
    try {
      await fetch('/api/v1/auth/break-glass/logout', { method: 'POST', headers: { Authorization: `Bearer ${session.token}` } });
    } catch {
      // The token expires by itself; what matters now is that this tab stops using it.
    }
  }
  setSession(null);
  window.location.assign('/');
}

/** The token went wrong on the server (expired or revoked): forget it and ask for the sign-in again. */
export function dropBreakGlass(): void {
  setSession(null);
  window.location.assign(BREAK_GLASS_PATH);
}
