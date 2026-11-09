import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';

import { useAdminActions, useConnectionList, usePipelineList, useProjectConnections, useProjectList, useProjectSummary, useTeams } from '../../api/admin';
import type { Project, ProjectConnection } from '../../api/admin';
import { useCan } from '../../auth/useCan';
import DataTable from '../../components/DataTable';
import type { Column } from '../../components/DataTable';
import EnvBadge from '../../components/EnvBadge';
import { messageOf, primaryButton, secondaryButton } from '../../components/FormDialog';
import KpiCard from '../../components/KpiCard';
import PageHeader from '../../components/PageHeader';
import { EmptyState, ErrorCard } from '../../components/States';
import { fill } from '../../i18n';
import { useLocale, useMessages } from '../../i18n/useMessages';
import { formatDateTime, formatNumber, formatPercent } from '../../lib/format';
import AdminTabs from '../../shell/AdminTabs';
import Topbar from '../../shell/Topbar';
import { ChangelogsPanel } from './Projects';
import { ActivityTab, CollectorTab } from './ProjectCollector';
import { toggle } from './rules';

type Tab = 'overview' | 'changelogs' | 'connections' | 'pipelines' | 'collector' | 'activity';
const TABS: Tab[] = ['overview', 'changelogs', 'connections', 'pipelines', 'collector', 'activity'];

/** /admin/projects/:projectId: the project's numbers on top, and below them its changelogs, connections and pipelines. */
export default function ProjectDetail() {
  const t = useMessages();
  const a = t.admin;
  const d = a.projects.detail;
  const locale = useLocale();
  const { projectId } = useParams();
  const projects = useProjectList();
  const summary = useProjectSummary(projectId);
  const [tab, setTab] = useState<Tab>('overview');
  const project = projects.data?.find((item) => item.id === projectId);
  const s = summary.data;
  const none = t.common.none;

  return (
    <>
      <Topbar trail={[a.breadcrumbRoot, a.projects.title, project?.name ?? '…']} />
      <PageHeader title={project?.name ?? a.projects.title} subtitle={project ? project.slug : undefined}
        right={<Link to="/admin/projects" className="text-[12px] font-semibold text-primary hover:underline">← {d.back}</Link>} />
      <AdminTabs />
      {projects.isError ? (
        <ErrorCard message={a.common.loadError} onRetry={() => projects.refetch()} />
      ) : projects.data && !project ? (
        <EmptyState title={d.notFound} />
      ) : (
        <>
          <div className="mb-3.5 grid grid-cols-4 gap-3.5">
            <KpiCard hero label={d.kpi.operations} value={s ? formatNumber(s.finished, locale) : none} sub={d.kpi.operationsSub} />
            <KpiCard label={d.kpi.successRate}
              value={s?.successRate == null ? none : formatPercent(s.successRate, locale)}
              tone={s?.successRate == null ? 'default' : s.successRate >= 95 ? 'success' : s.successRate >= 80 ? 'warning' : 'danger'}
              sub={s ? (s.successRate == null ? d.kpi.noOperations : fill(d.kpi.successRateSub, { failed: s.failed, blocked: s.blocked })) : undefined} />
            <KpiCard label={d.kpi.connections} value={s ? formatNumber(s.connections, locale) : none} sub={d.kpi.connectionsSub} />
            <KpiCard label={d.kpi.openDrift} value={s ? formatNumber(s.openDrift, locale) : none}
              tone={s && s.openDrift > 0 ? 'warning' : 'default'} sub={d.kpi.openDriftSub} />
          </div>

          <div role="tablist" aria-label={project?.name} className="mb-3 flex gap-1 border-b border-border">
            {TABS.map((name) => (
              <button key={name} type="button" role="tab" aria-selected={tab === name} onClick={() => setTab(name)}
                className={`-mb-px border-b-2 px-3 py-2 text-[13px] font-semibold ${tab === name ? 'border-primary text-primary' : 'border-transparent text-text-2 hover:text-text'}`}>
                {d.tabs[name]}
              </button>
            ))}
          </div>

          {project && tab === 'overview' && <Overview project={project} lastAt={s?.lastAt ?? null} changelogs={s?.changelogs ?? 0} pipelines={s?.pipelines ?? 0} />}
          {project && tab === 'changelogs' && <ChangelogsPanel project={project} />}
          {project && tab === 'connections' && <ConnectionsTab project={project} />}
          {project && tab === 'pipelines' && <PipelinesTab project={project} />}
          {project && tab === 'collector' && <CollectorTab project={project} />}
          {project && tab === 'activity' && <ActivityTab project={project} />}
        </>
      )}
    </>
  );
}

