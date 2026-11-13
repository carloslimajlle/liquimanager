import { useEffect, useState } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';

import { useProjectList, useProjectSummary } from '../../api/admin';
import { useProjectChangesets, useProjectEnvironments } from '../../api/changesets';
import { useInReview } from '../../api/inReview';
import { useProjectApprovals } from '../../api/projectGovernance';
import type { ProjectChangeset } from '../../api/changesets';
import { useOperationKpis, useOperations } from '../../api/operations';
import type { OperationSummary } from '../../api/operations';
import CommandChip from '../../components/CommandChip';
import DataTable from '../../components/DataTable';
import type { Column } from '../../components/DataTable';
import EnvBadge from '../../components/EnvBadge';
import FilterSelect from '../../components/FilterSelect';
import KpiCard from '../../components/KpiCard';
import OriginBadge from '../../components/OriginBadge';
import PageHeader from '../../components/PageHeader';
import Pagination from '../../components/Pagination';
import StatusBadge from '../../components/StatusBadge';
import { EmptyState, ErrorCard } from '../../components/States';
import { fill } from '../../i18n';
import { useLocale, useMessages } from '../../i18n/useMessages';
import { formatDateTime, formatNumber, formatPercent } from '../../lib/format';
import Topbar from '../../shell/Topbar';
import { isFailure, statusTone, stateTone } from '../operations/rules';
import { ApprovalsTab, DriftTab, PipelinesTab, PoliciesTab } from './ProjectGovernance';
import GenerateSql from './GenerateSql';
import { ReviewTab } from './ProjectReview';
import { RollbackTab } from './ProjectRollback';

type Tab = 'summary' | 'changesets' | 'operations' | 'rollback' | 'review' | 'policies' | 'drift' | 'pipelines' | 'approvals';
const TABS: Tab[] = ['summary', 'changesets', 'operations', 'rollback', 'review', 'policies', 'drift', 'pipelines', 'approvals'];
const PAGE_SIZE = 25;

/**
 * /projects/:projectId: the application as a whole. What each environment holds, every changeset of the project with where it ran, and
 * its operations. Registration (changelogs, connections, pipelines) stays under Administer; this page links to it.
 */
export default function ProjectHub() {
  const t = useMessages();
  const p = t.projects;
  const { projectId } = useParams();
  const [params, setParams] = useSearchParams();
  const projects = useProjectList();
  const project = projects.data?.find((item) => item.id === projectId);
  const requested = params.get('tab');
  const tab: Tab = TABS.includes(requested as Tab) ? (requested as Tab) : 'summary';
  const choose = (next: Tab) => setParams(next === 'summary' ? {} : { tab: next }, { replace: true });
  const approvals = useProjectApprovals(project?.id);
  const open = approvals.data?.total ?? 0;
  const inReview = useInReview(project?.id, false).data?.openCount ?? 0;

  return (
    <>
      <Topbar trail={[t.app.name, p.title, project?.name ?? '…']} />
      <PageHeader title={project?.name ?? p.title} subtitle={project?.slug}
        right={(
          <>
            <Link to="/projects" className="text-[12px] font-semibold text-primary hover:underline">← {p.back}</Link>
            {project && <Link to={`/admin/projects/${project.id}`} className="text-[12px] font-semibold text-primary hover:underline">{p.register} ↗</Link>}
          </>
        )} />
      {projects.isError ? (
        <ErrorCard message={p.loadError} onRetry={() => projects.refetch()} />
      ) : projects.data && !project ? (
        <EmptyState title={p.notFound} />
      ) : project && (
        <>
          <div role="tablist" aria-label={project.name} className="mb-3 flex gap-1 border-b border-border">
            {TABS.map((name) => (
              <button key={name} type="button" role="tab" aria-selected={tab === name} onClick={() => choose(name)}
                className={`-mb-px border-b-2 px-3 py-2 text-[13px] font-semibold ${tab === name ? 'border-primary text-primary' : 'border-transparent text-text-2 hover:text-text'}`}>
                {p.tabs[name]}{name === 'approvals' && open > 0 ? ` (${open})` : ''}{name === 'review' && inReview > 0 ? ` (${inReview})` : ''}
              </button>
            ))}
          </div>
          {tab === 'summary' && <Summary projectId={project.id} slug={project.slug} onSeeOperations={() => choose('operations')} />}
          {tab === 'changesets' && <Changesets projectId={project.id} slug={project.slug} />}
          {tab === 'operations' && <Operations slug={project.slug} />}
          {tab === 'rollback' && <RollbackTab projectId={project.id} />}
          {tab === 'review' && <ReviewTab projectId={project.id} />}
          {tab === 'policies' && <PoliciesTab projectId={project.id} slug={project.slug} />}
          {tab === 'drift' && <DriftTab projectId={project.id} />}
          {tab === 'pipelines' && <PipelinesTab projectId={project.id} />}
          {tab === 'approvals' && <ApprovalsTab projectId={project.id} />}
        </>
      )}
    </>
  );
}

