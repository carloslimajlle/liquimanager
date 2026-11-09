import { useQuery, useQueryClient } from '@tanstack/react-query';

import { apiGet, apiSend } from './client';

// ------------------------------------------------------------------ catalogue records

export type Team = { id: string; name: string };

export type Project = {
  id: string;
  teamId?: string | null;
  slug: string;
  name: string;
  repoUrl?: string | null;
  defaultBranch: string;
  description?: string | null;
};

export type ProjectBody = {
  slug: string;
  name: string;
  teamId?: string | null;
  repoUrl?: string | null;
  defaultBranch?: string | null;
  description?: string | null;
};

export type Changelog = {
  id: string;
  projectId: string;
  name: string;
  path: string;
  format: string;
  policyPackageId?: string | null;
  verified: boolean;
};

/** `policyPackageId` must be sent back on an edit: the API writes it as given, so omitting it would unassign the package. */
export type ChangelogBody = { name: string; path: string; format: string; verified?: boolean; policyPackageId?: string | null };

export type Connection = {
  id: string;
  environmentId: string;
  slug: string;
  displayName: string;
  engine: string;
  /** Never carries credentials: the API refuses a URL that does. */
  jdbcUrl: string;
  defaultSchema?: string | null;
  secretProvider?: string | null;
  secretRef?: string | null;
  driftEnabled: boolean;
  driftCron?: string | null;
  scannerEnabled: boolean;
  scannerIntervalMin?: number | null;
  dbVersion?: string | null;
  lastTestAt?: string | null;
  lastTestOk?: boolean | null;
  verified: boolean;
  /** The projects this connection applies to (RF-CAD-02). */
  projectIds?: string[];
};

export type ConnectionBody = {
  environmentId: string;
  slug: string;
  displayName: string;
  engine: string;
  jdbcUrl: string;
  defaultSchema?: string | null;
  secretProvider?: string | null;
  secretRef?: string | null;
  driftEnabled?: boolean;
  driftCron?: string | null;
  scannerEnabled?: boolean;
  scannerIntervalMin?: number | null;
  /** The whole set: what is not listed is unlinked. Left out, the links stay as they are. */
  projectIds?: string[];
};

export type ConnectionTest = { ok: boolean; dbVersion?: string; message?: string; latencyMs: number; warnings?: string[] };

export type EnvironmentBody = {
  code: string;
  name: string;
  sortOrder: number;
  colorToken: string;
  isProduction: boolean;
  requiresApproval: boolean;
};

export type Pipeline = {
  id: string;
  name: string;
  projectId: string;
  gitlabProjectId?: number | null;
  gitlabTriggerRef?: string | null;
};

export type PipelineBody = { name: string; projectId: string; gitlabProjectId?: number | null; gitlabTriggerRef?: string | null };

export type Stage = {
  id: string;
  pipelineId: string;
  sortOrder: number;
  environmentId: string;
  dbConnectionId?: string | null;
  policyPackageId?: string | null;
  requiresApproval: boolean;
  autoRequest?: boolean;
};

export type StageBody = {
  sortOrder: number;
  environmentId: string;
  dbConnectionId?: string | null;
  policyPackageId?: string | null;
  requiresApproval: boolean;
  autoRequest: boolean;
};

export type ServiceToken = {
  id: string;
  name: string;
  prefix: string;
  scopes: string[];
  projectIds: string[];
  expiresAt?: string | null;
  lastUsedAt?: string | null;
  createdBy?: string | null;
  revokedAt?: string | null;
};

export type TokenBody = { name: string; scopes: string[]; projectIds: string[]; expiresAt?: string | null };
export type IssuedToken = { id: string; prefix: string; token: string; notice: string };

export const TOKEN_SCOPES = ['ingest', 'checks:read', 'runner', 'webhook'] as const;

export type Workspace = {
  id: string; slug: string; name: string; aiEnabled: boolean; createdAt: string;
  jobRunRetentionDays: number; notificationRetentionDays: number; promotionTtlHours: number;
};

export type WorkspaceSettings = Pick<Workspace, 'jobRunRetentionDays' | 'notificationRetentionDays' | 'promotionTtlHours'>;

