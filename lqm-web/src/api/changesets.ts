import { keepPreviousData, useQuery } from '@tanstack/react-query';

import { apiGet } from './client';
import type { Page } from './operations';

export type ChangesetStatus = 'EXECUTED' | 'FAILED' | 'ROLLED_BACK' | 'PENDING' | string;

export type EnvironmentState = { environment: string; status: ChangesetStatus; deployedAt?: string | null };

export type ProjectChangeset = {
  id: string;
  changesetId: string;
  author: string;
  filePath: string;
  changelogId: string;
  changelog: string;
  release?: string | null;
  hasRollback: boolean;
  isBreakFix: boolean;
  firstSeenAt: string;
  /** When it was noticed that the changelog no longer has it. */
  removedAt?: string | null;
  /** Never seen on the default branch: it is in a merge request or a feature branch, not in the changelog yet. */
  onlyInBranch?: boolean;
  origin?: { branch?: string | null; mrIid?: string | null; mrTitle?: string | null; mrUrl?: string | null };
  environments: EnvironmentState[];
};

export type ProjectChangesets = Page<ProjectChangeset> & { environments: string[] };

export type EnvironmentSummary = {
  environment: string;
  executed: number;
  failed: number;
  rolledBack: number;
  lastDeployedAt?: string | null;
};

export type ChangesetFilters = { q?: string; author?: string; release?: string; status?: string; changelog?: string };

export type ChangesetDetail = {
  id: string;
  changesetId: string;
  author: string;
  filePath: string;
  checksum?: string | null;
  labels?: string | null;
  contexts?: string | null;
  comments?: string | null;
  release?: string | null;
  hasRollback: boolean;
  isBreakFix: boolean;
  firstSeenAt: string;
  changelogId: string;
  changelog: string;
  projectId: string;
  project: string;
  states: Array<{
    environment: string; connection: string; status: ChangesetStatus; execType?: string | null;
    deployedAt?: string | null; tag?: string | null; operationId?: string | null;
  }>;
  operations: Array<{
    id: string; command: string; status: string; outcome: string; startedAt: string; executedBy?: string | null;
    environment: string; connection: string; errorMessage?: string | null;
  }>;
  findings: Array<{ check: string; severity: number; result: string; message: string; objectName?: string | null; at: string; operationId: string }>;
  rollbacks: Array<{
    id: string; status: string; reason?: string | null; requestedBy: string; approvedBy?: string | null; at: string;
    operationId?: string | null; environment: string;
  }>;
};

export function useProjectChangesets(projectId: string | undefined, filters: ChangesetFilters, page: number, size: number) {
  return useQuery({
    queryKey: ['project-changesets', projectId, filters, page, size],
    queryFn: () => apiGet<ProjectChangesets>(`/projects/${projectId}/changesets`, { ...filters, page, size }),
    enabled: !!projectId,
    placeholderData: keepPreviousData,
  });
}

export function useProjectEnvironments(projectId: string | undefined) {
  return useQuery({
    queryKey: ['project-changesets-summary', projectId],
    queryFn: () => apiGet<EnvironmentSummary[]>(`/projects/${projectId}/changesets/summary`),
    enabled: !!projectId,
  });
}

export function useChangeset(id: string | undefined) {
  return useQuery({
    queryKey: ['changeset', id],
    queryFn: () => apiGet<ChangesetDetail>(`/changesets/${id}`),
    enabled: !!id,
  });
}

export type ChangesetSourceReason = 'NO_REPOSITORY' | 'NOT_CONFIGURED' | 'NOT_FOUND' | 'UNREACHABLE';

/** What the repository says about one changeset. Never an error: `found` false carries the reason the screen explains. */
export type ChangesetSource = {
  found: boolean;
  reason?: ChangesetSourceReason;
  detail?: string;
  path: string;
  format: string;
  ref?: string;
  refKind?: 'BRANCH' | 'COMMIT';
  tried?: string[];
  content?: string;
  startLine?: number;
  endLine?: number;
};

export function useChangesetSource(id: string | undefined, enabled = true) {
  return useQuery({
    queryKey: ['changeset-source', id],
    queryFn: () => apiGet<ChangesetSource>(`/changesets/${id}/source`),
    enabled: enabled && !!id,
    retry: false,
    staleTime: 60_000,
  });
}

export type PromotionChange = {
  id: string;
  changesetId: string;
  author: string;
  filePath: string;
  changelog: string;
  release?: string | null;
  hasRollback: boolean;
  isBreakFix: boolean;
};

/** What approving a promotion would apply: executed in the stage before, not yet in the target. */
export function usePromotionChanges(promotionId: string | undefined) {
  return useQuery({
    queryKey: ['promotion-changes', promotionId],
    queryFn: () => apiGet<PromotionChange[]>(`/promotions/${promotionId}/changes`),
    enabled: !!promotionId,
  });
}

export type DiffLine = { op: '-' | '+'; text: string };

/** What one database ran, against what the repository has now. `changed` is false when they are the same version. */
export type ChangesetVersion = {
  environment: string;
  connection: string;
  appliedChecksum: string;
  currentChecksum: string;
  appliedCommit?: string | null;
  appliedAt?: string | null;
  changed: boolean;
  sourceKnown?: boolean;
  equivalent?: boolean;
  diff?: DiffLine[];
};

export function useChangesetVersions(id: string | undefined) {
  return useQuery({
    queryKey: ['changeset-versions', id],
    queryFn: () => apiGet<ChangesetVersion[]>(`/changesets/${id}/versions`),
    enabled: !!id,
    retry: false,
  });
}
