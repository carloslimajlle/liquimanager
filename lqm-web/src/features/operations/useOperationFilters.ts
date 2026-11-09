import { useCallback, useMemo } from 'react';
import { useSearchParams } from 'react-router-dom';

import type { OperationFilters, Period } from '../../api/operations';

export const DEFAULT_PERIOD: Period = '7d';
export const DEFAULT_SORT = 'startedAt,desc';
export const PAGE_SIZE = 25;

const FILTER_KEYS = [
  'q', 'status', 'type', 'env', 'command', 'project', 'connection', 'changelog', 'executedBy', 'source',
] as const;

const PERIODS: Period[] = ['7d', '30d', '90d', 'all'];

/**
 * The screen's whole state lives in the URL (spec: "filters are reflected in the URL
 * — a shareable link"), so a copied address reopens exactly the same view. Changing
 * any filter sends the user back to page 1: page 7 of a different result set means nothing.
 */
export function useOperationFilters() {
  const [params, setParams] = useSearchParams();

  const filters = useMemo<OperationFilters>(() => {
    const out: OperationFilters = {};
    for (const key of FILTER_KEYS) {
      const value = params.get(key);
      if (value) {
        out[key] = value;
      }
    }
    const period = params.get('period');
    out.period = PERIODS.includes(period as Period) ? (period as Period) : DEFAULT_PERIOD;
    return out;
  }, [params]);

  const page = Math.max(0, Number.parseInt(params.get('page') ?? '0', 10) || 0);
  const sort = params.get('sort') ?? DEFAULT_SORT;

  const setFilter = useCallback(
    (key: string, value: string) => {
      setParams((current) => {
        const next = new URLSearchParams(current);
        if (value) {
          next.set(key, value);
        } else {
          next.delete(key);
        }
        next.delete('page');
        return next;
      });
    },
    [setParams],
  );

  const setPage = useCallback(
    (value: number) => {
      setParams((current) => {
        const next = new URLSearchParams(current);
        if (value > 0) {
          next.set('page', String(value));
        } else {
          next.delete('page');
        }
        return next;
      });
    },
    [setParams],
  );

  /** Clicking a sorted column flips its direction; a new column starts descending. */
  const toggleSort = useCallback(
    (key: string) => {
      const [currentKey, direction] = sort.split(',');
      const nextDirection = currentKey === key && direction === 'desc' ? 'asc' : 'desc';
      setFilter('sort', `${key},${nextDirection}`);
    },
    [sort, setFilter],
  );

  const clear = useCallback(() => setParams(new URLSearchParams()), [setParams]);

  const active = FILTER_KEYS.some((key) => params.get(key)) || (params.get('period') ?? DEFAULT_PERIOD) !== DEFAULT_PERIOD;

  return { filters, page, sort, setFilter, setPage, toggleSort, clear, active };
}
