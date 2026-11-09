import { NavLink } from 'react-router-dom';

import { useMessages } from '../i18n/useMessages';

/** Switches between the administration screens; the sidebar has a single Administer entry. */
export default function AdminTabs() {
  const t = useMessages();
  const tabs = [
    { to: '/admin/access', label: t.access.title },
    { to: '/admin/projects', label: t.admin.tabs.projects },
    { to: '/admin/connections', label: t.admin.tabs.connections },
    { to: '/admin/environments', label: t.admin.tabs.environments },
    { to: '/admin/pipelines', label: t.admin.tabs.pipelines },
    { to: '/admin/tokens', label: t.admin.tabs.tokens },
    { to: '/admin/notifications', label: t.notifications.title },
    { to: '/admin/audit', label: t.admin.tabs.audit },
  ];
  return (
    <nav aria-label={t.nav.administer} className="mb-3 flex flex-wrap gap-1">
      {tabs.map((tab) => (
        <NavLink
          key={tab.to}
          to={tab.to}
          className={({ isActive }) =>
            `rounded-control px-3 py-1.5 text-[12px] font-semibold ${isActive ? 'bg-primary-50 text-primary' : 'text-text-2 hover:bg-surface-alt'}`
          }
        >
          {tab.label}
        </NavLink>
      ))}
    </nav>
  );
}
