import { useQuery, useQueryClient } from '@tanstack/react-query';

import { apiGet, apiSend } from './client';

export type AccessRequestStatus = 'OPEN' | 'GRANTED' | 'DISMISSED';

export type AccessRequest = {
  id: string;
  requester: string;
  email?: string | null;
  groups: string[];
  message?: string | null;
  status: AccessRequestStatus;
  requestedAt: string;
  resolvedAt?: string | null;
  resolvedBy?: string | null;
  resolvedNote?: string | null;
  /** The answer to asking again too soon: the request that is already open. */
  already?: boolean;
};

/** The signed-in person's own open request, or nothing (an empty object: no `id`). */
export function useMyAccessRequest() {
  return useQuery({ queryKey: ['access-request-mine'], queryFn: () => apiGet<Partial<AccessRequest>>('/access-requests/mine') });
}

export function useAskAccess() {
  const client = useQueryClient();
  return (message: string) => apiSend<AccessRequest>('POST', '/access-requests', { message: message.trim() || null })
    .then((value) => {
      void client.invalidateQueries({ queryKey: ['access-request-mine'] });
      return value;
    });
}

/** The open requests: the line at the door, for whoever administers access. */
export function useAccessRequests(enabled: boolean) {
  return useQuery({
    queryKey: ['access-requests'],
    queryFn: () => apiGet<AccessRequest[]>('/access-requests'),
    enabled,
  });
}

export function useDismissAccessRequest() {
  const client = useQueryClient();
  return (id: string) => apiSend<AccessRequest>('POST', `/access-requests/${id}/dismiss`, {}).then((value) => {
    void client.invalidateQueries({ queryKey: ['access-requests'] });
    void client.invalidateQueries({ queryKey: ['me-pending'] });
    return value;
  });
}
