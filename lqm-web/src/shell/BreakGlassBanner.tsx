import { useSyncExternalStore } from 'react';
import { ShieldAlert } from 'lucide-react';

import { breakGlassSession, signOutBreakGlass, subscribeBreakGlass } from '../auth/breakGlass';
import { useLocale, useMessages } from '../i18n/useMessages';
import { formatDateTime } from '../lib/format';

/** While the emergency account is in use, every screen says so: it holds every permission and everything it does is audited. */
export default function BreakGlassBanner() {
  const t = useMessages();
  const locale = useLocale();
  const session = useSyncExternalStore(subscribeBreakGlass, breakGlassSession, () => null);
  if (!session) {
    return null;
  }
  return (
    <div role="alert" className="sticky top-0 z-30 -mx-7 mb-2 flex items-center gap-3 border-b border-danger bg-danger-bg px-7 py-2.5 text-danger">
      <ShieldAlert size={16} strokeWidth={2} aria-hidden className="shrink-0" />
      <span className="min-w-0 flex-1"><b>{t.breakGlass.banner.title}</b> {t.breakGlass.banner.text(formatDateTime(session.expiresAt, locale))}</span>
      <button type="button" onClick={() => void signOutBreakGlass()}
        className="shrink-0 rounded-md border border-danger px-3 py-1 text-[13px] font-semibold hover:bg-surface">{t.breakGlass.banner.end}</button>
    </div>
  );
}
