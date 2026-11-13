/**
 * One step of the breadcrumb. A plain string is text only (the current page, or a step with nowhere to go); a step with `to` is a
 * link, so the person can walk back up instead of reaching for the sidebar.
 */
export type Crumb = string | { label: string; to: string };

/** A breadcrumb step that links to `to`. */
export function crumb(label: string, to: string): Crumb {
  return { label, to };
}

export function crumbLabel(item: Crumb): string {
  return typeof item === 'string' ? item : item.label;
}

/** Where a step goes, or null when it must not be a link: text-only steps and the last one, which is the page you are on. */
export function crumbTarget(item: Crumb, last: boolean): string | null {
  return typeof item === 'string' || last ? null : item.to;
}
