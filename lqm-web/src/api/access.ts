import { useQuery, useQueryClient } from '@tanstack/react-query';

import { apiGet, apiSend } from './client';

export type Principal = { id: string; type: 'USER' | 'GROUP'; externalId: string; displayName: string; email?: string | null };

export type Binding = {
  id: string;
  principal: Principal;
  roleTemplate?: string | null;
  roleTemplateName?: string | null;
  permissions: string[];
  extraPermissions: string[];
  deniedPermissions: string[];
  scopeType: string;
  scopeId?: string | null;
  approver?: string | null;
  expiresAt?: string | null;
  createdAt: string;
};

export type AccessOverview = {
  bindings: Binding[];
  summary: { groups: number; members: number; openExceptions: number; auditEvents: number };
};

export type RoleTemplate = { code: string; name: string; permissions: string[] };

export type GrantBody = {
  principalId: string;
  roleTemplate?: string;
  permissions?: string[];
  deniedPermissions?: string[];
  scopeType: string;
  scopeId?: string;
  approver?: string;
  expiresAt?: string;
};

export function useAccess() {
  return useQuery({ queryKey: ['access'], queryFn: () => apiGet<AccessOverview>('/access/bindings') });
}

export function useRoleTemplates() {
  return useQuery({ queryKey: ['role-templates'], queryFn: () => apiGet<RoleTemplate[]>('/role-templates'), staleTime: 300_000 });
}

export function usePrincipals(q: string) {
  return useQuery({ queryKey: ['principals', q], queryFn: () => apiGet<Principal[]>('/principals', { q }) });
}

export function useAccessActions() {
  const client = useQueryClient();
  const done = <T,>(promise: Promise<T>) => promise.then((value) => {
    for (const key of ['access', 'principals']) {
      void client.invalidateQueries({ queryKey: [key] });
    }
    return value;
  });
  return {
    grant: (body: GrantBody) => done(apiSend<Binding>('POST', '/access/bindings', body)),
    revoke: (id: string) => done(apiSend<void>('DELETE', `/access/bindings/${id}`)),
    createPrincipal: (body: { type: string; externalId: string; displayName: string }) =>
      done(apiSend<Principal>('POST', '/principals', body)),
  };
}

// ------------------------------------------------------------- audit and secrets (E12, E13)

export type AuditEvent = {
  seq: number;
  at: string;
  actor: string;
  action: string;
  targetType?: string | null;
  targetId?: string | null;
};

export function useAudit(size = 8) {
  return useQuery({
    queryKey: ['audit', size],
    queryFn: () => apiGet<{ content: AuditEvent[]; totalElements: number }>('/audit', { size }),
  });
}

export function useAuditVerification() {
  return useQuery({
    queryKey: ['audit-verify'],
    queryFn: () => apiGet<{ intact: boolean; checked: number; brokenAt?: number | null }>('/audit/verify'),
    staleTime: 60_000,
  });
}

export type SecretRow = {
  id: string;
  connection: string;
  environment: string;
  provider: string;
  rotatedAt?: string | null;
  ageDays?: number | null;
  level: 'ok' | 'warning' | 'danger';
};

export function useSecrets() {
  return useQuery({
    queryKey: ['secrets'],
    queryFn: () => apiGet<{ connections: SecretRow[]; secretsInChangelogs: number }>('/secrets/status'),
  });
}

export function useRotationAction() {
  const client = useQueryClient();
  return (connection: string) =>
    apiSend<unknown>('POST', `/secrets/${encodeURIComponent(connection)}/rotated`).then(() => {
      void client.invalidateQueries({ queryKey: ['secrets'] });
      void client.invalidateQueries({ queryKey: ['audit'] });
    });
}
