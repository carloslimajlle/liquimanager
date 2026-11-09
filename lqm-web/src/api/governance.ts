import { keepPreviousData, useQuery, useQueryClient } from '@tanstack/react-query';

import { apiGet, apiSend } from './client';

export type GateResult = 'PASSED' | 'WARNED' | 'BLOCKED';

export type EnforcementGate = {
  stageId: string;
  env: string;
  envName: string;
  connection: string;
  production: boolean;
  result?: GateResult | null;
  evaluatedAt?: string | null;
  operationId?: string | null;
  findings: number;
  bySeverity: Record<string, number>;
};

export type EnforcementFinding = {
  severity: number;
  check: string;
  changeset?: string | null;
  connection: string;
  env: string;
  result: GateResult;
  message: string;
  operationId: string;
  at: string;
};

export type Enforcement = {
  pipeline: { id: string; name: string; project?: string | null };
  kpis: { evaluations: number; violations: number; blocked: number; passRate?: number | null };
  gates: EnforcementGate[];
  totalFindings: number;
  findings: EnforcementFinding[];
  latestBlocked?: EnforcementFinding | null;
  breakFix?: BreakFixEntry[];
};

export function useEnforcement(pipelineId: string | undefined, period: string) {
  return useQuery({
    queryKey: ['enforcement', pipelineId, period],
    queryFn: () => apiGet<Enforcement>('/enforcement', { pipeline: pipelineId, period }),
    enabled: !!pipelineId,
    placeholderData: keepPreviousData,
  });
}

export type PolicyCheck = {
  id: string;
  code: string;
  shortName: string;
  description?: string | null;
  category: string;
  scope: string;
  kind: string;
  defaultSeverity: number;
  enabled: boolean;
  prebuilt: boolean;
  packages: number;
  implemented: boolean;
};

export type PolicyChecksSummary = {
  prebuilt: number;
  prebuiltCategories: number;
  custom: number;
  packages: number;
  pipelines: number;
  connections: number;
  implemented: number;
};

export type CheckFilters = { category: string; kind: string; enabled: string; q: string };

export function usePolicyChecks(filters: CheckFilters) {
  return useQuery({
    queryKey: ['policy-checks', filters],
    queryFn: () => apiGet<PolicyCheck[]>('/policy-checks', { ...filters }),
    placeholderData: keepPreviousData,
  });
}

export function usePolicyChecksSummary() {
  return useQuery({
    queryKey: ['policy-checks-summary'],
    queryFn: () => apiGet<PolicyChecksSummary>('/policy-checks/summary'),
    staleTime: 60_000,
  });
}

// --------------------------------------------------------------- writing (E9)

export type CheckKind = 'BUILTIN' | 'REGEX' | 'SQL_PATTERN' | 'SPEL' | 'CHAINED';

/** One check as the editor reads and writes it (the contract's PolicyCheckDefinition). */
export type CheckDefinition = {
  id?: string;
  code: string;
  shortName?: string | null;
  description?: string | null;
  category: string;
  scope?: string;
  changelogTypes?: string;
  kind: CheckKind;
  defaultSeverity: number;
  parameters?: Record<string, unknown>;
  messageTemplate?: string | null;
  members?: string[];
  enabled?: boolean;
  parentId?: string | null;
};

/** What GET /policy-checks/{id} returns: the list fields plus the editable ones, as text. */
export type CheckDetail = PolicyCheck & {
  changelogTypes?: string;
  messageTemplate?: string | null;
  parametersSchema?: string;
  parameters?: string;
};

export function useCheck(id: string | undefined) {
  return useQuery({
    queryKey: ['policy-check', id],
    queryFn: () => apiGet<CheckDetail>(`/policy-checks/${id}`),
    enabled: !!id,
  });
}

/** Members of a chain are not in the detail response; the list has every code, which is what the picker needs. */
export function useAllChecks() {
  return usePolicyChecks({ category: '', kind: '', enabled: '', q: '' });
}

export type TestRequest = {
  changelogContent: string;
  format: 'XML' | 'YAML' | 'JSON' | 'SQL';
  dialect: string;
  severity?: number;
  parameters?: Record<string, unknown>;
};

export type TestResult = {
  gate: GateResult;
  changesets: number;
  findings: Array<{ check: string; severity: number; changeset?: string | null; message: string }>;
};

export type PolicyPackageSummary = {
  id: string;
  name: string;
  description?: string | null;
  blockThresholdProd: number;
  blockThresholdNonprod: number;
  checks: number;
  changelogs: number;
  stages: number;
  isDefault: boolean;
};

