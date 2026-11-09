/**
 * Thin fetch wrapper for the LiquiManager API.
 *
 * Every failure becomes an {@link ApiError} carrying the RFC 9457 `code` (LQM-0xx),
 * so screens can react to a business code instead of parsing prose.
 */

import { accessToken, authEnabled, signIn } from '../auth/auth';
import { breakGlassSession, dropBreakGlass } from '../auth/breakGlass';

const BASE = '/api/v1';

export class ApiError extends Error {
  readonly status: number;
  readonly code?: string;

  constructor(status: number, message: string, code?: string) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
  }
}

export type Params = Record<string, string | number | boolean | null | undefined>;

/** Drops empty values so the query string only carries filters that are actually set. */
export function toQuery(params: Params): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== null && value !== '') {
      search.set(key, String(value));
    }
  }
  const text = search.toString();
  return text ? `?${text}` : '';
}

async function failure(response: Response): Promise<ApiError> {
  if (response.status === 401 && breakGlassSession()) {
    // The emergency token expired or was revoked: ask for the sign-in again.
    dropBreakGlass();
  } else if (response.status === 401 && authEnabled) {
    // The session is gone (expired and could not be renewed): sign in again and come back to this page.
    void signIn(window.location.pathname + window.location.search);
  }
  try {
    const problem = await response.json();
    return new ApiError(response.status, problem.detail ?? problem.title ?? response.statusText, problem.code);
  } catch {
    return new ApiError(response.status, response.statusText);
  }
}

/**
 * Who is calling. With an identity provider that is the access token. Without one (local development)
 * `localStorage.setItem('lqm.devUser', 'ana')` makes every request act as ana: the API honours that header on its
 * development chain alone (no issuer configured); with a real issuer it is ignored and the JWT decides.
 */
export function identityHeaders(): Record<string, string> {
  const emergency = breakGlassSession();
  if (emergency) {
    return { Authorization: `Bearer ${emergency.token}` };
  }
  if (authEnabled) {
    const token = accessToken();
    return token ? { Authorization: `Bearer ${token}` } : {};
  }
  try {
    const user = window.localStorage.getItem('lqm.devUser');
    return user ? { 'X-LQM-User': user } : {};
  } catch {
    return {};
  }
}

export async function apiGet<T>(path: string, params: Params = {}): Promise<T> {
  const response = await fetch(`${BASE}${path}${toQuery(params)}`, {
    headers: { Accept: 'application/json', ...identityHeaders() },
  });
  if (!response.ok) {
    throw await failure(response);
  }
  return (await response.json()) as T;
}

export async function apiGetText(path: string): Promise<string> {
  const response = await fetch(`${BASE}${path}`, { headers: { Accept: 'text/plain', ...identityHeaders() } });
  if (!response.ok) {
    throw await failure(response);
  }
  return response.text();
}

/** POST, PUT or DELETE with a JSON body. A 204 resolves to `undefined`. */
export async function apiSend<T>(method: 'POST' | 'PUT' | 'DELETE', path: string, body?: unknown): Promise<T> {
  const response = await fetch(`${BASE}${path}`, {
    method,
    headers: { Accept: 'application/json', ...identityHeaders(), ...(body === undefined ? {} : { 'Content-Type': 'application/json' }) },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (!response.ok) {
    throw await failure(response);
  }
  return (response.status === 204 ? undefined : await response.json()) as T;
}
