import { useEffect, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';

import { useProjects } from '../../api/catalog';
import { ACTIVITY_LIMIT, useActivity, useDashboard } from '../../api/dashboard';
import type { ActivityRow, DashboardPeriod } from '../../api/dashboard';
import { useOperationStream } from '../../api/useOperationStream';
import CommandChip from '../../components/CommandChip';
import DataTable from '../../components/DataTable';
import type { Column } from '../../components/DataTable';
import EnvBadge from '../../components/EnvBadge';
import { ToChangeset, ToPage } from '../../components/EntityLinks';
import FilterSelect from '../../components/FilterSelect';
import KpiCard from '../../components/KpiCard';
import PageHeader from '../../components/PageHeader';
import StatusBadge from '../../components/StatusBadge';
import { EmptyState, ErrorCard } from '../../components/States';
import { fill, intlLocale } from '../../i18n';
import { useLocale, useMessages } from '../../i18n/useMessages';
import { formatDecimal, formatNumber, formatPercent } from '../../lib/format';
import Topbar from '../../shell/Topbar';
import { crumb } from '../../shell/crumbs';
import { isFailure, statusTone } from '../operations/rules';
import { newRowIds, signed, timeOfDay, trendHigherIsBetter, trendLowerIsBetter } from './rules';

const PERIODS: DashboardPeriod[] = ['7d', '30d', '90d'];

/** Screen 01: delivery indicators and the live feed of what just happened. */
export default function Dashboard() {
  const t = useMessages();
  const locale = useLocale();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [params, setParams] = useSearchParams();

  const requested = params.get('period');
  const period: DashboardPeriod = PERIODS.includes(requested as DashboardPeriod) ? (requested as DashboardPeriod) : '7d';

  const dashboard = useDashboard(period);
  const projectId = params.get('project') ?? undefined;
  const projects = useProjects();
  const activity = useActivity(projectId);
  const chooseProject = (value: string) => {
    const next = new URLSearchParams(params);
    if (value) {
      next.set('project', value);
    } else {
      next.delete('project');
    }
    setParams(next, { replace: true });
  };

  // A new operation arrives over SSE: refresh both the indicators and the table. The
  // numbers move too (a deploy changes frequency and failure rate), not only the list.
  const live = useOperationStream(() => {
    queryClient.invalidateQueries({ queryKey: ['dashboard'] });
  });

  // Rows not on screen before get a one-second fade. The very first load animates
  // nothing: a page that opens with every row "arriving" would be noise.
  const seen = useRef<Set<string>>(new Set());
  const [fresh, setFresh] = useState<Set<string>>(new Set());
  useEffect(() => {
    const rows = activity.data;
    if (!rows) {
      return;
    }
    const arrived = seen.current.size === 0 ? new Set<string>() : newRowIds(seen.current, rows);
    rows.forEach((row) => seen.current.add(row.id));
    setFresh(arrived);
    if (arrived.size === 0) {
      return;
    }
    const timer = setTimeout(() => setFresh(new Set()), 1200);
    return () => clearTimeout(timer);
  }, [activity.data]);

  const d = dashboard.data;
  const time = (iso: string) => timeOfDay(iso, intlLocale(locale));

  const columns: Column<ActivityRow>[] = [
    {
      key: 'time', header: t.dashboard.activity.columns.time,
      cell: (row) => <span className="text-text-3">{time(row.startedAt)}</span>,
    },
    {
      key: 'team', header: t.dashboard.activity.columns.team,
      cell: (row) => (row.team ? <b>{row.team}</b> : <span className="text-text-3">{t.common.none}</span>),
    },
    {
      key: 'project', header: t.dashboard.activity.columns.project,
      cell: (row) => (row.project && row.projectId
        ? <ToPage to={`/projects/${row.projectId}`}>{row.project}</ToPage>
        : <span className="text-text-3">{t.common.none}</span>),
    },
    {
      key: 'subject', header: t.dashboard.activity.columns.changeset,
      cell: (row) => {
        if (!row.subject) {
          return <span className="text-text-3">{t.common.none}</span>;
        }
        // One changeset links to it; a package (several) shows its name and leads to the operation, which lists them.
        return row.changesetId && row.changesetCount === 1
          ? <ToChangeset id={row.changesetId} name={row.subject} more={0} />
          : <span className="font-mono">{row.subject}</span>;
      },
    },
    {
      key: 'connection', header: t.dashboard.activity.columns.database,
      cell: (row) => <span className="font-mono">{row.connection}</span>,
    },
    { key: 'environment', header: t.dashboard.activity.columns.environment, cell: (row) => <EnvBadge code={row.environment} /> },
    { key: 'command', header: t.dashboard.activity.columns.operation, cell: (row) => <CommandChip command={row.command} /> },
    {
      key: 'scope', header: t.dashboard.activity.columns.scope,
      cell: (row) => {
        if (row.scope == null) {
          return <span className="text-text-3">{t.common.none}</span>;
        }
        const template = row.scope === 1 ? t.dashboard.activity.scopeOne : t.dashboard.activity.scopeMany;
        return <span>{fill(template, { n: formatNumber(row.scope, locale) })}</span>;
      },
    },
    {
      key: 'status', header: t.dashboard.activity.columns.result,
      cell: (row) => (
        <StatusBadge tone={statusTone(row.status)}>
          {`${isFailure(row.status) ? '✕ ' : ''}${(t.operations.status as Record<string, string>)[row.status] ?? row.status}`}
        </StatusBadge>
      ),
    },
  ];

  const percentChange = (value: number) => `${signed(value, (n) => formatDecimal(n, 1, locale))}%`;
  const pointsChange = (value: number) =>
    fill(t.dashboard.kpi.points, { value: signed(value, (n) => formatDecimal(n, 1, locale)) });

  const dora = d?.dora;
  const policies = d?.policies;
  const drift = d?.drift;
  const absoluteChange = (value: number) => signed(value, (n) => formatNumber(n, locale));
  const dash = t.common.none;

  return (
    <>
      <Topbar trail={[crumb(t.dashboard.breadcrumbRoot, '/'), t.dashboard.title]} />
      <PageHeader
        title={t.dashboard.title}
        subtitle={t.dashboard.subtitle}
        right={
          <>
            <FilterSelect
              label={t.dashboard.period}
              value={period}
              options={PERIODS.map((value) => ({ value, label: t.dashboard.periods[value] }))}
              onChange={(value) => setParams(value === '7d' ? {} : { period: value })}
            />
            {d && (
              <dl className="ml-3 flex gap-5 text-right">
                {([
                  ['teams', d.header.teams],
                  ['connections', d.header.connections],
                  ['pipelines', d.header.pipelines],
                  ['operations', d.header.operations],
                ] as const).map(([key, value]) => (
                  <div key={key}>
                    <dt className="text-[12px] text-text-2">{t.dashboard.stats[key]}</dt>
                    <dd className="text-[16px] font-bold">{formatNumber(value, locale)}</dd>
                  </div>
                ))}
              </dl>
            )}
          </>
        }
      />

      <Band
        title={t.dashboard.bands.changes.title}
        subtitle={t.dashboard.bands.changes.subtitle}
        link={<Link to="/operations" className="font-semibold text-primary hover:underline">{t.dashboard.bands.changes.link} ↗</Link>}
      >
        {dashboard.isError ? (
          <Card><ErrorCard message={t.dashboard.bands.error} onRetry={() => dashboard.refetch()} /></Card>
        ) : (
          <div className="grid grid-cols-2 gap-3 xl:grid-cols-4">
            <KpiCard
              hero
              label={t.dashboard.kpi.deployFrequency}
              value={dora ? formatDecimal(dora.deployFrequency.perDay, 2, locale) : dash}
              unit={dora ? t.dashboard.kpi.perDay : undefined}
              sub={dora ? fill(t.dashboard.kpi.deploysSub, { n: formatNumber(dora.deployFrequency.deploys, locale) }) : undefined}
              trend={trendHigherIsBetter(dora?.deployFrequency.trendPct, percentChange)}
            />
            <KpiCard
              label={t.dashboard.kpi.failureRate}
              value={dora?.changeFailureRate.value != null ? formatPercent(dora.changeFailureRate.value, locale) : dash}
              sub={dora ? fill(t.dashboard.kpi.failureSub, {
                part: formatNumber(dora.changeFailureRate.part, locale),
                whole: formatNumber(dora.changeFailureRate.whole, locale),
              }) : undefined}
              trend={trendLowerIsBetter(dora?.changeFailureRate.trendPts, pointsChange)}
            />
            <KpiCard
              label={t.dashboard.kpi.rollbackRate}
              value={dora?.rollbackRate.value != null ? formatPercent(dora.rollbackRate.value, locale) : dash}
              sub={dora ? fill(t.dashboard.kpi.rollbackSub, {
                part: formatNumber(dora.rollbackRate.part, locale),
                whole: formatNumber(dora.rollbackRate.whole, locale),
              }) : undefined}
              trend={trendLowerIsBetter(dora?.rollbackRate.trendPts, pointsChange)}
            />
            <KpiCard
              label={t.dashboard.kpi.cycleTime}
              value={dora?.cycleTime.hours != null ? formatDecimal(dora.cycleTime.hours, 1, locale) : dash}
              unit={dora?.cycleTime.hours != null ? t.dashboard.kpi.hours : undefined}
              sub={t.dashboard.kpi.cycleSub}
              trend={trendLowerIsBetter(dora?.cycleTime.trendPct, percentChange)}
            />
          </div>
        )}
      </Band>

      {d?.restricted ? (
        <p className="mb-4 text-[12.5px] text-text-2">{t.dashboard.restricted}</p>
      ) : (
        <>
        <Band
          title={t.dashboard.bands.policies.title}
          subtitle={t.dashboard.bands.policies.subtitle}
          link={<Link to="/govern/enforcement" className="font-semibold text-primary hover:underline">{t.dashboard.bands.changes.link} ↗</Link>}
        >
          <div className="grid grid-cols-2 gap-3 xl:grid-cols-4">
            <KpiCard
              hero
              label={t.dashboard.kpi.passRate}
              value={policies?.passRate.value != null ? formatPercent(policies.passRate.value, locale) : dash}
              sub={policies ? fill(t.dashboard.kpi.passSub, {
                part: formatNumber(policies.passRate.part, locale), whole: formatNumber(policies.passRate.whole, locale),
              }) : undefined}
              trend={trendHigherIsBetter(policies?.passRate.trendPts, pointsChange)}
            />
            <KpiCard
              label={t.dashboard.kpi.blockedProd}
              value={policies ? formatNumber(policies.blocked.count, locale) : dash}
              tone="danger"
              sub={t.dashboard.kpi.blockedSub}
              trend={trendLowerIsBetter(policies?.blocked.trendAbs, absoluteChange)}
            />
            <KpiCard
              label={t.dashboard.kpi.dbCoverage}
              value={policies ? fill(t.dashboard.kpi.ofTotal, { covered: policies.databaseCoverage.covered, total: policies.databaseCoverage.total }) : dash}
              sub={t.dashboard.kpi.dbCoverageSub}
            />
            <KpiCard
              label={t.dashboard.kpi.clCoverage}
              value={policies ? fill(t.dashboard.kpi.ofTotal, { covered: policies.changelogCoverage.covered, total: policies.changelogCoverage.total }) : dash}
              sub={t.dashboard.kpi.clCoverageSub}
            />
          </div>
        </Band>
        <Band
          title={t.dashboard.bands.drift.title}
          subtitle={t.dashboard.bands.drift.subtitle}
          link={<Link to="/drift" className="font-semibold text-primary hover:underline">{t.dashboard.bands.changes.link} ↗</Link>}
        >
          <div className="grid grid-cols-2 gap-3 xl:grid-cols-4">
            <KpiCard
              hero
              label={t.dashboard.kpi.driftRate}
              value={drift?.driftRate.value != null ? formatPercent(drift.driftRate.value, locale) : dash}
              sub={drift ? fill(t.dashboard.kpi.driftRateSub, {
                part: formatNumber(drift.driftRate.part, locale), whole: formatNumber(drift.driftRate.whole, locale),
              }) : undefined}
              trend={trendLowerIsBetter(drift?.driftRate.trendPts, pointsChange)}
            />
            <KpiCard
              label={t.dashboard.kpi.driftDetected}
              value={drift ? formatNumber(drift.detected.count, locale) : dash}
              tone="warning"
              sub={t.dashboard.kpi.driftDetectedSub}
              trend={trendLowerIsBetter(drift?.detected.trendAbs, absoluteChange)}
            />
            <KpiCard
              label={t.dashboard.kpi.driftItems}
              value={drift ? formatNumber(drift.items.total, locale) : dash}
              tone="warning"
              sub={drift ? fill(t.dashboard.kpi.driftItemsSub, {
                added: drift.items.added, removed: drift.items.removed, modified: drift.items.modified,
              }) : undefined}
              trend={trendLowerIsBetter(drift?.items.trendAbs, absoluteChange)}
            />
            <KpiCard
              label={t.dashboard.kpi.monitoring}
              value={drift ? fill(t.dashboard.kpi.ofTotal, { covered: drift.monitoring.monitored, total: drift.monitoring.total }) : dash}
              sub={t.dashboard.kpi.monitoringSub}
            />
          </div>
        </Band>
        </>
      )}

      <Card className="mb-6">
        <div className="flex items-center px-3.5 pt-3 pb-1 font-bold">
          {t.dashboard.activity.title}
          <label className="ml-3 font-normal">
            <span className="sr-only">{t.dashboard.activity.projectFilter}</span>
            <select value={projectId ?? ''} onChange={(event) => chooseProject(event.target.value)}
              className="rounded-control border border-border bg-surface px-2 py-1 text-[12px] font-semibold">
              <option value="">{t.dashboard.activity.allProjects}</option>
              {(projects.data ?? []).map((project) => <option key={project.id} value={project.id}>{project.name}</option>)}
            </select>
          </label>
          <span className="ml-auto text-[12px] font-normal text-text-3">
            {activity.data && !projectId
              ? fill(t.dashboard.activity.meta, {
                  shown: formatNumber(activity.data.length, locale),
                  total: d ? formatNumber(d.header.operations, locale) : dash,
                })
              : ''}
            {' · '}
            <span className={live ? 'text-success' : 'text-text-3'}>
              {live ? t.dashboard.activity.live : t.dashboard.activity.offline}
            </span>
          </span>
        </div>
        {activity.isError ? (
          <ErrorCard message={t.dashboard.activity.error} onRetry={() => activity.refetch()} />
        ) : activity.data && activity.data.length === 0 ? (
          <EmptyState title={t.dashboard.activity.empty.title} hint={t.dashboard.activity.empty.hint} />
        ) : (
          <DataTable
            columns={columns}
            rows={activity.data ?? []}
            rowKey={(row) => row.id}
            loading={activity.isLoading}
            skeletonRows={ACTIVITY_LIMIT}
            onRowClick={(row) => navigate(`/operations/${row.id}`)}
            rowLabel={(row) => fill(t.dashboard.activity.rowLabel, { id: row.id.slice(0, 8) })}
            rowClass={(row) => [
              isFailure(row.status) ? 'bg-row-danger' : '',
              fresh.has(row.id) ? 'row-enter' : '',
            ].join(' ')}
            caption={t.dashboard.activity.title}
          />
        )}
      </Card>
    </>
  );
}

function Card({ children, className = '' }: { children: ReactNode; className?: string }) {
  return <div className={`overflow-hidden rounded-card border border-border bg-surface shadow-card ${className}`}>{children}</div>;
}

/** A titled strip of KPIs (docs/telas/01: each band loads and fails on its own). */
function Band({ title, subtitle, link, children }: { title: string; subtitle: string; link?: ReactNode; children: ReactNode }) {
  return (
    <section className="mb-3.5">
      <div className="mb-2 flex items-center gap-2.5 text-[13px] font-bold">
        {title}
        <span className="font-normal text-text-2">· {subtitle}</span>
        {link && <span className="ml-auto text-[12px]">{link}</span>}
      </div>
      {children}
    </section>
  );
}
