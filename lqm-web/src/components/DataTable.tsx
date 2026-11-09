import type { KeyboardEvent, ReactNode } from 'react';
import { ArrowDown, ArrowUp } from 'lucide-react';

/**
 * Server-driven table (docs/10 § 3): rows are 36 px, the header is small caps, sorting
 * and paging happen on the server, and a failing row can be tinted.
 *
 * It is a plain table rather than a TanStack Table instance on purpose: all the work
 * the library would do (sorting, paging, filtering) is delegated to the API here, so
 * what is left is rendering — and a headless table core would add a layer to no effect.
 */
export type Column<T> = {
  key: string;
  header: string;
  /** Present when the column can be sorted; this is the key sent to the API. */
  sortKey?: string;
  align?: 'left' | 'right';
  cell: (row: T) => ReactNode;
};

type Props<T> = {
  columns: Column<T>[];
  rows: T[];
  rowKey: (row: T) => string;
  /** `key,direction`, e.g. `startedAt,desc`. */
  sort?: string;
  onSort?: (sortKey: string) => void;
  onRowClick?: (row: T) => void;
  rowClass?: (row: T) => string | undefined;
  rowLabel?: (row: T) => string;
  loading?: boolean;
  skeletonRows?: number;
  caption?: string;
};

export default function DataTable<T>({
  columns, rows, rowKey, sort, onSort, onRowClick, rowClass, rowLabel,
  loading = false, skeletonRows = 8, caption,
}: Props<T>) {
  const [sortKey, direction] = (sort ?? '').split(',');

  function activate(event: KeyboardEvent, row: T) {
    if (onRowClick && (event.key === 'Enter' || event.key === ' ')) {
      event.preventDefault();
      onRowClick(row);
    }
  }

  return (
    <table className="w-full border-collapse text-[13px]">
      {caption && <caption className="sr-only">{caption}</caption>}
      <thead>
        <tr>
          {columns.map((column) => {
            const sorted = column.sortKey !== undefined && column.sortKey === sortKey;
            return (
              <th
                key={column.key}
                scope="col"
                aria-sort={sorted ? (direction === 'asc' ? 'ascending' : 'descending') : undefined}
                className={`px-3.5 py-2.5 text-[11px] font-semibold tracking-[0.06em] text-text-2 uppercase ${
                  column.align === 'right' ? 'text-right' : 'text-left'
                }`}
              >
                {column.sortKey && onSort ? (
                  <button
                    type="button"
                    onClick={() => onSort(column.sortKey!)}
                    className="inline-flex items-center gap-1 uppercase hover:text-text"
                  >
                    {column.header}
                    {sorted && (direction === 'asc'
                      ? <ArrowUp size={11} aria-hidden />
                      : <ArrowDown size={11} aria-hidden />)}
                  </button>
                ) : (
                  column.header
                )}
              </th>
            );
          })}
        </tr>
      </thead>
      <tbody>
        {loading
          ? Array.from({ length: skeletonRows }, (_, index) => (
              <tr key={index} aria-hidden className="h-9 border-t border-border">
                {columns.map((column) => (
                  <td key={column.key} className="px-3.5">
                    <span className="block h-3 animate-pulse rounded bg-border" />
                  </td>
                ))}
              </tr>
            ))
          : rows.map((row) => (
              <tr
                key={rowKey(row)}
                tabIndex={onRowClick ? 0 : undefined}
                aria-label={rowLabel?.(row)}
                onClick={onRowClick ? () => onRowClick(row) : undefined}
                onKeyDown={onRowClick ? (event) => activate(event, row) : undefined}
                className={`h-9 border-t border-border ${onRowClick ? 'cursor-pointer hover:bg-surface-alt' : ''} ${
                  rowClass?.(row) ?? ''
                }`}
              >
                {columns.map((column) => (
                  <td
                    key={column.key}
                    className={`px-3.5 py-1 ${column.align === 'right' ? 'text-right' : 'text-left'}`}
                  >
                    {column.cell(row)}
                  </td>
                ))}
              </tr>
            ))}
      </tbody>
    </table>
  );
}
