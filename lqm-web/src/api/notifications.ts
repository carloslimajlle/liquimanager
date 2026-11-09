import { useQuery, useQueryClient } from '@tanstack/react-query';

import { apiGet, apiSend } from './client';

export const EVENTS = [
  'PRD_FAILURE', 'GATE_BLOCKED', 'DRIFT_DETECTED', 'EXCEPTION_EXPIRING', 'ROLLBACK_PENDING', 'SYSTEM_ALERT',
] as const;
export type NotificationEvent = (typeof EVENTS)[number];
export type Channel = 'EMAIL' | 'TEAMS' | 'SLACK';

export type NotificationRule = {
  id: string;
  name: string;
  event: NotificationEvent;
  channel: Channel;
  /** Addresses for e-mail; for a webhook only its host: the URL is a credential and is never returned. */
  target: string;
  environment?: string | null;
  project?: string | null;
  enabled: boolean;
  /** Only events at or above this severity (0 INFO to 4 BLOCKER); events that carry no severity are always told. */
  minSeverity?: number | null;
  /** No message leaves between these two times (HH:mm, in quietZone); it waits for the window to end. */
  quietStart?: string | null;
  quietEnd?: string | null;
  quietZone?: string | null;
  /** One message every N minutes for what piled up, instead of one each. */
  digestMinutes?: number | null;
};

export type RuleBody = {
  name: string;
  event: string;
  channel: string;
  target?: string;
  environment?: string;
  project?: string;
  enabled?: boolean;
  /** The rule's whole state: what is left out is cleared. */
  minSeverity?: number;
  quietStart?: string;
  quietEnd?: string;
  quietZone?: string;
  digestMinutes?: number;
};

export type Delivery = {
  id: string;
  event: string;
  subject: string;
  status: 'PENDING' | 'SENT' | 'FAILED';
  attempts: number;
  error?: string | null;
  rule: string;
  channel: Channel;
  createdAt: string;
  sentAt?: string | null;
};

export function useNotificationRules() {
  return useQuery({ queryKey: ['notification-rules'], queryFn: () => apiGet<NotificationRule[]>('/notification-rules') });
}

export function useDeliveries() {
  return useQuery({
    queryKey: ['notification-deliveries'],
    queryFn: () => apiGet<Delivery[]>('/notification-deliveries'),
    refetchInterval: 15000,
  });
}

export function useNotificationActions() {
  const client = useQueryClient();
  const done = <T,>(promise: Promise<T>) => promise.then((value) => {
    for (const key of ['notification-rules', 'notification-deliveries']) {
      void client.invalidateQueries({ queryKey: [key] });
    }
    return value;
  });
  return {
    create: (body: RuleBody) => done(apiSend<NotificationRule>('POST', '/notification-rules', body)),
    update: (id: string, body: Partial<RuleBody>) => done(apiSend<NotificationRule>('PUT', `/notification-rules/${id}`, body)),
    remove: (id: string) => done(apiSend<void>('DELETE', `/notification-rules/${id}`)),
    test: (id: string) => apiSend<{ ok: boolean; error?: string }>('POST', `/notification-rules/${id}/test`),
  };
}
