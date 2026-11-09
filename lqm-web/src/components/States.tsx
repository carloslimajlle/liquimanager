import type { ReactNode } from 'react';
import { Inbox, TriangleAlert } from 'lucide-react';

import { useMessages } from '../i18n/useMessages';

/**
 * The states every screen needs besides "loaded" (docs/10 § 5): empty and error.
 * Loading is handled where the data is shown, as skeleton rows or cards.
 */
export function EmptyState({ title, hint, action }: { title: string; hint?: string; action?: ReactNode }) {
  return (
    <div className="flex flex-col items-center gap-2 px-6 py-14 text-center">
      <Inbox size={28} className="text-text-3" aria-hidden />
      <div className="font-semibold">{title}</div>
      {hint && <p className="max-w-md text-text-2">{hint}</p>}
      {action}
    </div>
  );
}

export function ErrorCard({ message, onRetry }: { message: string; onRetry?: () => void }) {
  const t = useMessages();
  return (
    <div role="alert" className="flex flex-col items-center gap-2 px-6 py-14 text-center">
      <TriangleAlert size={28} className="text-danger" aria-hidden />
      <div className="font-semibold">{message}</div>
      {onRetry && (
        <button
          type="button"
          onClick={onRetry}
          className="rounded-control border border-border bg-surface px-3 py-1.5 font-medium hover:bg-surface-alt"
        >
          {t.common.retry}
        </button>
      )}
    </div>
  );
}