function Summary({ projectId, slug, onSeeOperations }: { projectId: string; slug: string; onSeeOperations: () => void }) {
  const t = useMessages();
  const s = t.projects.summary;
  const locale = useLocale();
  const summary = useProjectSummary(projectId);
  const environments = useProjectEnvironments(projectId);
  const changesets = useProjectChangesets(projectId, {}, 0, 1);
  const kpis = useOperationKpis({ project: slug, period: 'all' });
  const none = t.common.none;
  const rate = kpis.data?.successRate;
  const sum = summary.data;

  return (
    <>
      <div className="mb-3.5 grid grid-cols-4 gap-3.5">
        <KpiCard hero label={s.kpi.operations} value={kpis.data ? formatNumber(kpis.data.total, locale) : none} />
        <KpiCard label={s.kpi.successRate} value={rate == null ? none : formatPercent(rate, locale)}
          tone={rate == null ? 'default' : rate >= 95 ? 'success' : rate >= 80 ? 'warning' : 'danger'} />
        <KpiCard label={s.kpi.failed} value={sum ? formatNumber(sum.failed + sum.blocked, locale) : none}
          tone={sum && sum.failed + sum.blocked > 0 ? 'danger' : 'default'} />
        <KpiCard label={s.kpi.changesets} value={changesets.data ? formatNumber(changesets.data.totalElements, locale) : none} />
      </div>

      <section className="mb-3.5 rounded-card border border-border bg-surface p-4 shadow-card">
        <h2 className="text-[15px] font-bold">{s.environments}</h2>
        <p className="mb-3 text-text-2">{s.environmentsHint}</p>
        {environments.data && environments.data.length === 0 ? (
          <p className="text-text-2">{s.noEnvironments}</p>
        ) : (
          <div className="grid grid-cols-[repeat(auto-fit,minmax(200px,1fr))] gap-3">
            {(environments.data ?? []).map((env) => (
              <div key={env.environment} className="rounded-control border border-border p-3">
                <EnvBadge code={env.environment} />
                <div className="mt-2 text-[22px] font-bold">{formatNumber(env.executed, locale)} <span className="text-[12px] font-medium text-text-2">{s.executed}</span></div>
                <div className="text-[12px] text-text-2">
                  <span className={env.failed > 0 ? 'font-semibold text-danger' : ''}>{formatNumber(env.failed, locale)} {s.failed}</span>
                  {' · '}{formatNumber(env.rolledBack, locale)} {s.rolledBack}
                </div>
                <div className="mt-1 text-[12px] text-text-3">{s.lastDeploy}: {env.lastDeployedAt ? formatDateTime(env.lastDeployedAt, locale) : s.never}</div>
              </div>
            ))}
          </div>
        )}
      </section>

      <section>
        <div className="mb-2 flex items-center">
          <h2 className="text-[15px] font-bold">{s.recent}</h2>
          <button type="button" onClick={onSeeOperations} className="ml-auto text-[12px] font-semibold text-primary hover:underline">{s.viewAll} ↗</button>
        </div>
        <OperationsTable slug={slug} pageSize={5} />
      </section>
    </>
  );
}

