import { useState } from 'react';

import { downloadFile } from '../../api/download';
import { useAuditVerification } from '../../api/access';
import { useAuditPage } from '../../api/admin';
import type { AuditRow } from '../../api/admin';
import { useCan } from '../../auth/useCan';
import DataTable from '../../components/DataTable';
import type { Column } from '../../components/DataTable';
import { fieldClass, primaryButton } from '../../components/FormDialog';
import PageHeader from '../../components/PageHeader';
import Pagination from '../../components/Pagination';
import StatusBadge from '../../components/StatusBadge';
import { EmptyState, ErrorCard } from '../../components/States';
import { fill } from '../../i18n';
import { useLocale, useMessages } from '../../i18n/useMessages';
import { formatShortDateTime } from '../../lib/format';
import AdminTabs from '../../shell/AdminTabs';
import Topbar from '../../shell/Topbar';
import { crumb } from '../../shell/crumbs';
import { describeEvent } from '../access/SidePanels';

const PAGE_SIZE = 25;

/** /admin/audit (RF-ADM-04): the whole trail, filterable, with the chain verification and the CSV and PDF exports. */
export default function Audit() {
  const t = useMessages();
  const a = t.admin;
  const u = a.audit;
  const locale = useLocale();
  const canExport = useCan()('audit:export');
  const [actor, setActor] = useState('');
  const [action, setAction] = useState('');
  const [applied, setApplied] = useState<{ actor?: string; action?: string }>({});
  const [page, setPage] = useState(0);
  const audit = useAuditPage(applied, page, PAGE_SIZE);
  const verification = useAuditVerification();

  const exportPath = (format: 'csv' | 'pdf') => `/audit/export?${new URLSearchParams({ format, ...(applied.actor ? { actor: applied.actor } : {}), ...(applied.action ? { action: applied.action } : {}) }).toString()}`;
  const columns: Column<AuditRow>[] = [
    { key: 'when', header: u.columns.when, cell: (r) => formatShortDateTime(r.at, locale) },
    { key: 'actor', header: u.columns.actor, cell: (r) => r.actor },
    { key: 'action', header: u.columns.action, cell: (r) => (
      <span>{describeEvent(r, t.access.side.audit.actions, t.access.side.audit.fallback)} <code className="font-mono text-[11.5px] text-text-3">{r.action}</code></span>
    ) },
    { key: 'target', header: u.columns.target, cell: (r) => r.targetType ? <code className="font-mono text-[12px]">{`${r.targetType}${r.targetId ? `:${r.targetId}` : ''}`}</code> : a.common.none },
  ];

  return (
    <>
      <Topbar trail={[crumb(a.breadcrumbRoot, '/admin'), u.title]} />
      <PageHeader title={u.title} subtitle={u.subtitle}
        right={canExport && (
          <div className="flex gap-2">
            <button type="button" className={primaryButton} onClick={() => void downloadFile(exportPath('csv'), 'audit.csv')}>{u.export}</button>
            <button type="button" className={primaryButton} onClick={() => void downloadFile(exportPath('pdf'), 'audit.pdf')}>{u.exportPdf}</button>
          </div>
        )} />
      <AdminTabs />
      <div className="mb-3 flex items-end gap-2">
        <label className="flex flex-col gap-1 text-[12px] font-semibold">{u.filters.actor}
          <input className={fieldClass} value={actor} onChange={(ev) => setActor(ev.target.value)} />
        </label>
        <label className="flex flex-col gap-1 text-[12px] font-semibold">{u.filters.action}
          <input className={`${fieldClass} font-mono`} value={action} placeholder="access.%" onChange={(ev) => setAction(ev.target.value)} />
        </label>
        <button type="button" className={primaryButton}
          onClick={() => { setPage(0); setApplied({ actor: actor.trim() || undefined, action: action.trim() || undefined }); }}>{u.filters.apply}</button>
        {verification.data && (
          <span className="ml-auto">
            <StatusBadge tone={verification.data.intact ? 'success' : 'danger'}>
              {verification.data.intact ? fill(u.verified, { n: verification.data.checked }) : fill(u.broken, { seq: verification.data.brokenAt ?? 0 })}
            </StatusBadge>
          </span>
        )}
      </div>
      <section className="rounded-card border border-border bg-surface shadow-card">
        {audit.isError ? (
          <ErrorCard message={a.common.loadError} onRetry={() => audit.refetch()} />
        ) : audit.data && audit.data.content.length === 0 ? (
          <EmptyState title={u.empty.title} hint={u.empty.hint} />
        ) : (
          <>
            <DataTable columns={columns} rows={audit.data?.content ?? []} rowKey={(r) => String(r.seq)} loading={audit.isLoading} caption={u.title} />
            {audit.data && (
              <div className="px-4 py-3"><Pagination page={page} totalPages={Math.ceil(audit.data.totalElements / PAGE_SIZE)} onPage={setPage} /></div>
            )}
          </>
        )}
      </section>
    </>
  );
}
