import { describe, expect, it } from 'vitest';

import { pageSlice } from './paging';

const rows = Array.from({ length: 60 }, (_, i) => i);

describe('pageSlice', () => {
  it('shows the first page and counts the pages', () => {
    const result = pageSlice(rows, 0, 25);
    expect(result.rows).toHaveLength(25);
    expect(result.totalPages).toBe(3);
    expect(result.total).toBe(60);
  });

  it('gives the last, shorter page', () => {
    expect(pageSlice(rows, 2, 25).rows).toEqual(rows.slice(50));
  });

  it('pulls back to the last page when the list shrank under it', () => {
    const result = pageSlice(rows.slice(0, 30), 2, 25);
    expect(result.page).toBe(1);
    expect(result.rows).toHaveLength(5);
  });

  it('is one empty page for an empty list', () => {
    const result = pageSlice([], 3, 25);
    expect(result).toMatchObject({ page: 0, totalPages: 1, rows: [], total: 0 });
  });
});