/** Where each changeset of the project stands, one column per environment. */
function Changesets({ projectId, slug }: { projectId: string; slug: string }) {
  const t = useMessages();
  const c = t.projects.changesets;
  const locale = useLocale();
  const navigate = useNavigate();
  const [search, setSearch] = useState('');
  const [query, setQuery] = useState('');
  const [status, setStatus] = useState('');
  const [page, setPage] = useState(0);
  const [sqlOpen, setSqlOpen] = useState(false);
  useEffect(() => {
    const timer = setTimeout(() => { setQuery(search.trim()); setPage(0); }, 300);
    return () => clearTimeout(timer);
  }, [search]);

  const list = useProjectChangesets(projectId, { q: query, status }, page, PAGE_SIZE);
  const envs = list.data?.environments ?? [];

  const columns: Column<ProjectChangeset>[] = [
    {
      key: 'changeset', header: c.columns.changeset,
      cell: (row) => (
        <span>
          <span className="font-mono font-semibold">{row.changesetId}</span>
          {row.onlyInBranch && <> <OriginBadge origin={row.origin} /></>}
          {row.removedAt && <> <StatusBadge tone="warning">{c.removed}</StatusBadge></>}
          {row.isBreakFix && <> <StatusBadge tone="warning">{c.breakFix}</StatusBadge></>}
          {!row.hasRollback && <> <StatusBadge tone="neutral">{c.noRollback}</StatusBadge></>}
        </span>
      ),
    },
    { key: 'author', header: c.columns.author, cell: (row) => <span className="text-text-2">{row.author}</span> },
    {
      key: 'changelog', header: c.columns.changelog,
      cell: (row) => (
        <Link to={`/projects/${projectId}/changelogs/${row.changelogId}`} onClick={(event) => event.stopPropagation()} className="text-primary hover:underline">
          {row.changelog}
        </Link>
      ),
    },
    { key: 'release', header: c.columns.release, cell: (row) => (row.release ? <span className="font-mono">{row.release}</span> : <span className="text-text-3">{t.common.none}</span>) },
    ...envs.map((env): Column<ProjectChangeset> => ({
      key: `env-${env}`,
      header: env,
      cell: (row) => {
        const state = row.environments.find((item) => item.environment === env);
        return state
          ? <StatusBadge tone={stateTone(state.status)}>{t.projects.status[state.status as keyof typeof t.projects.status] ?? state.status}</StatusBadge>
          : <span className="text-text-3">{c.notIn}</span>;
      },
    })),
    { key: 'seen', header: c.columns.seen, cell: (row) => <span className="whitespace-nowrap text-text-2">{formatDateTime(row.firstSeenAt, locale)}</span> },
  ];

  const total = list.data?.totalElements ?? 0;
  const totalPages = Math.max(1, Math.ceil(total / PAGE_SIZE));

  return (
    <>
      <div className="mb-3 flex items-center gap-2">
        <input
          type="search" value={search} onChange={(event) => setSearch(event.target.value)} placeholder={c.search} aria-label={c.search}
          className="w-[320px] rounded-control border border-border bg-surface px-3 py-1.5 text-[13px]"
        />
        <FilterSelect label={c.status} value={status} onChange={(value) => { setStatus(value); setPage(0); }}
          options={[{ value: '', label: c.allStatuses }, ...(['EXECUTED', 'FAILED', 'ROLLED_BACK', 'PENDING'] as const).map((key) => ({ value: key, label: t.projects.status[key] }))]} />
        <span className="ml-auto text-[12px] text-text-2">{fill(c.meta, { shown: list.data?.content.length ?? 0, total: formatNumber(total, locale) })}</span>
        <button type="button" onClick={() => setSqlOpen(true)}
          className="rounded-control border border-border px-3 py-1.5 text-[12px] font-semibold hover:bg-surface-alt">{t.projects.sql.button}</button>
      </div>
      {sqlOpen && <GenerateSql projectId={projectId} project={slug} onClose={() => setSqlOpen(false)} />}
      {list.isError ? (
        <ErrorCard message={t.projects.loadError} onRetry={() => list.refetch()} />
      ) : list.data && list.data.content.length === 0 ? (
        <EmptyState title={c.empty.title} hint={c.empty.hint} />
      ) : (
        <DataTable
          columns={columns}
          rows={list.data?.content ?? []}
          rowKey={(row) => row.id}
          loading={list.isLoading}
          onRowClick={(row) => navigate(`/changesets/${row.id}`)}
          rowLabel={(row) => row.changesetId}
          caption={t.projects.tabs.changesets}
        />
      )}
      <div className="mt-3 flex justify-end">
        <Pagination page={page} totalPages={totalPages} onPage={setPage} />
      </div>
    </>
  );
}

