import type { ReactNode } from 'react';
import { LogOut } from 'lucide-react';
import { Link } from 'react-router-dom';

import { signOut } from '../auth/auth';
import { useMe } from '../api/admin';
import { useProfile } from '../auth/AuthGate';
import { useMessages } from '../i18n/useMessages';
import { crumbLabel, crumbTarget } from './crumbs';
import type { Crumb } from './crumbs';
import PendingBell from './PendingBell';

function initialsOf(name: string): string {
  return name.split(/\s+/).filter(Boolean).slice(0, 2).map((part) => part[0]!.toUpperCase()).join('') || '?';
}

/** 52 px topbar: breadcrumb on the left, actions and user on the right. */
export default function Topbar({
  trail,
  actions,
}: {
  trail: Crumb[];
  actions?: ReactNode;
}) {
  const t = useMessages();
  // The signed-in person; without an identity provider (local development), who the API says the caller is. Never a made-up one:
  // after a sign-out the session is gone for an instant before the browser leaves, and nobody should show up in that gap.
  const profile = useProfile();
  const me = useMe();
  const name = profile?.name ?? me.data?.name;
  const who = name ? { name, initials: profile?.initials ?? initialsOf(name) } : null;

  return (
    <div className="flex items-center gap-2.5" style={{ height: 52 }}>
      <nav aria-label={t.nav.breadcrumbLabel} className="flex items-center gap-2 text-text-2">
        {trail.map((part, index) => {
          const last = index === trail.length - 1;
          const label = crumbLabel(part);
          const to = crumbTarget(part, last);
          return (
            <span key={`${index}-${label}`} className="flex items-center gap-2">
              {last
                ? <b className="text-text" aria-current="page">{label}</b>
                : to ? <Link to={to} className="hover:text-text hover:underline">{label}</Link> : <span>{label}</span>}
              {!last && <span className="text-text-3" aria-hidden>›</span>}
            </span>
          );
        })}
      </nav>
      <div className="flex-1" />
      {actions}
      <PendingBell />
      {who && (
        <div className="flex items-center gap-2 font-semibold">
          <span
            aria-hidden
            className="grid place-items-center rounded-full bg-primary text-[11px] font-bold text-white"
            style={{ height: 26, width: 26 }}
          >
            {who.initials}
          </span>
          {who.name}
        </div>
      )}
      {profile && (
        <button type="button" aria-label={t.auth.logout} title={t.auth.logout} onClick={() => void signOut()}
          className="text-text-2 hover:text-text">
          <LogOut size={16} strokeWidth={2} aria-hidden />
        </button>
      )}
    </div>
  );
}
