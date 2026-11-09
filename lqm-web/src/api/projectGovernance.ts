import { keepPreviousData, useQuery } from '@tanstack/react-query';

import { apiGet } from './client';
import type { Page } from './operations';

export type FindingResult = 'BLOCKED' | 'WARNED' | 'PASSED';

export type ProjectFinding = {
  id: string;
  check: string;
  result: FindingResult;
  severity: number;
  message: string;
  objectName?: string | null;
  changeset?: string | null;
  changesetId?: string | null;
  environment: string;
  connection: string;
  operationId: string;
  at: string;
};

export type ProjectFindings = Page<ProjectFinding> & { counts: Record<FindingResult, number> };

export function useProjectFindings(projectId: string | undefined, result: string, page: number, size: number) {
  return useQuery({
    queryKey: ['project-findings', projectId, result, page, size],
    queryFn: () => apiGet<ProjectFindings>(`/projects/${projectId}/findings`, { result, page, size }),
    enabled: !!projectId,
    placeholderData: keepPreviousData,
  });
}

export type ProjectDriftEntry = {
  connectionId: string;
  connection: string;
  environment: string;
  runId?: string | null;
  status?: 'SUCCESS' | 'FAILED' | null;
  hasDrift?: boolean | null;
  added?: number | null;
  removed?: number | null;
  modified?: number | null;
  error?: string | null;
  checkedAt?: string | null;
};

export function useProjectDrift(projectId: string | undefined) {
  return useQuery({
    queryKey: ['project-drift', projectId],
    queryFn: () => apiGet<ProjectDriftEntry[]>(`/projects/${projectId}/drift`),
    enabled: !!projectId,
  });
}

export type ApprovalItem = {
  kind: 'PROMOTION' | 'ROLLBACK' | 'EXCEPTION';
  id: string;
  requestedBy: string;
  at: string;
  mine: boolean;
  decidable: boolean;
  environment?: string;
  release?: string | null;
  pipelineId?: string;
  pipeline?: string;
  changeset?: string | null;
  changesetId?: string | null;
  operationId?: string;
  check?: string;
};

/** Everything of the project that waits for a decision, each marked with whether the caller may decide it. */
export function useProjectApprovals(projectId: string | undefined) {
  return useQuery({
    queryKey: ['project-approvals', projectId],
    queryFn: () => apiGet<{ total: number; items: ApprovalItem[] }>(`/projects/${projectId}/approvals`),
    enabled: !!projectId,
    refetchInterval: 30000,
  });
}