/** The header of a project page: the last 30 days of its operations and what hangs off it. `successRate` is absent with no operation. */
export type ProjectSummary = {
  finished: number; succeeded: number; failed: number; blocked: number; lastAt?: string | null;
  successRate?: number | null; connections: number; changelogs: number; pipelines: number; openDrift: number;
};

export type ProjectConnection = {
  id: string; slug: string; displayName: string; engine: string; environmentCode: string; environmentOrder: number;
};

export type AuditRow = {
  seq: number;
  at: string;
  actor: string;
  action: string;
  targetType?: string | null;
  targetId?: string | null;
};

// -------------------------------------------------------------------------- queries

export function useTeams() {
  return useQuery({ queryKey: ['teams'], queryFn: () => apiGet<Team[]>('/teams') });
}

/** One project of the list screen with what hangs off it. */
export type ProjectOverview = Project & {
  team?: string | null;
  changelogs: number;
  connections: number;
  /** Where its reports come from: the starter, or the CLI in a pipeline. Absent when nothing was reported yet. */
  collector?: 'starter' | 'pipeline' | 'cli' | null;
  lastOperationId?: string | null;
  lastOperationStatus?: string | null;
  lastOperationAt?: string | null;
};

export type ProjectsOverview = {
  kpis: { projects: number; changelogs: number; withCollector: number; underPolicy: number };
  projects: ProjectOverview[];
};

export function useProjectsOverview() {
  return useQuery({ queryKey: ['projects-overview'], queryFn: () => apiGet<ProjectsOverview>('/projects/overview') });
}

export function useProjectList() {
  return useQuery({ queryKey: ['projects-full'], queryFn: () => apiGet<Project[]>('/projects') });
}

export function useChangelogs(projectId: string | undefined) {
  return useQuery({
    queryKey: ['project-changelogs', projectId],
    queryFn: () => apiGet<Changelog[]>(`/projects/${projectId}/changelogs`),
    enabled: !!projectId,
  });
}

export function useProjectSummary(projectId: string | undefined) {
  return useQuery({
    queryKey: ['project-summary', projectId],
    queryFn: () => apiGet<ProjectSummary>(`/projects/${projectId}/summary`),
    enabled: !!projectId,
  });
}

export function useProjectConnections(projectId: string | undefined) {
  return useQuery({
    queryKey: ['project-connections', projectId],
    queryFn: () => apiGet<ProjectConnection[]>(`/projects/${projectId}/connections`),
    enabled: !!projectId,
  });
}

export function useConnectionList() {
  return useQuery({ queryKey: ['connections'], queryFn: () => apiGet<Connection[]>('/connections') });
}

export function usePipelineList() {
  return useQuery({ queryKey: ['pipelines-admin'], queryFn: () => apiGet<Pipeline[]>('/pipelines') });
}

export function useStages(pipelineId: string | undefined) {
  return useQuery({
    queryKey: ['pipeline-stages', pipelineId],
    queryFn: () => apiGet<Stage[]>(`/pipelines/${pipelineId}/stages`),
    enabled: !!pipelineId,
  });
}

export function useServiceTokens() {
  return useQuery({ queryKey: ['service-tokens'], queryFn: () => apiGet<ServiceToken[]>('/service-tokens') });
}

export function useWorkspaces() {
  return useQuery({ queryKey: ['workspaces'], queryFn: () => apiGet<Workspace[]>('/workspaces') });
}

export type Me = {
  name: string;
  email?: string | null;
  groups?: string[] | null;
  /** True on the development chain, where nobody is identified and everything is allowed. */
  devMode: boolean;
  permissions: string[];
  bindings: number;
};

export function useMe() {
  return useQuery({ queryKey: ['me'], queryFn: () => apiGet<Me>('/me') });
}

export function useAuditPage(filters: { actor?: string; action?: string }, page: number, size = 25) {
  return useQuery({
    queryKey: ['audit-page', filters, page, size],
    queryFn: () => apiGet<{ content: AuditRow[]; totalElements: number; size: number }>('/audit', { ...filters, page, size }),
  });
}

// ------------------------------------------------------------------------ mutations