export type PackageCheck = {
  code: string;
  kind?: string;
  category?: string;
  defaultSeverity?: number;
  enabled?: boolean;
  severityOverride?: number | null;
  parametersOverride?: Record<string, unknown> | null;
};

export type PolicyPackageDetail = Omit<PolicyPackageSummary, 'checks' | 'isDefault'> & { checks: PackageCheck[] };

export type PackageBody = {
  name: string;
  description?: string;
  blockThresholdProd: number;
  blockThresholdNonprod: number;
  checks: Array<{ code: string; severityOverride?: number | null }>;
};

export function usePolicyPackages() {
  return useQuery({
    queryKey: ['policy-packages'],
    queryFn: () => apiGet<PolicyPackageSummary[]>('/policy-packages'),
  });
}

export function usePolicyPackage(id: string | undefined) {
  return useQuery({
    queryKey: ['policy-package', id],
    queryFn: () => apiGet<PolicyPackageDetail>(`/policy-packages/${id}`),
    enabled: !!id,
  });
}

/** Everything a library or package write can invalidate. */
function invalidateLibrary(client: ReturnType<typeof useQueryClient>) {
  for (const key of ['policy-checks', 'policy-check', 'policy-checks-summary', 'policy-packages', 'policy-package']) {
    void client.invalidateQueries({ queryKey: [key] });
  }
}

export function useLibraryMutations() {
  const client = useQueryClient();
  const done = <T,>(promise: Promise<T>) => promise.then((value) => { invalidateLibrary(client); return value; });
  return {
    createCheck: (body: CheckDefinition) => done(apiSend<CheckDefinition>('POST', '/policy-checks', body)),
    updateCheck: (id: string, body: Partial<CheckDefinition>) =>
      done(apiSend<CheckDefinition>('PUT', `/policy-checks/${id}`, body)),
    deleteCheck: (id: string) => done(apiSend<void>('DELETE', `/policy-checks/${id}`)),
    copyCheck: (id: string, code?: string) =>
      done(apiSend<CheckDefinition>('POST', `/policy-checks/${id}/copy`, code ? { code } : {})),
    testCheck: (id: string, body: TestRequest) => apiSend<TestResult>('POST', `/policy-checks/${id}/test`, body),
    createPackage: (body: PackageBody) => done(apiSend<PolicyPackageDetail>('POST', '/policy-packages', body)),
    updatePackage: (id: string, body: Partial<PackageBody>) =>
      done(apiSend<PolicyPackageDetail>('PUT', `/policy-packages/${id}`, body)),
    deletePackage: (id: string) => done(apiSend<void>('DELETE', `/policy-packages/${id}`)),
  };
}

// ---------------------------------------------------------- exceptions (E10)

export type ExceptionStatus = 'REQUESTED' | 'APPROVED' | 'REJECTED' | 'REVOKED' | 'EXPIRED';

export type PolicyException = {
  id: string;
  check: string;
  changeset?: string | null;
  changesetAuthor?: string | null;
  project?: string | null;
  environment?: string | null;
  justification: string;
  requestedBy: string;
  approvedBy?: string | null;
  status: ExceptionStatus;
  expiresAt: string;
  createdAt: string;
};

export type BreakFixEntry = {
  changeset: string;
  author: string;
  contexts?: string | null;
  env: string;
  connection: string;
  at: string;
  operationId?: string | null;
};

export function usePolicyExceptions() {
  return useQuery({
    queryKey: ['policy-exceptions'],
    queryFn: () => apiGet<PolicyException[]>('/policy-exceptions'),
  });
}

export type ExceptionRequest = {
  check: string;
  project?: string;
  changeset?: string;
  environment?: string;
  justification: string;
  expiresAt: string;
};

export function useExceptionActions() {
  const client = useQueryClient();
  const done = <T,>(promise: Promise<T>) => promise.then((value) => {
    for (const key of ['policy-exceptions', 'enforcement']) {
      void client.invalidateQueries({ queryKey: [key] });
    }
    return value;
  });
  return {
    request: (body: ExceptionRequest) => done(apiSend<PolicyException>('POST', '/policy-exceptions', body)),
    approve: (id: string) => done(apiSend<PolicyException>('POST', `/policy-exceptions/${id}/approve`)),
    reject: (id: string) => done(apiSend<PolicyException>('POST', `/policy-exceptions/${id}/reject`)),
    revoke: (id: string) => done(apiSend<PolicyException>('POST', `/policy-exceptions/${id}/revoke`)),
  };
}
