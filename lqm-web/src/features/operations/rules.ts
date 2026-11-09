/**
 * Pure presentation rules for operations. No React in here, so they can be tested
 * without a DOM and reused by both operation screens.
 */
import type { Messages } from '../../i18n';
import type { OperationSummary } from '../../api/operations';
import type { Tone } from '../../components/StatusBadge';

/**
 * Who ran it, per screen 05: pipeline, startup and scheduler get a fixed label; the
 * scanner is its own thing; anything else (a person at the CLI or the UI) shows the user.
 */
export function executorLabel(
  operation: Pick<OperationSummary, 'source' | 'executedBy'>,
  t: Messages,
): string {
  switch (operation.source) {
    case 'pipeline':
      return t.operations.executor.pipeline;
    case 'startup':
      return t.operations.executor.startup;
    case 'scheduler':
      return t.operations.executor.scheduler;
    case 'scanner':
      return t.operations.executor.scanner;
    default:
      return operation.executedBy;
  }
}

export function statusTone(status: string): Tone {
  switch (status) {
    case 'SUCCESS':
      return 'success';
    case 'FAILURE':
    case 'BLOCKED':
      return 'danger';
    case 'RUNNING':
      return 'info';
    default:
      return 'neutral';
  }
}

/** Failed or blocked rows get the red row background (docs/10). */
export function isFailure(status: string): boolean {
  return status === 'FAILURE' || status === 'BLOCKED';
}

export function outcomeTone(outcome: string): Tone {
  switch (outcome) {
    case 'EXECUTED':
      return 'success';
    case 'FAILED':
      return 'danger';
    case 'PREVIOUSLY_RUN':
      return 'info';
    case 'FILTERED':
    case 'ROLLED_BACK':
      return 'warning';
    default:
      return 'neutral';
  }
}

/** Environment state of a changeset -> tone (EXECUTED green, FAILED red, the rest muted). */
export function stateTone(status: string): Tone {
  switch (status) {
    case 'EXECUTED':
      return 'success';
    case 'FAILED':
      return 'danger';
    case 'ROLLED_BACK':
      return 'warning';
    case 'PENDING':
      return 'info';
    default:
      return 'neutral';
  }
}

/** The 1-based range shown in "Showing 1–25 of 1,284". Empty sets have no range. */
export function pageRange(page: number, size: number, total: number, shown: number) {
  if (total === 0 || shown === 0) {
    return { from: 0, to: 0 };
  }
  const from = page * size + 1;
  return { from, to: from + shown - 1 };
}

/**
 * Page numbers to render: the first, the last, and a window around the current one,
 * with `null` marking a gap ("1 2 3 … 107"). `current` is 0-based, the output is 0-based.
 */
export function pageWindow(current: number, totalPages: number, radius = 1): Array<number | null> {
  if (totalPages <= 0) {
    return [];
  }
  const wanted = new Set<number>([0, totalPages - 1]);
  for (let p = current - radius; p <= current + radius; p++) {
    if (p >= 0 && p < totalPages) {
      wanted.add(p);
    }
  }
  const sorted = [...wanted].sort((a, b) => a - b);
  const out: Array<number | null> = [];
  sorted.forEach((page, index) => {
    if (index > 0 && page - sorted[index - 1] > 1) {
      out.push(null);
    }
    out.push(page);
  });
  return out;
}
