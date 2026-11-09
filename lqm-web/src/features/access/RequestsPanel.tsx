import { useState } from 'react';

import { useAccessRequests, useDismissAccessRequest } from '../../api/accessRequests';
import { useCan } from '../../auth/useCan';
import StatusBadge from '../../components/StatusBadge';
import { fill } from '../../i18n';
import { useLocale, useMessages } from '../../i18n/useMessages';
import { formatDateTime } from '../../lib/format';

/**
 * The line at the door: people who signed in, have no access and asked for it. Whoever administers access sees them here (and in the bell),
 * grants a role from the request (the dialog opens with the person already chosen) or sets it aside. The request grants nothing by itself.
 */
export default function RequestsPanel({ onGrant }: { onGrant: (login: string) => void }) {
  const t = useMessages();
  const r = t.access.requests;
  const locale = useLocale();
  const canAdmin = useCan()('assignment:admin');
  const requests = useAccessRequests(canAdmin);
  const dismiss = useDismissAccessRequest();
  const [error, setError] = useState<string | null>(null);

  const rows = requests.data ?? [];
  if (!canAdmin || rows.length === 0) {
    return null;
  }

  async function setAside(id: string) {
    if (!window.confirm(r.confirmDismiss)) {
      return;
    }
    setError(null);
    try {
      await dismiss(id);
    } catch (err) {
      setError(err instanceof Error && err.message ? err.message : r.error);
    }
  }

  return (
    <section className="mb-3.5 rounded-card border border-border bg-surface p-4 shadow-card" aria-label={r.title}>
      <div className="mb-2 flex items-center gap-2">
        <h2 className="text-[15px] font-semibold">{r.title}</h2>
        <StatusBadge tone="warning">{String(rows.length)}</StatusBadge>
        <span className="text-[12px] text-text-2">{r.subtitle}</span>
      </div>
      {error && <p role="alert" className="mb-2 text-[12.5px] text-danger">{error}</p>}
      <ul className="flex flex-col divide-y divide-border">
        {rows.map((request) => (
          <li key={request.id} className="flex flex-wrap items-start gap-3 py-2.5 text-[13px]">
            <div className="min-w-0 flex-1">
              <div className="flex flex-wrap items-center gap-2">
                <b className="font-mono">{request.requester}</b>
                {request.email && <span className="text-text-2">{request.email}</span>}
                <span className="text-[12px] text-text-3">{fill(r.when, { when: formatDateTime(request.requestedAt, locale) })}</span>
              </div>
              <div className="mt-1 flex flex-wrap items-center gap-1.5 text-[12px]">
                <span className="text-text-2">{r.groups}</span>
                {request.groups.length > 0
                  ? request.groups.map((group) => <span key={group} className="rounded-badge bg-surface-alt px-2 py-0.5 font-mono ring-1 ring-border">{group}</span>)
                  : <span className="text-text-3">{r.noGroups}</span>}
              </div>
              {request.message && <p className="mt-1 text-text-2">&ldquo;{request.message}&rdquo;</p>}
            </div>
            <div className="flex shrink-0 gap-2">
              <button type="button" onClick={() => onGrant(request.requester)}
                className="rounded-control bg-primary px-3 py-1 text-[12px] font-semibold text-white">{r.grant}</button>
              <button type="button" onClick={() => void setAside(request.id)}
                className="rounded-control border border-border px-3 py-1 text-[12px] font-semibold hover:bg-surface-alt">{r.dismiss}</button>
            </div>
          </li>
        ))}
      </ul>
    </section>
  );
}
