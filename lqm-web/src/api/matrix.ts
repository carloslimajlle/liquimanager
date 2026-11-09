import { keepPreviousData, useQuery, useQueryClient } from '@tanstack/react-query';

import { apiGet, apiSend } from './client';

export type CellStatus =
  | 'EXECUTED' | 'FAILED' | 'PENDING' | 'NOT_TARGETED' | 'NOT_REACHED' | 'ROLLED_BACK' | 'OUT_OF_PROCESS';

export type MatrixStage = { stageId: string; env: string; envName: string; connection: string };

export type MatrixCell = {
  stageId: string;
  status: CellStatus;
  operationId?: string | null;
  deployedAt?: string | null;
  deploymentId?: string | null;
  /** Who ran the operation that produced this cell. */
  executedBy?: string | null;
};

/** Where a changeset that is not on the default branch came from: the merge request when the pipeline said which, else the branch. */
export type ChangesetOrigin = { branch?: string | null; mrIid?: string | null; mrTitle?: string | null; mrUrl?: string | null };

export type MatrixRow = {
  changeset: string;
  author: string;
  filePath: string;
  release?: string | null;
  isBreakFix: boolean;
  contexts?: string | null;
  /** No longer in the changelog on the default branch; shown only while some database of the pipeline still holds it. */
  removed?: boolean;
  /** Never seen on the default branch: it is in a merge request or a feature branch. */
  onlyInBranch?: boolean;
  origin?: ChangesetOrigin;
  cells: MatrixCell[];
};

export type Matrix = {
  stages: MatrixStage[];
  kpis: {
    inSyncAll: number;
    total: number;
    environments: number;
    awaitingPromotion: number;
    awaitingPromotionEnvironment?: string | null;
    failedWithFix: number;
  };
  rows: MatrixRow[];
  page: number;
  size: number;
  totalElements: number;
};

export type PipelineSummary = {
  id: string;
  name: string;
  projectId?: string | null;
};

export function usePipelines() {
  return useQuery({
    queryKey: ['pipelines'],
    queryFn: () => apiGet<PipelineSummary[]>('/pipelines'),
    staleTime: 60_000,
  });
}

/**
 * The matrix is read as one whole grid, so it asks for the largest page the API allows
 * instead of paging: a half-drawn grid cannot be compared across environments. A real
 * 500+ changeset pipeline will want row virtualisation (the spec asks for it); until
 * then the cap is also the limit.
 */
export const MATRIX_PAGE_SIZE = 500;

export function useMatrix(pipelineId: string | undefined, release: string, q: string) {
  return useQuery({
    queryKey: ['matrix', pipelineId, release, q],
    queryFn: () => apiGet<Matrix>(`/pipelines/${pipelineId}/matrix`, { release, q, size: MATRIX_PAGE_SIZE }),
    enabled: !!pipelineId,
    placeholderData: keepPreviousData,
  });
}

export type TimelineEvent = {
  kind: 'DEPLOY' | 'PROMOTION';
  at: string;
  stageId: string;
  environment: string;
  release?: string | null;
  changesets?: number | null;
  by: string;
  ciPipelineId?: string | null;
  url?: string | null;
  operationId?: string | null;
  outOfProcess?: boolean;
  status?: 'REQUESTED' | 'TRIGGERED' | 'FAILED' | 'REJECTED' | 'CANCELED' | 'EXPIRED';
};

export function useTimeline(pipelineId: string | undefined) {
  return useQuery({
    queryKey: ['timeline', pipelineId],
    queryFn: () => apiGet<TimelineEvent[]>(`/pipelines/${pipelineId}/timeline`),
    enabled: !!pipelineId,
  });
}

export type PromotionStatus = 'REQUESTED' | 'TRIGGERED' | 'FAILED' | 'REJECTED' | 'CANCELED' | 'EXPIRED';

export type Promotion = {
  id: string;
  pipelineId?: string;
  stageId?: string;
  environment: string;
  release?: string | null;
  status: PromotionStatus;
  requestedBy?: string;
  decidedBy?: string | null;
  gitlabUrl?: string | null;
  error?: string | null;
  createdAt?: string;
};

/** Promotions of a pipeline that wait for a second person (a stage that requires approval). */
export function usePendingPromotions(pipelineId: string | undefined) {
  return useQuery({
    queryKey: ['promotions', pipelineId],
    queryFn: () => apiGet<Promotion[]>('/promotions', { pipeline: pipelineId, status: 'REQUESTED' }),
    enabled: !!pipelineId,
  });
}

export function usePromotionActions(pipelineId: string | undefined) {
  const client = useQueryClient();
  const done = <T,>(promise: Promise<T>) => promise.then((value) => {
    void client.invalidateQueries({ queryKey: ['promotions', pipelineId] });
    void client.invalidateQueries({ queryKey: ['timeline', pipelineId] });
    void client.invalidateQueries({ queryKey: ['audit'] });
    return value;
  });
  return {
    approve: (id: string) => done(apiSend<Promotion>('POST', `/promotions/${id}/approve`)),
    reject: (id: string) => done(apiSend<Promotion>('POST', `/promotions/${id}/reject`)),
    cancel: (id: string) => done(apiSend<Promotion>('POST', `/promotions/${id}/cancel`)),
  };
}

export type PreviewItem = { id: string; author: string; filePath: string };
export type PreviewEdit = PreviewItem & { appliedChecksum: string; currentChecksum: string };

/** What promoting to a stage would meet (D-22): what is ready, what was edited after the stage before ran it, what the stage before lacks. */
export type PromotionPreview = {
  env: string;
  gated: boolean;
  previousEnv?: string;
  ready?: PreviewItem[];
  readyCount?: number;
  edited?: PreviewEdit[];
  editedCount?: number;
  notInPrevious?: PreviewItem[];
  notInPreviousCount?: number;
  /** Gone from the changelog but still applied in the stage before: not promoted, and it does not hold the deploy back. */
  removed?: PreviewItem[];
  removedCount?: number;
};

export function usePromotionPreview(pipelineId: string | undefined, stageId: string | undefined) {
  return useQuery({
    queryKey: ['promotion-preview', pipelineId, stageId],
    queryFn: () => apiGet<PromotionPreview>(`/pipelines/${pipelineId}/stages/${stageId}/preview`),
    enabled: !!pipelineId && !!stageId,
    retry: false,
  });
}

/** Asks GitLab to run the pipeline job that moves a release to the stage; resolves with what was recorded. */
export function usePromote(pipelineId: string | undefined) {
  const client = useQueryClient();
  return (stageId: string, release: string) =>
    apiSend<Promotion>('POST', `/pipelines/${pipelineId}/stages/${stageId}/promote`, release ? { release } : {}).then((value) => {
      void client.invalidateQueries({ queryKey: ['timeline', pipelineId] });
      void client.invalidateQueries({ queryKey: ['promotions', pipelineId] });
      return value;
    });
}
