import { describe, expect, it } from 'vitest';

import { can } from '../auth/permissions';
import { ADMIN_PERMISSIONS } from './Sidebar';

const administers = (held: string[]) => ADMIN_PERMISSIONS.some((permission) => can(held, permission));

describe('who sees Administrar', () => {
  it('shows it to whoever administers something', () => {
    expect(administers(['workspace:admin'])).toBe(true);
    expect(administers(['catalog:edit'])).toBe(true);
    expect(administers(['*:*'])).toBe(true);
  });

  it('hides it from people who only read and deploy', () => {
    expect(administers([])).toBe(false);
    expect(administers(['assignment:view', 'deploy:dev,tst', 'rollback:hml'])).toBe(false);
  });
});
