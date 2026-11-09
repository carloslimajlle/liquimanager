/** Pure rules for the dashboard's trends and activity table. No React, so they are testable as they are. */
import type { Trend } from '../../components/KpiCard';
import type { ActivityRow } from '../../api/dashboard';

type Formatter = (value: number) => string;

/**
 * A trend where MORE is better (deploy frequency): up is good, down is bad.
 * Absent when there is nothing to compare with or nothing changed — an arrow for
 * "0%" would be noise.
 */
export function trendHigherIsBetter(change: number | null | undefined, format: Formatter): Trend | undefined {
  if (change == null || change === 0) {
    return undefined;
  }
  return { value: format(change), direction: change > 0 ? 'up' : 'down', good: change > 0 };
}

/**
 * A trend where LESS is better (failure rate, rollback rate, cycle time): the arrow
 * still points where the number went, but a rise is the bad news. This is the case
 * docs/10 spells out for Trend — direction and good/bad are independent.
 */
export function trendLowerIsBetter(change: number | null | undefined, format: Formatter): Trend | undefined {
  if (change == null || change === 0) {
    return undefined;
  }
  return { value: format(change), direction: change > 0 ? 'up' : 'down', good: change < 0 };
}

/** "+25" / "-3,5": the sign is part of a change, unlike a plain number. */
export function signed(value: number, format: Formatter): string {
  return `${value > 0 ? '+' : ''}${format(value)}`;
}

/** Ids already on screen: anything not in here arrived since, and gets the fade-in. */
export function newRowIds(previous: ReadonlySet<string>, rows: ActivityRow[]): Set<string> {
  return new Set(rows.filter((row) => !previous.has(row.id)).map((row) => row.id));
}

/** HH:mm in the display timezone, for the activity table's first column. */
export function timeOfDay(iso: string, locale: string, timeZone = 'America/Sao_Paulo'): string {
  return new Intl.DateTimeFormat(locale, { hour: '2-digit', minute: '2-digit', timeZone }).format(new Date(iso));
}
