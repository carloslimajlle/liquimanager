import { keepPreviousData, useQuery } from '@tanstack/react-query';

import { apiGet } from './client';

export type DashboardPeriod = '7d' | '30d' | '90d';

/**
 * Null fields are omitted by the API, so everything that can be absent is optional —
 * see the note in operations.ts. A rate with nothing to divide by (no deploys yet) is
 * absent, never zero: zero would claim "nothing failed" when the truth is "nothing ran".
 */
export type Dashboard = {
  period: { days: number; from: string; to: string };
  header: { teams: number; connections: number; pipelines: number; operations: number };
  dora: {
    deployFrequency: { perDay: number; deploys: number; trendPct?: number | null };
    changeFailureRate: { value?: number | null; part: number; whole: number; trendPts?: number | null };
    rollbackRate: { value?: number | null; part: number; whole: number; trendPts?: number | null };
    cycleTime: { hours?: number | null; changesets: number; trendPct?: number | null };
  };
  /** True when the caller reads only part of the workspace: the policy and drift bands, which need the whole, are left out. */
  restricted?: boolean;
  policies?: {
    passRate: { value?: number | null; part: number; whole: number; trendPts?: number | null };
    blocked: { count: number; trendAbs: number };
    databaseCoverage: { covered: number; total: number };
    changelogCoverage: { covered: number; total: number };
  };
  drift?: {
    driftRate: { value?: number | null; part: number; whole: number; trendPts?: number | null };
    detected: { count: number; trendAbs: number };
    items: { total: number; added: number; removed: number; modified: number; trendAbs: number };
    monitoring: { monitored: number; total: number };
  };
};

export type ActivityRow = {
  id: string;
  startedAt: string;
  team?: string | null;
  subject?: string | null;
  project?: string | null;
  projectId?: string | null;
  changelogId?: string | null;
  changesetId?: string | null;
  changesetCount?: number;
  connection: string;
  environment: string;
  command: string;
  scope?: number | null;
  status: string;
};

export function useDashboard(period: DashboardPeriod) {
  return useQuery({
    queryKey: ['dashboard', period],
    queryFn: () => apiGet<Dashboard>('/dashboard', { period }),
    placeholderData: keepPreviousData,
  });
}

export const ACTIVITY_LIMIT = 9;

export function useActivity(project?: string) {
  return useQuery({
    queryKey: ['dashboard', 'activity', project ?? null],
    queryFn: () => apiGet<ActivityRow[]>('/dashboard/activity', { limit: ACTIVITY_LIMIT, project }),
  });
}
