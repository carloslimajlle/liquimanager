import { NavLink } from 'react-router-dom';

import { useMessages } from '../../i18n/useMessages';

/** Switches between the two governance screens; the sidebar has a single Governance entry. */
export default function GovernanceTabs() {
  const t = useMessages();
  const tabs = [
    { to: '/govern/enforcement', label: t.governance.tabs.enforcement },
    { to: '/govern/checks', label: t.governance.tabs.library },
    { to: '/govern/packages', label: t.governance.tabs.packages },
  ];
  return (
    <nav aria-label={t.governance.tabs.label} className="mb-3 flex gap-1">
      {tabs.map((tab) => (
        <NavLink
          key={tab.to}
          to={tab.to}
          className={({ isActive }) =>
            `rounded-control px-3 py-1.5 text-[12px] font-semibold ${
              isActive ? 'bg-primary-50 text-primary' : 'text-text-2 hover:bg-surface-alt'
            }`
          }
        >
          {tab.label}
        </NavLink>
      ))}
    </nav>
  );
}
