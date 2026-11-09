import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';

import { fill } from '../i18n';
import { useMessages } from '../i18n/useMessages';

/**
 * Links from a table row to the thing the cell names (the project, the changelog, the changeset). Clicking one must not also open the
 * row, which has its own destination, so the click does not bubble.
 */
export function ToPage({ to, children, mono = false }: { to: string; children: ReactNode; mono?: boolean }) {
  return (
    <Link to={to} onClick={(event) => event.stopPropagation()} className={`text-primary hover:underline ${mono ? 'font-mono' : ''}`}>
      {children}
    </Link>
  );
}

/** The first changeset of an operation, with "+N" when it ran more. */
export function ToChangeset({ id, name, more }: { id: string; name?: string | null; more: number }) {
  const t = useMessages();
  return (
    <ToPage to={`/changesets/${id}`} mono>
      {name ?? id.slice(0, 8)}{more > 0 ? ` ${fill(t.dashboard.activity.more, { n: more })}` : ''}
    </ToPage>
  );
}
