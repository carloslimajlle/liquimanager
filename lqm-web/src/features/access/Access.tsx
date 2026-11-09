import { useState } from 'react';

import { useAccess, useAccessActions } from '../../api/access';
import { useCan } from '../../auth/useCan';
import type { Binding } from '../../api/access';
import DataTable from '../../components/DataTable';
import type { Column } from '../../components/DataTable';
import KpiCard from '../../components/KpiCard';
import PageHeader from '../../components/PageHeader';
import StatusBadge from '../../components/StatusBadge';
import { EmptyState, ErrorCard } from '../../components/States';
import { fill } from '../../i18n';
import { useLocale, useMessages } from '../../i18n/useMessages';
import { formatNumber, formatShortDateTime } from '../../lib/format';
import { ExceptionsPanel } from '../governance/ExceptionsPanel';
import AdminTabs from '../../shell/AdminTabs';
import Topbar from '../../shell/Topbar';
import GrantDialog from './GrantDialog';
import RequestsPanel from './RequestsPanel';
import { AuditPanel, SecretsPanel } from './SidePanels';

function initials(name: string): string {
  return name.split(/\s+/).filter(Boolean).slice(0, 2).map((w) => w[0].toUpperCase()).join('');
}

/** Screen 08: who has which role where, and the rules that stop one person from holding both halves of a control. */
export default function Access() {
  const t = useMessages();
  const a = t.access;
  const locale = useLocale();
  const access = useAccess();
  const actions = useAccessActions();
  const canAdmin = useCan()('assignment:admin');
  // null: closed; '' : open; a login: open with that person already searched (from a request for access)
  const [granting, setGranting] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const data = access.data;

  async function revoke(binding: Binding) {
    if (!window.confirm(a.table.confirmRevoke)) {
      return;
    }
    setError(null);
    try {
      await actions.revoke(binding.id);
    } catch (err) {
      setError(err instanceof Error && err.message ? err.message : a.grant.error);
    }
  }

  const columns: Column<Binding>[] = [
    {
      key: 'principal', header: a.table.columns.principal,
      cell: (b) => (
        <span className="flex items-center gap-2">
          <span aria-hidden className="flex size-6 items-center justify-center rounded-full bg-primary-50 text-[10px] font-bold text-primary">
            {initials(b.principal.displayName)}
          </span>
          {b.principal.displayName}
        </span>
      ),
    },
    { key: 'type', header: a.table.columns.type, cell: (b) => (b.principal.type === 'GROUP' ? a.table.group : a.table.user) },
    {
      key: 'role', header: a.table.columns.role,
      cell: (b) => (b.roleTemplateName ? <StatusBadge tone="info">{b.roleTemplateName}</StatusBadge> : <span className="text-text-3">{a.table.noRole}</span>),
    },
    {
      key: 'scope', header: a.table.columns.scope,
      cell: (b) => a.scopes[b.scopeType as keyof typeof a.scopes] ?? b.scopeType,
    },
    {
      key: 'permission', header: a.table.columns.permission,
      cell: (b) => (
        <span className="font-mono text-[11.5px]">
          {b.permissions.join(' ')}
          {b.deniedPermissions.length > 0 && (
            <span className="ml-2 text-danger">{fill(a.table.denied, { list: b.deniedPermissions.join(' ') })}</span>
          )}
        </span>
      ),
    },
    { key: 'approver', header: a.table.columns.approver, cell: (b) => b.approver ?? t.common.none },
    {
      key: 'expires', header: a.table.columns.expires,
      cell: (b) => (b.expiresAt ? formatShortDateTime(b.expiresAt, locale) : a.table.never),
    },
    {
      key: 'actions', header: a.table.columns.actions, align: 'right',
      cell: (b) => canAdmin ? (
        <button type="button" onClick={() => revoke(b)}
          className="text-[12px] font-semibold text-danger hover:underline">{a.table.revoke}</button>
      ) : null,
    },
  ];

  const stat = (label: string, value: number | undefined, tone?: 'warning') => (
    <KpiCard label={label} value={value === undefined ? t.common.none : formatNumber(value, locale)} tone={tone} />
  );

  return (
    <>
      <Topbar trail={[a.breadcrumbRoot, a.title]} />
      <PageHeader
        title={a.title}
        subtitle={a.subtitle}
        right={canAdmin &&
          <button type="button" onClick={() => setGranting('')}
            className="rounded-control bg-primary px-3 py-1.5 text-[12px] font-semibold text-white">{a.grant.open}</button>
        }
      />
      <AdminTabs />
      <RequestsPanel onGrant={(login) => setGranting(login)} />
      <div className="mb-3.5 grid grid-cols-4 gap-3.5">
        {stat(a.stats.groups, data?.summary.groups)}
        {stat(a.stats.members, data?.summary.members)}
        {stat(a.stats.openExceptions, data?.summary.openExceptions, 'warning')}
        {stat(a.stats.auditEvents, data?.summary.auditEvents)}
      </div>

      <div className="grid grid-cols-[1fr_300px] items-start gap-3.5">
        <section className="rounded-card border border-border bg-surface shadow-card">
          <div className="flex items-center px-4 py-3">
            <h2 className="text-[15px] font-semibold">{a.table.title}</h2>
            <span className="ml-auto text-[12px] text-text-2">{a.table.rules}</span>
          </div>
          {error && <p role="alert" className="px-4 pb-2 text-[12.5px] text-danger">{error}</p>}
          {access.isError ? (
            <ErrorCard message={a.table.error} onRetry={() => access.refetch()} />
          ) : data && data.bindings.length === 0 ? (
            <EmptyState title={a.table.empty.title} hint={a.table.empty.hint} />
          ) : (
            <DataTable
              columns={columns}
              rows={data?.bindings ?? []}
              rowKey={(b) => b.id}
              loading={access.isLoading}
              rowClass={(b) => (b.deniedPermissions.length > 0 ? 'bg-row-danger' : undefined)}
              caption={a.table.title}
            />
          )}
        </section>

        <aside className="flex flex-col gap-3.5">
          <ExceptionsPanel />
          <SecretsPanel />
          <AuditPanel />
        </aside>
      </div>

      {granting !== null && <GrantDialog initialSearch={granting} onClose={() => setGranting(null)} />}
    </>
  );
}
