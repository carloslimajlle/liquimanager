import { useNavigate } from 'react-router-dom';

import { useProjectsOverview } from '../../api/admin';
import type { ProjectOverview } from '../../api/admin';
import DataTable from '../../components/DataTable';
import type { Column } from '../../components/DataTable';
import KpiCard from '../../components/KpiCard';
import PageHeader from '../../components/PageHeader';
import StatusBadge from '../../components/StatusBadge';
import { EmptyState, ErrorCard } from '../../components/States';
import { formatNumber, formatShortDateTime } from '../../lib/format';
import { useLocale, useMessages } from '../../i18n/useMessages';
import { isFailure, statusTone } from '../operations/rules';
import Topbar from '../../shell/Topbar';
import { crumb } from '../../shell/crumbs';

/** /projects: every application with its changelogs, connections, collector and last operation, each leading to its own page. */
export default function ProjectsIndex() {
  const t = useMessages();
  const p = t.projects;
  const locale = useLocale();
  const navigate = useNavigate();
  const overview = useProjectsOverview();
  const kpis = overview.data?.kpis;
  const rows = overview.data?.projects ?? [];
  const none = <span className="text-text-3">{t.common.none}</span>;

  const columns: Column<ProjectOverview>[] = [
    { key: 'name', header: p.columns.name, cell: (row) => <span><b>{row.name}</b> <code className="font-mono text-[12px] text-text-3">{row.slug}</code></span> },
    { key: 'team', header: p.columns.team, cell: (row) => row.team ?? none },
    { key: 'repo', header: p.columns.repo, cell: (row) => (row.repoUrl ? <code className="font-mono text-[12px]">{row.repoUrl}</code> : none) },
    { key: 'changelogs', header: p.columns.changelogs, align: 'right', cell: (row) => formatNumber(row.changelogs, locale) },
    { key: 'connections', header: p.columns.connections, align: 'right', cell: (row) => formatNumber(row.connections, locale) },
    { key: 'collector', header: p.columns.collector, cell: (row) => (row.collector ? <span className="font-mono text-[12px]">{p.collectors[row.collector] ?? row.collector}</span> : none) },
    {
      key: 'last', header: p.columns.lastOperation,
      cell: (row) => (row.lastOperationAt && row.lastOperationStatus ? (
        <span className="flex items-center gap-2 whitespace-nowrap">
          <span className="text-text-2">{formatShortDateTime(row.lastOperationAt, locale)}</span>
          <StatusBadge tone={statusTone(row.lastOperationStatus)}>
            {`${isFailure(row.lastOperationStatus) ? '✕ ' : ''}${t.operations.status[row.lastOperationStatus as keyof typeof t.operations.status] ?? row.lastOperationStatus}`}
          </StatusBadge>
        </span>
      ) : none),
    },
  ];

  return (
    <>
      <Topbar trail={[crumb(t.app.name, '/'), p.title]} />
      <PageHeader title={p.title} subtitle={p.subtitle} />
      {kpis && (
        <div className="mb-3 grid grid-cols-2 gap-3 xl:grid-cols-4">
          <KpiCard hero label={p.kpi.projects} value={formatNumber(kpis.projects, locale)} />
          <KpiCard label={p.kpi.changelogs} value={formatNumber(kpis.changelogs, locale)} />
          <KpiCard label={p.kpi.withCollector} value={p.kpi.of(kpis.withCollector, kpis.projects)} />
          <KpiCard label={p.kpi.underPolicy} value={p.kpi.of(kpis.underPolicy, kpis.projects)} />
        </div>
      )}
      {overview.isError ? (
        <ErrorCard message={p.loadError} onRetry={() => overview.refetch()} />
      ) : overview.data && rows.length === 0 ? (
        <EmptyState title={p.empty.title} hint={p.empty.hint} />
      ) : (
        <DataTable
          columns={columns}
          rows={rows}
          rowKey={(row) => row.id}
          loading={overview.isLoading}
          onRowClick={(row) => navigate(`/projects/${row.id}`)}
          rowLabel={(row) => row.name}
          caption={p.title}
        />
      )}
    </>
  );
}
