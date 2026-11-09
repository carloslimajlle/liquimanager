/** Pure presentation rules for the Deployment Matrix: no React, so they are testable as they are. */
import type { CellStatus, MatrixRow, TimelineEvent } from '../../api/matrix';
import type { Messages } from '../../i18n';
import type { Tone } from '../../components/StatusBadge';

/**
 * Badge tone per cell status (docs/telas/04). `NOT_REACHED` has no badge at all: the
 * cell shows a dash, because "has not got here yet" is not a state worth shouting about.
 */
export function cellTone(status: CellStatus): Tone | null {
  switch (status) {
    case 'EXECUTED':
      return 'success';
    case 'FAILED':
      return 'danger';
    case 'PENDING':
      return 'info';
    case 'NOT_TARGETED':
      return 'neutral';
    case 'ROLLED_BACK':
    case 'OUT_OF_PROCESS':
      return 'warning';
    case 'NOT_REACHED':
      return null;
  }
}

/** A row with a failure anywhere is tinted red across the whole line. */
export function rowHasFailure(row: Pick<MatrixRow, 'cells'>): boolean {
  return row.cells.some((cell) => cell.status === 'FAILED');
}

/**
 * "Em sincronia nos QUATRO": the count spelled out, as the print shows. Beyond the
 * catalogue's words it falls back to the digits, never to something wrong.
 */
export function numberWord(n: number, t: Messages): string {
  return t.matrix.numberWords[n] ?? String(n);
}

/** The distinct releases in view, newest-looking last, for the release picker. */
export function releasesOf(rows: Pick<MatrixRow, 'release'>[]): string[] {
  const seen = new Set<string>();
  for (const row of rows) {
    if (row.release) {
      seen.add(row.release);
    }
  }
  return [...seen].sort((a, b) => a.localeCompare(b, undefined, { numeric: true }));
}

/**
 * The timeline as the screen draws it: only the chosen release (when one is chosen), grouped by day, newest day
 * first and, inside a day, newest event first. The day is the one the user lives in (UTC-3), not UTC: a deploy at
 * 22:00 in Sao Paulo belongs to that evening.
 */
export function groupByDay(events: TimelineEvent[], release: string): { day: string; events: TimelineEvent[] }[] {
  const days = new Map<string, TimelineEvent[]>();
  const sorted = [...events]
    .filter((event) => !release || event.release === release)
    .sort((a, b) => b.at.localeCompare(a.at));
  for (const event of sorted) {
    const day = new Date(new Date(event.at).getTime() - 3 * 3_600_000).toISOString().slice(0, 10);
    days.set(day, [...(days.get(day) ?? []), event]);
  }
  return [...days.entries()].map(([day, list]) => ({ day, events: list }));
}

/** Release options for the timeline filter, from the events themselves, ordered as versions. */
export function timelineReleases(events: TimelineEvent[]): string[] {
  return [...new Set(events.map((event) => event.release).filter((r): r is string => !!r))]
    .sort((a, b) => a.localeCompare(b, undefined, { numeric: true }));
}
