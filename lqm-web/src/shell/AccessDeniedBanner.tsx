import { useState, useSyncExternalStore } from 'react';
import { ShieldAlert, X } from 'lucide-react';

import { deniedCount, subscribeDenied } from '../api/denied';
import { useMessages } from '../i18n/useMessages';

/** Shown when the API refuses a request with 403: the person is signed in but may not do (or see) this. Dismissed until the next refusal. */
export default function AccessDeniedBanner() {
  const t = useMessages();
  const count = useSyncExternalStore(subscribeDenied, deniedCount, () => 0);
  const [dismissedAt, setDismissedAt] = useState(0);
  if (count === 0 || count === dismissedAt) {
    return null;
  }
  return (
    <div role="alert" className="sticky top-0 z-30 -mx-7 mb-2 flex items-center gap-3 border-b border-danger bg-danger-bg px-7 py-2.5 text-danger">
      <ShieldAlert size={16} strokeWidth={2} aria-hidden className="shrink-0" />
      <span className="min-w-0 flex-1"><b>{t.auth.denied.title}</b> {t.auth.denied.text}</span>
      <button type="button" aria-label={t.auth.denied.dismiss} title={t.auth.denied.dismiss} onClick={() => setDismissedAt(count)}
        className="shrink-0 rounded-md p-1 hover:bg-surface"><X size={16} strokeWidth={2} aria-hidden /></button>
    </div>
  );
}
