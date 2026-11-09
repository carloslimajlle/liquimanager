import { useMe } from '../api/admin';
import { can } from './permissions';

/**
 * `can('catalog:edit')`: whether the signed-in person holds the permission. Until `/me` has answered (or if it fails)
 * everything shows: a hidden button is a convenience, the API is what refuses, and a screen that flickers empty on every
 * load is worse than one that shows a button the server may still turn down.
 */
export function useCan(): (permission: string) => boolean {
  const me = useMe();
  const held = me.data?.permissions;
  return (permission) => (held === undefined ? true : can(held, permission));
}
