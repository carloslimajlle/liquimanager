import { keepPreviousData, useQuery, useQueryClient } from '@tanstack/react-query';

import { apiGet, apiSend, identityHeaders } from './client';

export type DriftFilter = 'all' | 'drift' | 'clean';
export type DriftPeriod = '7d' | '30d' | '90d' | 'all';

export type DriftRun = {
  id: string;
  operationId?: string | null;
  command: string;
  status: 'SUCCESS' | 'FAILED';
  hasDrift?: boolean | null;
  added: number;
  removed: number;
  modified: number;
  connectionId: string;
  connection: string;
  environment: string;
  startedAt: string;
  error?: string | null;
};

export type DriftItem = {
  id: string;
  change: 'ADDED' | 'REMOVED' | 'MODIFIED';
  objectType: string;
  objectName: string;
  details?: Record<string, { before: string; after: string }> | Record<string, unknown> | null;
  accepted: boolean;
  acceptedBy?: string | null;
  acceptedReason?: string | null;
};

export type DriftRunDetail = DriftRun & { reference?: string | null; items: DriftItem[] };

export type DriftSummary = {
  driftRate?: number | null;
  driftDetected: number;
  cleanChecks: number;
  items: { total: number; added: number; removed: number; modified: number };
  averageItems?: number | null;
  coverage: { monitored: number; total: number };
  weekly: Array<{ week: number; from: string; items: number; rate?: number | null }>;
  diverged: { connection?: string | null; byType: Array<{ type: string; count: number }>; comparedAgainst: string };
};

export type DriftQuery = { period: DriftPeriod; filter: DriftFilter; connection: string };

export function useDriftSummary(q: DriftQuery) {
  return useQuery({
    queryKey: ['drift-summary', q.period, q.connection],
    queryFn: () => apiGet<DriftSummary>('/drift/summary', { period: q.period, connection: q.connection }),
    placeholderData: keepPreviousData,
  });
}

export function useDriftRuns(q: DriftQuery) {
  return useQuery({
    queryKey: ['drift-runs', q],
    queryFn: () => apiGet<{ content: DriftRun[]; totalElements: number }>('/drift/runs', { ...q, size: 50 }),
    placeholderData: keepPreviousData,
  });
}

export function useDriftRun(id: string | null) {
  return useQuery({
    queryKey: ['drift-run', id],
    queryFn: () => apiGet<DriftRunDetail>(`/drift/runs/${id}`),
    enabled: !!id,
  });
}

export function useDriftActions() {
  const client = useQueryClient();
  const done = <T,>(promise: Promise<T>) => promise.then((value) => {
    for (const key of ['drift-summary', 'drift-runs', 'drift-run']) {
      void client.invalidateQueries({ queryKey: [key] });
    }
    return value;
  });
  return {
    runNow: (connectionId: string) => done(apiSend<DriftRunDetail>('POST', `/connections/${connectionId}/drift-runs`)),
    rebaseline: (connectionId: string) => done(apiSend<unknown>('POST', `/connections/${connectionId}/drift-reference`)),
    accept: (itemId: string, reason: string) => done(apiSend<unknown>('POST', `/drift/items/${itemId}/accept`, { reason })),
  };
}

/** The generated changelog is XML, so it is fetched as text and handed to the browser as a file. */
export async function downloadDriftChangelog(runId: string): Promise<void> {
  const response = await fetch(`/api/v1/drift/runs/${runId}/changelog`, { method: 'POST', headers: identityHeaders() });
  if (!response.ok) {
    throw new Error(String(response.status));
  }
  const blob = new Blob([await response.text()], { type: 'application/xml' });
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = `drift-${runId}.xml`;
  link.click();
  URL.revokeObjectURL(url);
}
