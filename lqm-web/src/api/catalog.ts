import { useQuery } from '@tanstack/react-query';

import { apiGet } from './client';

export type Environment = {
  id: string;
  code: string;
  name: string;
  sortOrder: number;
  colorToken: string;
  isProduction: boolean;
  requiresApproval: boolean;
};

/**
 * The workspace's environments, in pipeline order. They are configurable (D-15), so
 * screens read them from here instead of hard-coding DEV/TST/HML/PRD.
 */
export function useEnvironments() {
  return useQuery({
    queryKey: ['environments'],
    queryFn: () => apiGet<Environment[]>('/environments'),
    // Rarely edited, and read by every filter bar: no need to refetch often.
    staleTime: 5 * 60_000,
  });
}

export type ProjectRef = { id: string; slug: string; name: string };

export function useProjects() {
  return useQuery({
    queryKey: ['projects'],
    queryFn: () => apiGet<ProjectRef[]>('/projects'),
    staleTime: 60_000,
  });
}
