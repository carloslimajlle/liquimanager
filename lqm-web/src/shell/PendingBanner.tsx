import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { BellRing, X } from 'lucide-react';

import { itemKey, pendingTarget, unseen, useMyPending, usePendingLive } from '../api/pending';
import { useMessages } from '../i18n/useMessages';
import { describePending } from './describePending';

/**
 * The notice at the top of every screen when something waits for this person's decision. It arrives the moment the request is
 * opened (the server pushes it), says what it is, and leads to where it is decided. Dismissing it hides what is open now; whatever
 * arrives afterwards brings it back. The bell in the topbar keeps the full list.
 *
 * Also owns the live connection of the whole shell, and puts the count in the tab title, so it is seen from another tab too.
 */
export default function PendingBanner() {
  const t = useMessages();
  const p = t.pending;
  usePendingLive();
  const pending = useMyPending();
  const items = pending.data?.items ?? [];
  const total = pending.data?.total ?? 0;
  const [dismissed, setDismissed] = useState<ReadonlySet<string>>(new Set());
  const visible = unseen(items, dismissed);

  useEffect(() => {
    const base = document.title.replace(/^\(\d+\) /, '');
    document.title = total > 0 ? `(${total}) ${base}` : base;
    return () => {
      document.title = base;
    };
  }, [total]);

  if (visible.length === 0) {
    return null;
  }
  const first = visible[0];
  return (
    <div
      role="status"
      aria-live="polite"
      className="sticky top-0 z-20 -mx-7 mb-2 flex items-center gap-3 border-b border-primary bg-primary-50 px-7 py-2.5"
    >
      <BellRing size={16} strokeWidth={2} aria-hidden className="shrink-0 text-primary" />
      <div className="min-w-0 flex-1">
        <b className="text-text">{p.banner.headline(total)}</b>
        <span className="ml-2 text-text-2">
          {describePending(first, p)}
          {visible.length > 1 ? ` · ${p.banner.more(visible.length - 1)}` : ''}
        </span>
      </div>
      <Link
        to={pendingTarget(first)}
        className="shrink-0 rounded-md bg-primary px-3 py-1 text-[13px] font-semibold text-white hover:bg-primary-600"
      >
        {p.banner.open}
      </Link>
      <button
        type="button"
        aria-label={p.banner.dismiss}
        title={p.banner.dismiss}
        onClick={() => setDismissed(new Set([...dismissed, ...items.map(itemKey)]))}
        className="shrink-0 text-text-2 hover:text-text"
      >
        <X size={16} strokeWidth={2} aria-hidden />
      </button>
    </div>
  );
}
