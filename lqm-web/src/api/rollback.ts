import { useQuery, useQueryClient } from '@tanstack/react-query';

import { apiGet, apiSend } from './client';

export type RollbackStatus = 'REQUESTED' | 'APPROVED' | 'REJECTED' | 'CANCELED' | 'RUNNING' | 'DONE' | 'FAILED';

/** One changeset a rollback would undo. */
export type RollbackItem = {
  changesetUuid: string;
  changesetId: string;
  author: string;
  filePath: string;
  hasRollback: boolean;
  deployedAt?: string | null;
  operationId?: string | null;
  /** The changeset the person chose to go back to (always the last of the list). */
  target: boolean;
};

/** What going back to a changeset would undo, newest first: the order it is undone in. */
export type RollbackPreview = {
  connection: string;
  environment: string;
  total: number;
  reversible: boolean;
  blockers: string[];
  items: RollbackItem[];
};

export type RollbackRequest = {
  id: string;
  operationId: string;
  kind: 'CHANGESET' | 'TAG';
  changeset?: string | null;
  changesetAuthor?: string | null;
  changesetUuid?: string | null;
  tag?: string | null;
  /** For a changeset: what it undoes, newest first, frozen when it was requested. */
  plan?: RollbackItem[];
  count?: number;
  reason: string;
  status: RollbackStatus;
  connection: string;
  environment: string;
  requestedBy: string;
  approvedBy?: string | null;
  resultOperationId?: string | null;
  error?: string | null;
  createdAt: string;
};

/** What is applied on a connection, newest first, each with the operation that applied it. */
export type AppliedChangesets = { connection: string; environment: string; items: RollbackItem[] };

export function useAppliedChangesets(connectionId: string) {
  return useQuery({
    queryKey: ['applied-changesets', connectionId],
    queryFn: () => apiGet<AppliedChangesets>(`/connections/${connectionId}/applied-changesets`),
    enabled: connectionId !== '',
    refetchInterval: 10000,
  });
}

/** Requests on one connection, newest first. */
export function useConnectionRollbackRequests(connectionId: string) {
  return useQuery({
    queryKey: ['rollback-requests', 'connection', connectionId],
    queryFn: () => apiGet<RollbackRequest[]>('/rollback-requests', { connection: connectionId }),
    enabled: connectionId !== '',
    refetchInterval: 5000,
  });
}

export function useRollbackRequests(operationId: string) {
  return useQuery({
    queryKey: ['rollback-requests', operationId],
    queryFn: () => apiGet<RollbackRequest[]>('/rollback-requests', { operation: operationId }),
    // A request moves without anyone touching the page (a runner takes it, finishes it).
    refetchInterval: 5000,
  });
}

export function useRollbackPreview(operationId: string, changeset: string) {
  return useQuery({
    queryKey: ['rollback-preview', operationId, changeset],
    queryFn: () => apiGet<RollbackPreview>(`/operations/${operationId}/rollback-preview`, { changeset }),
    enabled: changeset !== '',
    // What is applied can change under the dialog: never show a list from the cache as if it were current.
    staleTime: 0,
    gcTime: 0,
  });
}

/** `operationId` is where requests are made from; the approving and cancelling calls do not need one. */
export function useRollbackActions(operationId = '') {
  const client = useQueryClient();
  const done = <T,>(promise: Promise<T>) => promise.then((value) => {
    void client.invalidateQueries({ queryKey: ['rollback-requests'] });
    void client.invalidateQueries({ queryKey: ['applied-changesets'] });
    return value;
  });
  return {
    request: (body: { changeset?: string; tag?: string; reason: string }, operation = operationId) =>
      done(apiSend<RollbackRequest>('POST', `/operations/${operation}/rollback-requests`, body)),
    approve: (id: string) => done(apiSend<RollbackRequest>('POST', `/rollback-requests/${id}/approve`)),
    reject: (id: string) => done(apiSend<RollbackRequest>('POST', `/rollback-requests/${id}/reject`)),
    cancel: (id: string) => done(apiSend<RollbackRequest>('POST', `/rollback-requests/${id}/cancel`)),
  };
}
