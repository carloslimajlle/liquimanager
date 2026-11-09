/** Presentation rules for the drift screen; no React, so they test without a DOM. */
import type { DriftRun, DriftSummary } from '../../api/drift';
import type { Tone } from '../../components/StatusBadge';

/** `+2 −1 ~3`, with the sign of each kind of change; a kind with none is left out. */
export function itemsLabel(run: Pick<DriftRun, 'added' | 'removed' | 'modified'>): string {
  const parts = [
    run.added > 0 ? `+${run.added}` : null,
    run.removed > 0 ? `−${run.removed}` : null,
    run.modified > 0 ? `~${run.modified}` : null,
  ].filter((p): p is string => p !== null);
  return parts.length === 0 ? '0' : parts.join(' ');
}

/** Drift, clean, or "unknown" when the run failed before it could compare anything. */
export function driftTone(run: Pick<DriftRun, 'hasDrift' | 'status'>): Tone {
  if (run.status === 'FAILED' || run.hasDrift == null) {
    return 'neutral';
  }
  return run.hasDrift ? 'warning' : 'success';
}

export function rowTone(run: Pick<DriftRun, 'hasDrift' | 'status'>): 'danger' | 'warning' | undefined {
  if (run.status === 'FAILED') {
    return 'danger';
  }
  return run.hasDrift ? 'warning' : undefined;
}

/** "↘ 86% in 8 weeks": how the drift rate moved between the first and the last week that had one. */
export function trend(weekly: DriftSummary['weekly']): { direction: 'up' | 'down' | 'flat'; percent: number } | null {
  const rated = weekly.filter((w) => w.rate != null);
  if (rated.length < 2) {
    return null;
  }
  const first = rated[0].rate as number;
  const last = rated[rated.length - 1].rate as number;
  if (first === 0) {
    return last === 0 ? { direction: 'flat', percent: 0 } : null;
  }
  const change = Math.round(((last - first) / first) * 100);
  return { direction: change === 0 ? 'flat' : change > 0 ? 'up' : 'down', percent: Math.abs(change) };
}

/** Widths of the "what diverged" bars, the biggest at 100%. */
export function barWidths(byType: Array<{ type: string; count: number }>): Array<{ type: string; count: number; width: number }> {
  const max = Math.max(1, ...byType.map((b) => b.count));
  return byType.map((b) => ({ ...b, width: Math.round((100 * b.count) / max) }));
}

/** The before/after pairs of a MODIFIED item, or nothing for the other kinds. */
export function differences(details: unknown): Array<{ field: string; before: string; after: string }> {
  if (!details || typeof details !== 'object') {
    return [];
  }
  return Object.entries(details as Record<string, unknown>)
    .filter(([, value]) => value && typeof value === 'object' && 'before' in (value as object) && 'after' in (value as object))
    .map(([field, value]) => ({
      field,
      before: String((value as { before: unknown }).before),
      after: String((value as { after: unknown }).after),
    }));
}