function Overview({ project, lastAt, changelogs, pipelines }: { project: Project; lastAt: string | null; changelogs: number; pipelines: number }) {
  const t = useMessages();
  const o = t.admin.projects.detail.overview;
  const locale = useLocale();
  const teams = useTeams();
  const term = 'text-[12px] font-semibold text-text-2';
  return (
    <section className="rounded-card border border-border bg-surface p-4 shadow-card">
      <dl className="grid grid-cols-[160px_1fr] gap-y-2 text-[13px]">
        <dt className={term}>{o.team}</dt>
        <dd>{(teams.data ?? []).find((team) => team.id === project.teamId)?.name ?? t.admin.projects.noTeam}</dd>
        <dt className={term}>{o.repo}</dt>
        <dd>{project.repoUrl ? <code className="font-mono text-[12px]">{project.repoUrl}</code> : t.admin.common.none}{' '}
          <code className="font-mono text-[12px] text-text-3">{project.defaultBranch}</code></dd>
        <dt className={term}>{o.description}</dt><dd>{project.description || t.admin.common.none}</dd>
        <dt className={term}>{o.lastOperation}</dt><dd>{lastAt ? formatDateTime(lastAt, locale) : o.never}</dd>
        <dt className={term}>{o.changelogs}</dt><dd>{changelogs}</dd>
        <dt className={term}>{o.pipelines}</dt><dd>{pipelines}</dd>
      </dl>
    </section>
  );
}

/** Which connections the project applies to: a checklist of every connection, saved as a set (one audited write). */
function ConnectionsTab({ project }: { project: Project }) {
  const t = useMessages();
  const a = t.admin;
  const c = a.projects.detail.connections;
  const linked = useProjectConnections(project.id);
  const all = useConnectionList();
  const actions = useAdminActions();
  const editable = useCan()('catalog:edit');
  const [editing, setEditing] = useState(false);
  const [chosen, setChosen] = useState<string[]>([]);
  const [message, setMessage] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const columns: Column<ProjectConnection>[] = [
    { key: 'connection', header: c.columns.connection, cell: (r) => (
      <span><span className="font-semibold">{r.displayName}</span> <code className="font-mono text-[12px] text-text-3">{r.slug}</code></span>
    ) },
    { key: 'env', header: c.columns.environment, cell: (r) => <EnvBadge code={r.environmentCode} /> },
    { key: 'engine', header: c.columns.engine, cell: (r) => r.engine },
  ];

  function start() {
    setChosen((linked.data ?? []).map((item) => item.id));
    setMessage(null);
    setEditing(true);
  }

  async function save() {
    setBusy(true);
    try {
      await actions.linkConnections(project.id, chosen);
      setEditing(false);
      setMessage(c.saved);
    } catch (err) {
      setMessage(messageOf(err, a.common.saveError));
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="rounded-card border border-border bg-surface shadow-card">
      <div className="flex items-center gap-2 px-4 pt-3">
        <h2 className="text-[14px] font-semibold">{fill(c.title, { project: project.name })}</h2>
        {editable && !editing && <button type="button" className={`${secondaryButton} ml-auto`} onClick={start}>{c.add}</button>}
      </div>
      <p className="px-4 pt-1 text-[12px] text-text-3">{c.hint}</p>
      {message && <p role="status" className="mx-4 mt-2 rounded-control bg-info-bg px-3 py-2 text-[12.5px] text-info">{message}</p>}
      {editing ? (
        <div className="px-4 py-3">
          {(all.data ?? []).length === 0 ? (
            <p className="text-[12.5px] text-text-2">{c.none}</p>
          ) : (
            <ul className="mb-3 grid grid-cols-2 gap-2">
              {(all.data ?? []).map((item) => (
                <li key={item.id}>
                  <label className="flex items-center gap-2 rounded-control border border-border px-3 py-2 text-[13px]">
                    <input type="checkbox" checked={chosen.includes(item.id)} onChange={() => setChosen(toggle(chosen, item.id))} />
                    <span className="font-semibold">{item.displayName}</span>
                    <code className="font-mono text-[12px] text-text-3">{item.slug}</code>
                  </label>
                </li>
              ))}
            </ul>
          )}
          <div className="flex gap-2">
            <button type="button" className={primaryButton} disabled={busy} onClick={save}>{c.save}</button>
            <button type="button" className={secondaryButton} onClick={() => setEditing(false)}>{a.common.cancel}</button>
          </div>
        </div>
      ) : linked.isError ? (
        <ErrorCard message={a.common.loadError} onRetry={() => linked.refetch()} />
      ) : linked.data && linked.data.length === 0 ? (
        <p className="px-4 py-6 text-[12.5px] text-text-2">{c.empty}</p>
      ) : (
        <DataTable columns={columns} rows={linked.data ?? []} rowKey={(r) => r.id} loading={linked.isLoading} caption={c.title} />
      )}
    </section>
  );
}

function PipelinesTab({ project }: { project: Project }) {
  const t = useMessages();
  const a = t.admin;
  const p = a.projects.detail.pipelines;
  const pipelines = usePipelineList();
  const mine = (pipelines.data ?? []).filter((item) => item.projectId === project.id);

  return (
    <section className="rounded-card border border-border bg-surface shadow-card">
      {pipelines.isError ? (
        <ErrorCard message={a.common.loadError} onRetry={() => pipelines.refetch()} />
      ) : pipelines.data && mine.length === 0 ? (
        <p className="px-4 py-6 text-[12.5px] text-text-2">{p.empty}</p>
      ) : (
        <ul className="divide-y divide-border">
          {mine.map((item) => (
            <li key={item.id} className="flex items-center gap-3 px-4 py-3 text-[13px]">
              <span className="font-semibold">{item.name}</span>
              <Link to={`/pipelines/${item.id}/matrix`} className="ml-auto text-[12px] font-semibold text-primary hover:underline">{p.open}</Link>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
