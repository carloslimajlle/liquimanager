import { describe, expect, it } from 'vitest';

import type { CellStatus } from '../../api/matrix';
import { messagesFor } from '../../i18n';
import type { TimelineEvent } from '../../api/matrix';
import { cellTone, groupByDay, numberWord, releasesOf, rowHasFailure, timelineReleases } from './rules';

const t = messagesFor('pt-BR');

describe('cellTone', () => {
  it('maps every status, and gives the not-yet-reached one no badge', () => {
    const expected: Record<CellStatus, string | null> = {
      EXECUTED: 'success',
      FAILED: 'danger',
      PENDING: 'info',
      NOT_TARGETED: 'neutral',
      ROLLED_BACK: 'warning',
      OUT_OF_PROCESS: 'warning',
      NOT_REACHED: null,
    };
    for (const [status, tone] of Object.entries(expected)) {
      expect(cellTone(status as CellStatus)).toBe(tone);
    }
  });
});

describe('rowHasFailure', () => {
  const cell = (status: CellStatus) => ({ stageId: 's', status });

  it('is true when any environment failed, even if the others are fine', () => {
    expect(rowHasFailure({ cells: [cell('EXECUTED'), cell('FAILED')] })).toBe(true);
  });

  it('is false for a pending or not-reached cell: those are waiting, not broken', () => {
    expect(rowHasFailure({ cells: [cell('EXECUTED'), cell('PENDING'), cell('NOT_REACHED')] })).toBe(false);
  });
});

describe('numberWord', () => {
  it('spells small counts in words, as the print does', () => {
    expect(numberWord(4, t)).toBe('quatro');
    expect(numberWord(0, t)).toBe('zero');
  });

  it('falls back to the digits past the catalogue instead of inventing a word', () => {
    expect(numberWord(11, t)).toBe('11');
  });
});

describe('releasesOf', () => {
  it('lists each release once, ordered as versions, ignoring rows without one', () => {
    const rows = [
      { release: 'release-2.10.0' }, { release: 'release-2.2.0' }, { release: 'release-2.2.0' }, { release: null },
      { release: undefined },
    ];
    expect(releasesOf(rows)).toEqual(['release-2.2.0', 'release-2.10.0']);
  });
});

describe('groupByDay', () => {
  const event = (at: string, release: string | null = 'release-2.1.0'): TimelineEvent =>
    ({ kind: 'DEPLOY', at, stageId: 's', environment: 'DEV', release, by: 'x' });

  it('groups by the day in Sao Paulo, newest first', () => {
    const groups = groupByDay([
      event('2026-11-01T10:00:00Z'), event('2026-11-02T01:30:00Z'), event('2026-11-02T15:00:00Z'),
    ], '');

    // 01:30Z on the 2nd is 22:30 on the 1st in Sao Paulo
    expect(groups.map((g) => g.day)).toEqual(['2026-11-02', '2026-11-01']);
    expect(groups[0].events).toHaveLength(1);
    expect(groups[1].events.map((e) => e.at)).toEqual(['2026-11-02T01:30:00Z', '2026-11-01T10:00:00Z']);
  });

  it('keeps only the chosen release', () => {
    const groups = groupByDay([event('2026-11-01T10:00:00Z'), event('2026-11-01T11:00:00Z', 'release-2.2.0')], 'release-2.2.0');

    expect(groups.flatMap((g) => g.events)).toHaveLength(1);
  });

  it('is empty for no events', () => {
    expect(groupByDay([], '')).toEqual([]);
  });
});

describe('timelineReleases', () => {
  it('lists each release once, as versions, ignoring events without one', () => {
    const e = (release: string | null) => ({ kind: 'DEPLOY' as const, at: 'x', stageId: 's', environment: 'DEV', release, by: 'x' });

    expect(timelineReleases([e('release-2.10.0'), e('release-2.2.0'), e(null), e('release-2.2.0')]))
      .toEqual(['release-2.2.0', 'release-2.10.0']);
  });
});
