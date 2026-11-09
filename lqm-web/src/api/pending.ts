import { useEffect } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';

import { apiGet } from './client';
import { connectEventStream } from './sse';

export type PendingItem = {
  kind: 'PROMOTION' | 'ROLLBACK' | 'EXCEPTION' | 'ACCESS';
  id: string;
  requestedBy: string;
  at: string;
  environment?: string;
  release?: string | null;
  pipelineId?: string;
  pipeline?: string;
  changeset?: string | null;
  operationId?: string;
  check?: string;
};

export type Pending = { total: number; items: PendingItem[] };

/** The server pushes `pending.changed` the moment something changes; this slow poll only covers what it cannot push (a request that expired). */
const SAFETY_POLL_MS = 5 * 60 * 1000;

/** What waits for the signed-in person to decide. Kept current by `usePendingLive`; the poll is only the safety net. */
export function useMyPending() {
  return useQuery({
    queryKey: ['me-pending'],
    queryFn: () => apiGet<Pending>('/me/pending'),
    refetchInterval: SAFETY_POLL_MS,
    refetchOnWindowFocus: true,
  });
}

/**
 * Listens to the server for `pending.changed` and asks again for the lists that depend on it, so a request opened (or decided) by
 * somebody else shows up at once, with no wait for a poll. Mounted once, by the shell. Reconnecting asks again too: whatever
 * happened while the stream was down is picked up.
 */
export function usePendingLive() {
  const client = useQueryClient();
  useEffect(() => {
    const refresh = () => {
      void client.invalidateQueries({ queryKey: ['me-pending'] });
      void client.invalidateQueries({ queryKey: ['project-approvals'] });
    };
    return connectEventStream('/api/v1/stream/operations', {
      onEvent: (name) => {
        if (name === 'pending.changed') refresh();
      },
      onState: (connected) => {
        if (connected) refresh();
      },
    });
  }, [client]);
}

/** The items whose id is not in `seen`: what arrived since the person last looked. */
export function unseen(items: PendingItem[], seen: ReadonlySet<string>): PendingItem[] {
  return items.filter((item) => !seen.has(itemKey(item)));
}

export function itemKey(item: PendingItem): string {
  return `${item.kind}-${item.id}`;
}

/** Where a pending item is decided. */
export function pendingTarget(item: PendingItem): string {
  switch (item.kind) {
    case 'PROMOTION':
      return `/pipelines/${item.pipelineId}/matrix`;
    case 'ROLLBACK':
      return `/operations/${item.operationId}`;
    case 'ACCESS':
      return '/admin/access';
    default:
      return '/govern/enforcement';
  }
}
