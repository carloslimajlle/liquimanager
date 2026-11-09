import { useState } from 'react';

export const ADMIN_PAGE_SIZE = 25;

/** The slice of `rows` for the requested page, clamped so a list that shrank never leaves the screen on a page that is gone. */
export function pageSlice<T>(rows: T[], requested: number, size = ADMIN_PAGE_SIZE) {
  const totalPages = Math.max(1, Math.ceil(rows.length / size));
  const page = Math.max(0, Math.min(requested, totalPages - 1));
  return { page, totalPages, rows: rows.slice(page * size, page * size + size), total: rows.length };
}

/**
 * Client-side paging for the administration lists: the cadastro endpoints return everything (a workspace has tens of
 * projects but can have hundreds of connections), and rendering all of them at once makes the screen unusable.
 */
export function usePaged<T>(rows: T[] | undefined, size = ADMIN_PAGE_SIZE) {
  const [requested, setPage] = useState(0);
  return { ...pageSlice(rows ?? [], requested, size), setPage };
}
