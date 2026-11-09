import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';

import { useOperationKpis, useOperations } from '../../api/operations';
import type { OperationSummary } from '../../api/operations';
import { useEnvironments } from '../../api/catalog';
import CommandChip from '../../components/CommandChip';
import DataTable from '../../components/DataTable';
import type { Column } from '../../components/DataTable';
import EnvBadge from '../../components/EnvBadge';
import { ToChangeset, ToPage } from '../../components/EntityLinks';
import FilterSelect from '../../components/FilterSelect';
import KpiCard from '../../components/KpiCard';
import PageHeader from '../../components/PageHeader';
import Pagination from '../../components/Pagination';
import StatusBadge from '../../components/StatusBadge';
import { EmptyState, ErrorCard } from '../../components/States';
import { fill } from '../../i18n';
import { useLocale, useMessages } from '../../i18n/useMessages';
import { formatDateTime, formatDurationParts, formatNumber, formatPercent } from '../../lib/format';
import Topbar from '../../shell/Topbar';
import { executorLabel, isFailure, pageRange, statusTone } from './rules';
import { PAGE_SIZE, useOperationFilters } from './useOperationFilters';

/** Screen 05: every operation of every project, in one audit trail. */
export default function AllOperations() {
  const t = useMessages();
  const locale = useLocale();
  const navigate = useNavigate();
  const { filters, page, sort, setFilter, setPage, toggleSort, clear, active } = useOperationFilters();

  const operations = useOperations(filters, page, PAGE_SIZE, sort);
  const kpis = useOperationKpis(filters);
  const environments = useEnvironments();

  // The search box edits local state at once and the URL after a pause, so typing never
  // fires a request per keystroke. The URL stays the source of truth: if it changes
  // from outside (clear filters, back button), the box follows.
  const [search, setSearch] = useState(filters.q ?? '');
  useEffect(() => setSearch(filters.q ?? ''), [filters.q]);
  useEffect(() => {
    if (search === (filters.q ?? '')) {
      return;
    }
    const timer = setTimeout(() => setFilter('q', search.trim()), 300);
    return () => clearTimeout(timer);
  }, [search, filters.q, setFilter]);

  const link = (key: string, value: string | null | undefined) =>
    value ? (
      <button
        type="button"
        onClick={(event) => {
          event.stopPropagation();
          setFilter(key, value);
        }}
        className="text-primary hover:underline"
      >
        {value}
      </button>
    ) : (
      <span className="text-text-3">{t.common.none}</span>
    );

  const columns: Column<OperationSummary>[] = [
    {
      key: 'command', header: t.operations.columns.command, sortKey: 'command',
      cell: (row) => <CommandChip command={row.command} />,
    },
    {
      key: 'status', header: t.operations.columns.status, sortKey: 'status',
      cell: (row) => (
        <StatusBadge tone={statusTone(row.status)}>
          {`${isFailure(row.status) ? '✕ ' : ''}${t.operations.status[row.status]}`}
        </StatusBadge>
      ),
    },
    {
      key: 'date', header: t.operations.columns.date, sortKey: 'startedAt',
      cell: (row) => <span className="whitespace-nowrap">{formatDateTime(row.startedAt, locale)}</span>,
    },
    {
      key: 'type', header: t.operations.columns.type, sortKey: 'type',
      cell: (row) => <span className="font-mono text-text-2">{row.type}</span>,
    },
    {
      key: 'duration', header: t.operations.columns.duration, sortKey: 'durationMs',
      cell: (row) =>
        row.durationMs == null ? <span className="text-text-3">{t.common.none}</span>
          : <span>{formatNumber(row.durationMs, locale)} ms</span>,
    },
    {
      key: 'database', header: t.operations.columns.database, sortKey: 'connection',
      cell: (row) => (
        <span className="inline-flex items-center gap-1.5">
          {link('connection', row.connection)}
          <EnvBadge code={row.environment} />
        </span>
      ),
    },
    {
      key: 'changeset', header: t.operations.columns.changeset,
      cell: (row) => (row.changesetId
        ? <ToChangeset id={row.changesetId} name={row.changeset} more={(row.changesetCount ?? 1) - 1} />
        : <span className="text-text-3">{t.common.none}</span>),
    },
    {
      key: 'changelog', header: t.operations.columns.changelog,
      cell: (row) => (row.changelog && row.projectId && row.changelogId
        ? <ToPage to={`/projects/${row.projectId}/changelogs/${row.changelogId}`}>{row.changelog}</ToPage>
        : <span className="text-text-3">{t.common.none}</span>),
    },
    {
      key: 'project', header: t.operations.columns.project, sortKey: 'project',
      cell: (row) => (row.project && row.projectId
        ? <ToPage to={`/projects/${row.projectId}`}>{row.project}</ToPage>
        : <span className="text-text-3">{t.common.none}</span>),
    },
    {
      key: 'executor', header: t.operations.columns.executor, sortKey: 'executedBy',
      cell: (row) => <span className="text-text-2">{executorLabel(row, t)}</span>,
    },
  ];

  const data = operations.data;
  const total = data?.totalElements ?? 0;
  const shown = data?.content.length ?? 0;
  const range = pageRange(page, PAGE_SIZE, total, shown);
  const totalPages = Math.ceil(total / PAGE_SIZE);

  const statusOptions = [
    { value: '', label: t.operations.filters.status },
    ...(['SUCCESS', 'FAILURE', 'BLOCKED', 'RUNNING', 'CANCELED'] as const).map((value) => ({
      value, label: t.operations.status[value],
    })),
  ];
  const typeOptions = [
    { value: '', label: t.operations.filters.type },
    ...['database-change', 'policy-checks', 'read-only'].map((value) => ({ value, label: value })),
  ];
  const envOptions = [
    { value: '', label: t.operations.filters.env },
    ...(environments.data ?? []).map((env) => ({ value: env.code, label: env.code })),
  ];
  const periodOptions = (['7d', '30d', '90d', 'all'] as const).map((value) => ({
    value, label: t.operations.periods[value],
  }));

  const k = kpis.data;
  const trend = k && k.trendPct != null
    ? {
        value: `${k.trendPct >= 0 ? '+' : ''}${formatNumber(k.trendPct, locale)}%`,
        direction: k.trendPct >= 0 ? ('up' as const) : ('down' as const),
        good: true,
      }
    : undefined;
  const median = k && k.medianDurationMs != null ? formatDurationParts(k.medianDurationMs, locale) : null;

  return (
    <>
      <Topbar trail={[t.operations.breadcrumbRoot, t.operations.breadcrumbPage]} />
      <PageHeader
        title={t.operations.title}
        subtitle={t.operations.subtitle}
        right={
          <>
            <input
              type="search"
              value={search}
              onChange={(event) => setSearch(event.target.value)}
              placeholder={t.operations.searchPlaceholder}
              aria-label={t.operations.searchLabel}
              className="w-[180px] rounded-control border border-border bg-surface px-3 py-1.5 text-[12px] shadow-card placeholder:text-text-3"
            />
            <FilterSelect
              label={t.operations.filters.status}
              value={filters.status ?? ''}
              options={statusOptions}
              onChange={(value) => setFilter('status', value)}
            />
            <FilterSelect
              label={t.operations.filters.type}
              value={filters.type ?? ''}
              options={typeOptions}
              onChange={(value) => setFilter('type', value)}
            />
            <FilterSelect
              label={t.operations.filters.env}
              value={filters.env ?? ''}
              options={envOptions}
              onChange={(value) => setFilter('env', value)}
            />
            <FilterSelect
              label={t.operations.filters.period}
              value={filters.period ?? '7d'}
              options={periodOptions}
              onChange={(value) => setFilter('period', value)}
            />
            {active && (
              <button type="button" onClick={clear} className="text-[12px] font-semibold text-primary hover:underline">
                {t.operations.filters.clear}
              </button>
            )}
          </>
        }
      />

      <div className="mb-3.5 grid grid-cols-2 gap-3 xl:grid-cols-4">
        <KpiCard
          hero
          label={
            filters.period === '7d'
              ? t.operations.kpi.operationsThisWeek
              : fill(t.operations.kpi.operations, { period: t.operations.periods[filters.period ?? '7d'].toLowerCase() })
          }
          value={k ? formatNumber(k.total, locale) : t.common.none}
          trend={trend}
        />
        <KpiCard
          label={t.operations.kpi.successRate}
          tone="success"
          value={k && k.successRate != null ? formatPercent(k.successRate, locale) : t.common.none}
          sub={k ? fill(t.operations.kpi.successSub, { n: formatNumber(k.succeeded, locale) }) : undefined}
        />
        <KpiCard
          label={t.operations.kpi.medianDuration}
          value={median ? median.value : t.common.none}
          unit={median ? median.unit : undefined}
          sub={t.operations.kpi.perOperation}
        />
        <KpiCard
          label={t.operations.kpi.caught}
          tone="warning"
          value={k ? formatNumber(k.caughtBeforeProd, locale) : t.common.none}
          sub={k ? fill(t.operations.kpi.caughtSub, {
            failures: formatNumber(k.failuresNonProd, locale),
            blocks: formatNumber(k.caughtBeforeProd - k.failuresNonProd, locale),
          }) : undefined}
        />
      </div>

      <div className="overflow-hidden rounded-card border border-border bg-surface shadow-card">
        {operations.isError ? (
          <ErrorCard message={t.operations.error} onRetry={() => operations.refetch()} />
        ) : !operations.isLoading && total === 0 ? (
          <EmptyState title={t.operations.empty.title} hint={t.operations.empty.hint} />
        ) : (
          <DataTable
            columns={columns}
            rows={data?.content ?? []}
            rowKey={(row) => row.id}
            loading={operations.isLoading}
            sort={sort}
            onSort={toggleSort}
            onRowClick={(row) => navigate(`/operations/${row.id}`)}
            rowLabel={(row) => fill(t.operations.rowLabel, { id: row.id.slice(0, 8) })}
            rowClass={(row) => (isFailure(row.status) ? 'bg-row-danger' : undefined)}
            caption={t.operations.title}
          />
        )}
        <div className="flex items-center border-t border-border px-3.5 py-2.5 text-[12px] text-text-2">
          <span>
            {total === 0
              ? t.operations.footerEmpty
              : fill(t.operations.footer, {
                  from: formatNumber(range.from, locale),
                  to: formatNumber(range.to, locale),
                  total: formatNumber(total, locale),
                })}
          </span>
          <div className="ml-auto">
            <Pagination page={page} totalPages={totalPages} onPage={setPage} />
          </div>
        </div>
      </div>
    </>
  );
}
