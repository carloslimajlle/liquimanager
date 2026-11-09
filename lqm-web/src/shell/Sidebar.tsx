import { useState } from 'react';
import { NavLink } from 'react-router-dom';
import {
  Activity, Clock, Code, Folder, HelpCircle, Home, Settings, Shield, TrendingUp, UserPlus,
} from 'lucide-react';
import type { LucideIcon } from 'lucide-react';

import { useMe } from '../api/admin';
import { can } from '../auth/permissions';
import { useMessages } from '../i18n/useMessages';

/** Whoever holds any of these has something to do in Administrar; everyone else does not even see the entry (the API refuses them anyway). */
export const ADMIN_PERMISSIONS = [
  'workspace:admin', 'assignment:admin', 'catalog:edit', 'connection:edit', 'pipeline:edit', 'token:manage', 'audit:export',
];

const EXPANDED_KEY = 'lqm.sidebarExpanded';

function storedExpanded(): boolean {
  try {
    return window.localStorage.getItem(EXPANDED_KEY) === '1';
  } catch {
    return false;
  }
}

/**
 * 56 px sidebar, primary background, 18 px icons.
 * Order and icons follow docs/10-design-system.md § 2.
 */
type Item = { to: string; label: string; Icon: LucideIcon };

function Link({ to, label, Icon, expanded }: Item & { expanded: boolean }) {
  return (
    <NavLink
      to={to}
      end={to === '/'}
      title={label}
      aria-label={label}
      className={({ isActive }) =>
        [
          'flex h-9 items-center rounded-[8px] transition-colors',
          expanded ? 'w-full gap-3 px-2.5' : 'w-9 justify-center',
          isActive ? 'bg-white/18 text-white' : 'text-white/75 hover:bg-white/10 hover:text-white',
        ].join(' ')
      }
    >
      <Icon size={18} strokeWidth={2} aria-hidden className="shrink-0" />
      {expanded && <span className="truncate text-[13px] font-semibold">{label}</span>}
    </NavLink>
  );
}

export default function Sidebar() {
  const t = useMessages();
  const me = useMe();
  const [expanded, setExpanded] = useState(storedExpanded);
  const toggle = () => {
    setExpanded((value) => {
      try {
        window.localStorage.setItem(EXPANDED_KEY, value ? '0' : '1');
      } catch {
        // the choice just is not remembered
      }
      return !value;
    });
  };
  // Hidden until /me says the person administers something: showing it first and taking it away would flicker for everyone else.
  const administers = me.data !== undefined && ADMIN_PERMISSIONS.some((permission) => can(me.data.permissions, permission));

  const primary: Item[] = [
    { to: '/', label: t.nav.overview, Icon: Home },
    { to: '/projects', label: t.nav.projects, Icon: Folder },
    { to: '/operations', label: t.nav.operations, Icon: Activity },
    { to: '/govern', label: t.nav.governance, Icon: Shield },
    { to: '/drift', label: t.nav.drift, Icon: Clock },
    { to: '/pipelines', label: t.nav.automate, Icon: TrendingUp },
  ];

  const secondary: Item[] = [
    { to: '/developer', label: t.nav.developerMode, Icon: Code },
    ...(administers ? [{ to: '/admin', label: t.nav.administer, Icon: UserPlus }] : []),
  ];

  const footer: Item[] = [
    { to: '/settings', label: t.nav.settings, Icon: Settings },
    { to: '/help', label: t.nav.help, Icon: HelpCircle },
  ];

  return (
    <nav aria-label={t.nav.mainLabel} className={['flex flex-col gap-1.5 bg-primary py-3.5 transition-[width]', expanded ? 'w-60 items-stretch px-2.5' : 'w-14 items-center'].join(' ')}>
      <button type="button" onClick={toggle} aria-expanded={expanded} aria-label={expanded ? t.nav.collapse : t.nav.expand}
        title={expanded ? t.nav.collapse : t.nav.expand}
        className={['mb-3.5 flex items-center gap-3 rounded-[8px] text-left', expanded ? 'px-0.5' : ''].join(' ')}>
        <span className="grid h-7 w-7 shrink-0 place-items-center rounded-[8px] bg-white text-[13px] font-extrabold text-primary">LM</span>
        {expanded && <span className="truncate text-[14px] font-bold text-white">{t.app.name}</span>}
      </button>
      {primary.map((item) => <Link key={item.to} {...item} expanded={expanded} />)}
      <div className="h-3" />
      {secondary.map((item) => <Link key={item.to} {...item} expanded={expanded} />)}
      <div className={['mt-auto flex flex-col gap-1.5', expanded ? '' : 'items-center'].join(' ')}>
        {footer.map((item) => <Link key={item.to} {...item} expanded={expanded} />)}
      </div>
    </nav>
  );
}
