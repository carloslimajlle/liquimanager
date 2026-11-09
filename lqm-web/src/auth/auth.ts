import { User, UserManager, WebStorageStateStore } from 'oidc-client-ts';

import { breakGlassSession, signOutBreakGlass } from './breakGlass';

/**
 * Sign-in with the identity provider (D-03, Keycloak): Authorization Code with PKCE in the browser, no client secret.
 *
 * It is switched on by `VITE_OIDC_AUTHORITY`. Without it the app runs the way local development always has: no sign-in,
 * and the API (which then has no issuer either) takes `X-LQM-User` as "act as". With it, every API call carries the
 * access token and the API validates it.
 *
 * The user is kept in `sessionStorage`: closing the tab ends the session, and nothing outlives it on disk.
 */
export type AuthConfig = { authority: string; clientId: string };

type Env = { VITE_OIDC_AUTHORITY?: string; VITE_OIDC_CLIENT_ID?: string };

export function readAuthConfig(env: Env): AuthConfig | null {
  const authority = env.VITE_OIDC_AUTHORITY?.trim();
  if (!authority) {
    return null;
  }
  return { authority, clientId: env.VITE_OIDC_CLIENT_ID?.trim() || 'lqm-web' };
}

/** What the container wrote into /config.js when it started, so one image serves every environment; empty in development. */
function runtimeEnv(): Env {
  return typeof window === 'undefined' ? {} : ((window as unknown as { __LQM_CONFIG__?: Env }).__LQM_CONFIG__ ?? {});
}

export const authConfig = readAuthConfig({ ...(import.meta.env as Env), ...Object.fromEntries(Object.entries(runtimeEnv()).filter(([, value]) => value)) });
export const authEnabled = authConfig !== null;

export const CALLBACK_PATH = '/auth/callback';

export type Profile = { name: string; username: string; initials: string };

let manager: UserManager | null = null;
let current: User | null = null;
// Kept as a value, not computed per call: useSyncExternalStore needs a snapshot that is the same object until it changes.
let profile: Profile | null = null;
const listeners = new Set<() => void>();

function setCurrent(user: User | null) {
  current = user;
  profile = profileOf(user);
  listeners.forEach((listener) => listener());
}

function userManager(): UserManager {
  if (!authConfig) {
    throw new Error('OIDC is not configured');
  }
  if (!manager) {
    manager = new UserManager({
      authority: authConfig.authority,
      client_id: authConfig.clientId,
      redirect_uri: `${window.location.origin}${CALLBACK_PATH}`,
      post_logout_redirect_uri: window.location.origin,
      response_type: 'code',
      scope: 'openid profile email',
      userStore: new WebStorageStateStore({ store: window.sessionStorage }),
      // Renews with the refresh token before the access token expires; a failure ends in a new sign-in (see below).
      automaticSilentRenew: true,
      monitorSession: false,
    });
    manager.events.addUserLoaded((user) => setCurrent(user));
    manager.events.addUserUnloaded(() => setCurrent(null));
    manager.events.addSilentRenewError(() => {
      void signIn(window.location.pathname + window.location.search);
    });
  }
  return manager;
}

/** The bearer token for an API call, or null when there is no (valid) session. */
export function accessToken(): string | null {
  return current && !current.expired ? current.access_token : null;
}

function profileOf(user: User | null): Profile | null {
  if (!user) {
    return null;
  }
  const username = (user.profile.preferred_username as string | undefined) ?? user.profile.sub;
  const name = (user.profile.name as string | undefined) ?? username;
  const initials = name.split(/\s+/).filter(Boolean).slice(0, 2).map((part) => part[0]!.toUpperCase()).join('');
  return { name, username, initials: initials || '?' };
}

export function currentProfile(): Profile | null {
  return profile;
}

export function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

/** Loads the stored session, if there is a valid one. */
export async function loadSession(): Promise<boolean> {
  const user = await userManager().getUser();
  setCurrent(user && !user.expired ? user : null);
  return current !== null;
}

/** Sends the browser to the identity provider; `returnTo` is where the callback sends it back. */
export async function signIn(returnTo: string): Promise<void> {
  await userManager().signinRedirect({ state: returnTo });
}

let callback: Promise<string> | null = null;

/** Completes the sign-in on `/auth/callback` and says where to go next. Safe to call twice (React StrictMode does). */
export function completeSignIn(): Promise<string> {
  callback ??= userManager().signinRedirectCallback().then((user) => {
    setCurrent(user);
    return typeof user.state === 'string' && user.state.startsWith('/') ? user.state : '/';
  });
  return callback;
}

export async function signOut(): Promise<void> {
  if (breakGlassSession()) {
    await signOutBreakGlass();
    return;
  }
  await userManager().signoutRedirect();
}