function Operations({ slug }: { slug: string }) {
  const t = useMessages();
  return (
    <>
      <OperationsTable slug={slug} pageSize={PAGE_SIZE} paged />
      <div className="mt-3 text-right">
        <Link to={`/operations?project=${encodeURIComponent(slug)}`} className="text-[12px] font-semibold text-primary hover:underline">{t.projects.summary.viewAll} ↗</Link>
      </div>
    </>
  );
}

function OperationsTable({ slug, pageSize, paged = false }: { slug: string; pageSize: number; paged?: boolean }) {
  const t = useMessages();
  const locale = useLocale();
  const navigate = useNavigate();
  const [page, setPage] = useState(0);
  const operations = useOperations({ project: slug, period: 'all' }, page, pageSize, 'startedAt,desc');
  const total = operations.data?.totalElements ?? 0;

  const columns: Column<OperationSummary>[] = [
    { key: 'command', header: t.operations.columns.command, cell: (row) => <CommandChip command={row.command} /> },
    {
      key: 'status', header: t.operations.columns.status,
      cell: (row) => <StatusBadge tone={statusTone(row.status)}>{`${isFailure(row.status) ? '✕ ' : ''}${t.operations.status[row.status]}`}</StatusBadge>,
    },
    { key: 'date', header: t.operations.columns.date, cell: (row) => <span className="whitespace-nowrap">{formatDateTime(row.startedAt, locale)}</span> },
    {
      key: 'changeset', header: t.operations.columns.changeset,
      cell: (row) => (row.changesetId
        ? (
          <Link to={`/changesets/${row.changesetId}`} onClick={(event) => event.stopPropagation()} className="font-mono text-primary hover:underline">
            {row.changeset}{(row.changesetCount ?? 0) > 1 ? ` ${fill(t.dashboard.activity.more, { n: (row.changesetCount ?? 1) - 1 })}` : ''}
          </Link>
        )
        : <span className="text-text-3">{t.common.none}</span>),
    },
    { key: 'database', header: t.operations.columns.database, cell: (row) => <span className="inline-flex items-center gap-1.5">{row.connection}<EnvBadge code={row.environment} /></span> },
    { key: 'executor', header: t.operations.columns.executor, cell: (row) => <span className="text-text-2">{row.executedBy}</span> },
  ];

  if (operations.data && total === 0) {
    return <EmptyState title={t.projects.operations.empty.title} hint={t.projects.operations.empty.hint} />;
  }
  return (
    <>
      <DataTable columns={columns} rows={operations.data?.content ?? []} rowKey={(row) => row.id} loading={operations.isLoading}
        skeletonRows={Math.min(pageSize, 5)} onRowClick={(row) => navigate(`/operations/${row.id}`)}
        rowLabel={(row) => row.id.slice(0, 8)} caption={t.projects.tabs.operations} />
      {paged && (
        <div className="mt-3 flex justify-end">
          <Pagination page={page} totalPages={Math.max(1, Math.ceil(total / pageSize))} onPage={setPage} />
        </div>
      )}
    </>
  );
}
