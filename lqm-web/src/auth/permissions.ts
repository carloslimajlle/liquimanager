/**
 * The permission language of docs/09 § 2, the same rule the API applies (`Permissions.covers`): `resource:what`, where
 * `what` is an action or a list of environments, and a grant may use `*`. `deploy:dev,tst` covers `deploy:tst`;
 * `rollback:*` covers `rollback:prd` and `rollback:approve`.
 *
 * It exists to hide what the person cannot do, not to decide: the API decides, and answers 403 to anything that gets
 * past a hidden button.
 */
export function covers(held: string, required: string): boolean {
  const h = held.trim().toLowerCase();
  const r = required.trim().toLowerCase();
  if (h === '*:*' || h === r) {
    return true;
  }
  const hi = h.indexOf(':');
  const ri = r.indexOf(':');
  if (hi < 0 || ri < 0 || h.slice(0, hi) !== r.slice(0, ri)) {
    return false;
  }
  const heldWhat = h.slice(hi + 1);
  return heldWhat === '*' || heldWhat.split(',').includes(r.slice(ri + 1));
}

/**
 * A person the identity provider vouched for who holds no permission at all: signed in, but nobody has granted them (or one of their
 * groups) anything yet. Not the development chain, where everybody is allowed everything.
 */
export function hasNoAccess(me: { devMode: boolean; permissions: readonly string[] }): boolean {
  return !me.devMode && me.permissions.length === 0;
}

/** Does any held permission cover the required one? `*:*` is what the API reports for the development caller. */
export function can(held: readonly string[], required: string): boolean {
  return held.some((permission) => covers(permission, required));
}
