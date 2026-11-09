import { useQuery, useQueryClient } from '@tanstack/react-query';

import { apiGet, apiSend } from './client';

export type ReviewChangeset = {
  id: string;
  changesetId: string;
  author: string;
  filePath: string;
  changelog: string;
  release?: string | null;
  hasRollback: boolean;
};

/** The changesets of one merge request (or of a branch, when the pipeline did not say which merge request). */
export type ReviewGroup = {
  mrIid?: string | null;
  mrTitle?: string | null;
  mrUrl?: string | null;
  branch?: string | null;
  lastSeenAt?: string | null;
  closed: boolean;
  closedAt?: string | null;
  closedBy?: string | null;
  closedNote?: string | null;
  changesets: ReviewChangeset[];
};

export type InReview = { groups: ReviewGroup[]; openCount: number; closedCount: number };

/** What names a group when it is acted on: the merge request, or the branch when there is none. */
export type GroupRef = { mrIid: string | null; branch: string | null };

export function refOf(group: ReviewGroup): GroupRef {
  return group.mrIid ? { mrIid: group.mrIid, branch: null } : { mrIid: null, branch: group.branch ?? null };
}

export function useInReview(projectId: string | undefined, closed: boolean) {
  return useQuery({
    queryKey: ['in-review', projectId, closed],
    queryFn: () => apiGet<InReview>(`/projects/${projectId}/changesets/in-review`, { closed }),
    enabled: !!projectId,
    refetchInterval: 15000,
  });
}

export function useInReviewActions(projectId: string) {
  const client = useQueryClient();
  const done = <T,>(promise: Promise<T>) => promise.then((value) => {
    void client.invalidateQueries({ queryKey: ['in-review', projectId] });
    return value;
  });
  return {
    close: (ref: GroupRef, note: string) =>
      done(apiSend<{ closed: number }>('POST', `/projects/${projectId}/changesets/in-review/close`, { ...ref, note })),
    reopen: (ref: GroupRef) =>
      done(apiSend<{ reopened: number }>('POST', `/projects/${projectId}/changesets/in-review/reopen`, { ...ref, note: null })),
  };
}
