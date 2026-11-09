import { describe, expect, it } from 'vitest';

import { readAuthConfig } from './auth';

describe('readAuthConfig', () => {
  it('is off without an authority, which is how local development runs', () => {
    expect(readAuthConfig({})).toBeNull();
    expect(readAuthConfig({ VITE_OIDC_AUTHORITY: '   ' })).toBeNull();
  });

  it('defaults the client id to lqm-web', () => {
    expect(readAuthConfig({ VITE_OIDC_AUTHORITY: 'http://kc/realms/liquimanager' }))
      .toEqual({ authority: 'http://kc/realms/liquimanager', clientId: 'lqm-web' });
  });

  it('takes the client id when it is set', () => {
    expect(readAuthConfig({ VITE_OIDC_AUTHORITY: ' http://kc/realms/x ', VITE_OIDC_CLIENT_ID: 'other' }))
      .toEqual({ authority: 'http://kc/realms/x', clientId: 'other' });
  });
});
