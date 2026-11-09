import { keepPreviousData, useQuery } from '@tanstack/react-query';

import { apiGet, apiGetText } from './client';
import type { Params } from './client';

/**
 * The API leaves null fields out of the JSON entirely (`non_null` inclusion), so a field
 * typed `X | null` here really arrives as `undefined`. They are declared optional, and
 * every check against them must be `== null`, never `=== null`.
 */
export type OperationStatus = 'SUCCESS' | 'FAILURE' | 'BLOCKED' | 'RUNNING' | 'CANCELED';

export type OperationSummary = {
  id: string;
  command: string;
  status: OperationStatus;
  type: string;
  source: string;
  executedBy: string;
  project?: string | null;
  projectId?: string | null;
  connection: string;
  environment: string;
  changelog?: string | null;
  changelogId?: string | null;
  changeset?: string | null;
  changesetId?: string | null;
  changesetCount?: number;
  release?: string | null;
  startedAt: string;
  durationMs?: number | null;
  isBreakFix: boolean;
};

export type Page<T> = { content: T[]; page: number; size: number; totalElements: number };

export type OperationKpis = {
  total: number;
  trendPct?: number | null;
  successRate?: number | null;
  succeeded: number;
  medianDurationMs?: number | null;
  caughtBeforeProd: number;
  failuresNonProd: number;
};

export type EnvironmentState = { environment: string; status: string; deployedAt: string | null };

export type OperationDetail = OperationSummary & {
  endedAt?: string | null;
  deploymentId?: string | null;
  contexts?: string | null;
  labels?: string | null;
  gitCommit?: string | null;
  gitBranch?: string | null;
  ciPipelineId?: string | null;
  ciJobUrl?: string | null;
  gateResult?: string | null;
  errorMessage?: string | null;
  exitCode?: number | null;
  hasLog: boolean;
  hasSql: boolean;
  summary: { executed: number; previouslyRun: number; filtered: number; failed: number };
  runtime: {
    engine?: string | null;
    dbVersion?: string | null;
    liquibaseVersion?: string | null;
    collector?: string | null;
    host?: string | null;
  };
  failedChangeset: {
    id: string;
    author: string;
    filePath: string;
    errorMessage?: string | null;
    environments: EnvironmentState[];
  } | null;
  findings: Array<{
    check: string;
    severity: number;
    result: string;
    message: string;
    objectName?: string | null;
    changeset?: string | null;
  }>;
};

export type OperationChangeset = {
  changesetId: string;
  author: string;
  filePath: string;
  outcome: string;
  hasRollback?: boolean;
  filterReason?: string | null;
  release?: string | null;
  startedAt?: string | null;
  durationMs?: number | null;
  errorMessage?: string | null;
};

/** Filters as they live in the URL. `period` is symbolic and turns into `from` at fetch time. */
export type OperationFilters = {
  q?: string;
  status?: string;
  type?: string;
  env?: string;
  command?: string;
  project?: string;
  connection?: string;
  changelog?: string;
  executedBy?: string;
  source?: string;
  period?: Period;
};

export type Period = '7d' | '30d' | '90d' | 'all';

const PERIOD_DAYS: Record<Exclude<Period, 'all'>, number> = { '7d': 7, '30d': 30, '90d': 90 };

/**
 * Turns the URL filters into API parameters. The period becomes a `from` computed NOW,
 * at fetch time, and never part of the query key: a key that carried a timestamp would
 * change on every render and refetch forever.
 */
export function toApiParams(filters: OperationFilters, now: Date = new Date()): Params {
  const { period, ...rest } = filters;
  const params: Params = { ...rest };
  if (period && period !== 'all') {
    params.from = new Date(now.getTime() - PERIOD_DAYS[period] * 86_400_000).toISOString();
  }
  return params;
}

export function useOperations(filters: OperationFilters, page: number, size: number, sort: string) {
  return useQuery({
    queryKey: ['operations', filters, page, size, sort],
    queryFn: () =>
      apiGet<Page<OperationSummary>>('/operations', { ...toApiParams(filters), page, size, sort }),
    // Keep the previous page on screen while the next one loads, so paging does not flash.
    placeholderData: keepPreviousData,
  });
}

export function useOperationKpis(filters: OperationFilters) {
  return useQuery({
    queryKey: ['operations', 'kpis', filters],
    queryFn: () => apiGet<OperationKpis>('/operations/kpis', toApiParams(filters)),
    placeholderData: keepPreviousData,
  });
}

export function useOperation(id: string) {
  return useQuery({
    queryKey: ['operation', id],
    queryFn: () => apiGet<OperationDetail>(`/operations/${id}`),
  });
}

export function useOperationChangesets(id: string, page: number, size: number) {
  return useQuery({
    queryKey: ['operation', id, 'changesets', page, size],
    queryFn: () => apiGet<Page<OperationChangeset>>(`/operations/${id}/changesets`, { page, size }),
    placeholderData: keepPreviousData,
  });
}

export function useOperationText(id: string, kind: 'log' | 'sql', enabled: boolean) {
  return useQuery({
    queryKey: ['operation', id, kind],
    queryFn: () => apiGetText(`/operations/${id}/${kind}`),
    enabled,
    retry: false,
  });
}
