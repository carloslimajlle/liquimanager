import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { Bell } from 'lucide-react';

import { itemKey, pendingTarget, useMyPending } from '../api/pending';
import { formatShortDateTime } from '../lib/format';
import { useLocale, useMessages } from '../i18n/useMessages';
import { describePending } from './describePending';

/**
 * The bell of every topbar. A badge counts what waits for this person to decide (approvals they can give); the panel lists it, each
 * line leading to where it is decided. Not a toast: it stays until somebody decides, and costs no click when there is nothing.
 */
export default function PendingBell() {
  const t = useMessages();
  const p = t.pending;
  const locale = useLocale();
  const pending = useMyPending();
  const [open, setOpen] = useState(false);
  const root = useRef<HTMLDivElement>(null);
  const total = pending.data?.total ?? 0;
  const items = pending.data?.items ?? [];

  useEffect(() => {
    if (!open) {
      return undefined;
    }
    const away = (ev: MouseEvent) => {
      if (root.current && !root.current.contains(ev.target as Node)) {
        setOpen(false);
      }
    };
    const esc = (ev: KeyboardEvent) => {
      if (ev.key === 'Escape') {
        setOpen(false);
      }
    };
    document.addEventListener('mousedown', away);
    document.addEventListener('keydown', esc);
    return () => {
      document.removeEventListener('mousedown', away);
      document.removeEventListener('keydown', esc);
    };
  }, [open]);

  return (
    <div ref={root} className="relative">
      <button
        type="button"
        aria-label={total > 0 ? p.labelWith(total) : t.nav.notifications}
        aria-expanded={open}
        aria-haspopup="dialog"
        onClick={() => setOpen((value) => !value)}
        className="relative text-text-2 hover:text-text"
      >
        <Bell size={16} strokeWidth={2} aria-hidden />
        {total > 0 && (
          <span
            aria-hidden
            className="absolute -right-2 -top-2 grid min-w-[16px] place-items-center rounded-full bg-primary px-1 text-[10px] font-bold leading-4 text-white"
          >
            {total > 99 ? '99+' : total}
          </span>
        )}
      </button>
      {open && (
        <div role="dialog" aria-label={p.title} className="absolute right-0 top-7 z-30 w-[360px] rounded-lg border border-border bg-surface shadow-card">
          <div className="border-b border-border px-3.5 py-2.5 font-semibold">{p.title}</div>
          {items.length === 0 ? (
            <div className="px-3.5 py-5 text-center text-text-2">{p.empty}</div>
          ) : (
            <ul className="max-h-[360px] overflow-auto">
              {items.map((item) => (
                <li key={itemKey(item)} className="border-b border-border last:border-b-0">
                  <Link to={pendingTarget(item)} onClick={() => setOpen(false)} className="block px-3.5 py-2.5 hover:bg-bg">
                    <div className="text-[11px] font-bold uppercase tracking-wide text-text-3">{p.kinds[item.kind]}</div>
                    <div className="text-text">{describePending(item, p)}</div>
                    <div className="text-[12px] text-text-2">{p.by(item.requestedBy)} · {formatShortDateTime(item.at, locale)}</div>
                  </Link>
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
    </div>
  );
}
