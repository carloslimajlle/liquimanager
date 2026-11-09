import { useQuery } from '@tanstack/react-query';

import { apiGet, apiGetText } from './client';

export type ProductionState = 'EXECUTED' | 'PENDING' | 'FAILED';

export type DevChangeset = {
  id: string;
  author: string;
  filePath: string;
  hasRollback: boolean;
  release?: string | null;
  production: ProductionState;
};

export type DevFinding = {
  check: string;
  severity: number;
  result: string;
  message: string;
  line?: number | null;
  changeset?: string | null;
  filePath?: string | null;
};

export type ChangelogDetail = {
  id: string;
  name: string;
  path: string;
  format: string;
  project: { id: string; slug: string; name: string };
  branch?: string | null;
  sourceConfigured: boolean;
  kpis: {
    changesets: number;
    withRollback: number;
    release?: string | null;
    lastFlow?: { operationId: string; status: string; durationMs?: number | null; stages: number; startedAt: string } | null;
  };
  changesets: DevChangeset[];
  files: string[];
  findings: DevFinding[];
};

export type ChangelogListItem = { id: string; name: string; path: string; projectId: string; projectName: string };

export function useChangelogDetail(projectId: string | undefined, changelogId: string | undefined) {
  return useQuery({
    queryKey: ['changelog-detail', projectId, changelogId],
    queryFn: () => apiGet<ChangelogDetail>(`/projects/${projectId}/changelogs/${changelogId}`),
    enabled: !!projectId && !!changelogId,
  });
}

/** One repository file, through the backend. A file that is not there is an error the caller shows, not a crash. */
export function useRepoFile(projectId: string | undefined, changelogId: string | undefined, path: string | undefined, enabled = true) {
  return useQuery({
    queryKey: ['changelog-file', projectId, changelogId, path],
    queryFn: () => apiGetText(`/projects/${projectId}/changelogs/${changelogId}/files?path=${encodeURIComponent(path ?? '')}`),
    enabled: enabled && !!projectId && !!changelogId && !!path,
    retry: false,
    staleTime: 60_000,
  });
}

/** What the last flow printed: the same text the operation screen shows. */
export function useOperationLog(operationId: string | undefined) {
  return useQuery({
    queryKey: ['operation-log', operationId],
    queryFn: () => apiGetText(`/operations/${operationId}/log`),
    enabled: !!operationId,
    retry: false,
  });
}

/** Every changelog of every project, for the Developer Mode entry page. */
export function useAllChangelogs() {
  return useQuery({
    queryKey: ['all-changelogs'],
    queryFn: async (): Promise<ChangelogListItem[]> => {
      const projects = await apiGet<Array<{ id: string; name: string }>>('/projects');
      const lists = await Promise.all(projects.map((project) =>
        apiGet<Array<{ id: string; name: string; path: string }>>(`/projects/${project.id}/changelogs`)
          .then((rows) => rows.map((row) => ({ ...row, projectId: project.id, projectName: project.name })))));
      return lists.flat();
    },
    staleTime: 60_000,
  });
}