/** Every write refreshes the lists it can change, and the audit trail it appends to. */
export function useAdminActions() {
  const client = useQueryClient();
  const done = <T,>(promise: Promise<T>) => promise.then((value) => {
    for (const key of ['teams', 'projects', 'projects-full', 'project-changelogs', 'connections', 'environments', 'pipelines-admin',
      'pipelines', 'pipeline-stages', 'service-tokens', 'audit', 'audit-page', 'policy-packages', 'project-summary', 'project-connections', 'workspaces']) {
      void client.invalidateQueries({ queryKey: [key] });
    }
    return value;
  });

  return {
    createTeam: (name: string) => done(apiSend<Team>('POST', '/teams', { name })),
    renameTeam: (id: string, name: string) => done(apiSend<Team>('PUT', `/teams/${id}`, { name })),

    createProject: (body: ProjectBody) => done(apiSend<Project>('POST', '/projects', body)),
    updateProject: (id: string, body: ProjectBody) => done(apiSend<Project>('PUT', `/projects/${id}`, body)),
    archiveProject: (id: string) => done(apiSend<void>('DELETE', `/projects/${id}`)),

    /** Replaces the set of connections the project applies to. */
    linkConnections: (projectId: string, connectionIds: string[]) =>
      done(apiSend<ProjectConnection[]>('PUT', `/projects/${projectId}/connections`, { connectionIds })),

    createChangelog: (projectId: string, body: ChangelogBody) =>
      done(apiSend<Changelog>('POST', `/projects/${projectId}/changelogs`, body)),
    updateChangelog: (projectId: string, id: string, body: ChangelogBody) =>
      done(apiSend<Changelog>('PUT', `/projects/${projectId}/changelogs/${id}`, body)),
    /** The audited way to point a changelog at a package, or to take it off one. */
    assignPackage: (packageId: string, target: { changelogId?: string; stageId?: string }) =>
      done(apiSend<unknown>('POST', `/policy-packages/${packageId}/assign`, target)),
    unassignPackage: (packageId: string, target: { changelogId?: string; stageId?: string }) =>
      done(apiSend<unknown>('POST', `/policy-packages/${packageId}/unassign`, target)),

    createConnection: (body: ConnectionBody) => done(apiSend<Connection>('POST', '/connections', body)),
    updateConnection: (id: string, body: ConnectionBody) => done(apiSend<Connection>('PUT', `/connections/${id}`, body)),
    archiveConnection: (id: string) => done(apiSend<void>('DELETE', `/connections/${id}`)),
    testConnection: (id: string) => done(apiSend<ConnectionTest>('POST', `/connections/${id}/test`)),

    createEnvironment: (body: EnvironmentBody) => done(apiSend<unknown>('POST', '/environments', body)),
    updateEnvironment: (id: string, body: EnvironmentBody) => done(apiSend<unknown>('PUT', `/environments/${id}`, body)),

    /** Every environment id, first to last: the pipeline order. */
    reorderEnvironments: (ids: string[]) => done(apiSend<unknown>('PUT', '/environments/order', { ids })),
    saveWorkspaceSettings: (workspaceId: string, body: WorkspaceSettings) =>
      done(apiSend<Workspace>('PUT', `/workspaces/${workspaceId}/settings`, body)),

    createPipeline: (body: PipelineBody) => done(apiSend<Pipeline>('POST', '/pipelines', body)),
    updatePipeline: (id: string, body: PipelineBody) => done(apiSend<Pipeline>('PUT', `/pipelines/${id}`, body)),
    deletePipeline: (id: string) => done(apiSend<void>('DELETE', `/pipelines/${id}`)),
    createStage: (pipelineId: string, body: StageBody) => done(apiSend<Stage>('POST', `/pipelines/${pipelineId}/stages`, body)),
    updateStage: (pipelineId: string, id: string, body: StageBody) =>
      done(apiSend<Stage>('PUT', `/pipelines/${pipelineId}/stages/${id}`, body)),
    deleteStage: (pipelineId: string, id: string) => done(apiSend<void>('DELETE', `/pipelines/${pipelineId}/stages/${id}`)),

    issueToken: (body: TokenBody) => done(apiSend<IssuedToken>('POST', '/service-tokens', body)),
    revokeToken: (id: string) => done(apiSend<void>('DELETE', `/service-tokens/${id}`)),
  };
}
