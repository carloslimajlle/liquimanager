import type { ServiceToken } from '../../api/admin';

/** What a token is right now: revoking wins over expiry, and a token with no expiry never expires. */
export function stateOf(token: Pick<ServiceToken, 'revokedAt' | 'expiresAt'>, now: number): 'active' | 'revoked' | 'expired' {
  if (token.revokedAt) return 'revoked';
  if (token.expiresAt && new Date(token.expiresAt).getTime() <= now) return 'expired';
  return 'active';
}

/** The list with `value` added, or taken out when it is already there. */
export function toggle(list: string[], value: string): string[] {
  return list.includes(value) ? list.filter((item) => item !== value) : [...list, value];
}

/** The list with the item at `from` moved to `to` (both clamped to the list). A move that goes nowhere returns the same order. */
export function moveItem<T>(list: T[], from: number, to: number): T[] {
  const target = Math.max(0, Math.min(list.length - 1, to));
  if (from < 0 || from >= list.length || from === target) return [...list];
  const next = [...list];
  const [item] = next.splice(from, 1);
  next.splice(target, 0, item);
  return next;
}
