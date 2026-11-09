import { useState } from 'react';
import { Link } from 'react-router-dom';

import { usePipelineList, useStages } from '../../api/admin';
import { useEnvironments } from '../../api/catalog';
import { usePolicyExceptions } from '../../api/governance';
import type { PolicyException } from '../../api/governance';
import { useProjectApprovals, useProjectDrift, useProjectFindings } from '../../api/projectGovernance';
import type { ApprovalItem, FindingResult, ProjectDriftEntry, ProjectFinding } from '../../api/projectGovernance';
import DataTable from '../../components/DataTable';
import type { Column } from '../../components/DataTable';
import EnvBadge from '../../components/EnvBadge';
import FilterSelect from '../../components/FilterSelect';
import KpiCard from '../../components/KpiCard';
import Pagination from '../../components/Pagination';
import StatusBadge from '../../components/StatusBadge';
import { EmptyState, ErrorCard } from '../../components/States';
import { fill } from '../../i18n';
import { useLocale, useMessages } from '../../i18n/useMessages';
import { formatDateTime, formatNumber } from '../../lib/format';
import { gateTone } from '../governance/rules';
import { usePromotionChanges } from '../../api/changesets';
import { ChangesetPeek } from './ChangesetSource';

const PAGE_SIZE = 25;
const RESULTS: FindingResult[] = ['BLOCKED', 'WARNED', 'PASSED'];

/** What the policies said about the project: its findings, counted by result, and the exceptions that cover it. */
export function PoliciesTab({ projectId, slug }: { projectId: string; slug: string }) {
  const t = useMessages();
  const g = t.projects.gov.policies;
  const locale = useLocale();
  const [result, setResult] = useState('');
  const [page, setPage] = useState(0);
  const findings = useProjectFindings(projectId, result, page, PAGE_SIZE);
  const exceptions = usePolicyExceptions();
  const mine = (exceptions.data ?? []).filter((item) => item.project === slug);
  const total = findings.data?.totalElements ?? 0;

  const columns: Column<ProjectFinding>[] = [
    { key: 'time', header: g.columns.time, cell: (row) => <span className="whitespace-nowrap">{formatDateTime(row.at, locale)}</span> },
    { key: 'check', header: g.columns.check, cell: (row) => <span className="font-mono">{row.check}</span> },
    { key: 'result', header: g.columns.result, cell: (row) => <StatusBadge tone={gateTone(row.result)}>{t.governance.gate[row.result]}</StatusBadge> },
    {
      key: 'changeset', header: g.columns.changeset,
      cell: (row) => (row.changesetId
        ? <Link to={`/changesets/${row.changesetId}`} onClick={(event) => event.stopPropagation()} className="font-mono text-primary hover:underline">{row.changeset}</Link>
        : <span className="text-text-3">{t.common.none}</span>),
    },
    { key: 'environment', header: g.columns.environment, cell: (row) => <EnvBadge code={row.environment} /> },
    { key: 'message', header: g.columns.message, cell: (row) => row.message },
  ];
  const exceptionColumns: Column<PolicyException>[] = [
    { key: 'check', header: g.exceptionColumns.check, cell: (row) => <span className="font-mono">{row.check}</span> },
    { key: 'changeset', header: g.exceptionColumns.changeset, cell: (row) => row.changeset ?? <span className="text-text-3">{t.common.none}</span> },
    { key: 'environment', header: g.exceptionColumns.environment, cell: (row) => (row.environment ? <EnvBadge code={row.environment} /> : <span className="text-text-3">{t.common.none}</span>) },
    {
      key: 'status', header: g.exceptionColumns.status,
      cell: (row) => <StatusBadge tone={row.status === 'APPROVED' ? 'success' : row.status === 'REQUESTED' ? 'info' : 'neutral'}>{t.governance.exceptions.status[row.status]}</StatusBadge>,
    },
    { key: 'by', header: g.exceptionColumns.by, cell: (row) => <span className="text-text-2">{row.requestedBy}</span> },
    { key: 'expires', header: g.exceptionColumns.expires, cell: (row) => <span className="whitespace-nowrap">{formatDateTime(row.expiresAt, locale)}</span> },
    { key: 'why', header: g.exceptionColumns.why, cell: (row) => <span className="text-text-2">{row.justification}</span> },
  ];

  return (
    <>
      <div className="mb-3.5 grid grid-cols-3 gap-3.5">
        {RESULTS.map((key) => (
          <KpiCard key={key} label={g.counts[key]} value={findings.data ? formatNumber(findings.data.counts[key] ?? 0, locale) : t.common.none}
            tone={key === 'BLOCKED' && (findings.data?.counts.BLOCKED ?? 0) > 0 ? 'danger' : key === 'WARNED' && (findings.data?.counts.WARNED ?? 0) > 0 ? 'warning' : 'default'} />
        ))}
      </div>
      <div className="mb-3 flex items-center gap-2">
        <FilterSelect label={g.all} value={result} onChange={(value) => { setResult(value); setPage(0); }}
          options={[{ value: '', label: g.all }, ...RESULTS.map((key) => ({ value: key, label: t.governance.gate[key] }))]} />
        <Link to="/govern/enforcement" className="ml-auto text-[12px] font-semibold text-primary hover:underline">{g.manage} ↗</Link>
      </div>
      {findings.isError ? (
        <ErrorCard message={t.projects.loadError} onRetry={() => findings.refetch()} />
      ) : findings.data && total === 0 ? (
        <EmptyState title={g.empty.title} hint={g.empty.hint} />
      ) : (
        <DataTable columns={columns} rows={findings.data?.content ?? []} rowKey={(row) => row.id} loading={findings.isLoading}
          rowClass={(row) => (row.result === 'BLOCKED' ? 'bg-row-danger' : undefined)} caption={t.projects.tabs.policies} />
      )}
      <div className="mt-3 flex justify-end"><Pagination page={page} totalPages={Math.max(1, Math.ceil(total / PAGE_SIZE))} onPage={setPage} /></div>

      <h2 className="mt-5 mb-2 text-[15px] font-bold">{g.exceptions}</h2>
      {exceptions.data && mine.length === 0 ? (
        <p className="text-text-2">{g.exceptionsEmpty}</p>
      ) : (
        <DataTable columns={exceptionColumns} rows={mine} rowKey={(row) => row.id} loading={exceptions.isLoading} caption={g.exceptions} />
      )}
    </>
  );
}

/** The latest drift check of each database of the project. A database never checked says so: that is not "clean". */
export function DriftTab({ projectId }: { projectId: string }) {
  const t = useMessages();
  const g = t.projects.gov.drift;
  const locale = useLocale();
  const drift = useProjectDrift(projectId);

  const situation = (row: ProjectDriftEntry): { tone: 'danger' | 'warning' | 'success' | 'neutral'; text: string } => {
    if (!row.runId) return { tone: 'neutral', text: g.status.never };
    if (row.status === 'FAILED') return { tone: 'danger', text: g.status.failed };
    return row.hasDrift ? { tone: 'warning', text: g.status.drift } : { tone: 'success', text: g.status.clean };
  };
  const columns: Column<ProjectDriftEntry>[] = [
    { key: 'environment', header: g.columns.environment, cell: (row) => <EnvBadge code={row.environment} /> },
    { key: 'connection', header: g.columns.connection, cell: (row) => <span className="font-mono">{row.connection}</span> },
    { key: 'status', header: g.columns.status, cell: (row) => { const s = situation(row); return <StatusBadge tone={s.tone}>{s.text}</StatusBadge>; } },
    {
      key: 'items', header: g.columns.items,
      cell: (row) => (row.hasDrift
        ? <span className="font-mono">{fill(g.items, { added: row.added ?? 0, removed: row.removed ?? 0, modified: row.modified ?? 0 })}</span>
        : <span className="text-text-3">{t.common.none}</span>),
    },
    { key: 'checked', header: g.columns.checked, cell: (row) => (row.checkedAt ? <span className="whitespace-nowrap">{formatDateTime(row.checkedAt, locale)}</span> : <span className="text-text-3">{t.common.none}</span>) },
    {
      key: 'open', header: '',
      cell: (row) => <Link to={`/drift?connection=${encodeURIComponent(row.connection)}`} onClick={(event) => event.stopPropagation()} className="text-[12px] font-semibold text-primary hover:underline">{g.open} ↗</Link>,
    },
  ];

  return drift.isError ? (
    <ErrorCard message={t.projects.loadError} onRetry={() => drift.refetch()} />
  ) : drift.data && drift.data.length === 0 ? (
    <EmptyState title={g.empty.title} hint={g.empty.hint} />
  ) : (
    <DataTable columns={columns} rows={drift.data ?? []} rowKey={(row) => row.connectionId} loading={drift.isLoading} caption={t.projects.tabs.drift} />
  );
}

/** The project's pipelines with their stages in order, and the screens that show each one. */
export function PipelinesTab({ projectId }: { projectId: string }) {
  const t = useMessages();
  const g = t.projects.gov.pipelines;
  const pipelines = usePipelineList();
  const mine = (pipelines.data ?? []).filter((item) => item.projectId === projectId);

  if (pipelines.isError) {
    return <ErrorCard message={t.projects.loadError} onRetry={() => pipelines.refetch()} />;
  }
  if (pipelines.data && mine.length === 0) {
    return <EmptyState title={g.empty.title} hint={g.empty.hint} />;
  }
  return (
    <ul className="flex flex-col gap-3">
      {mine.map((pipeline) => (
        <li key={pipeline.id} className="rounded-card border border-border bg-surface p-4 shadow-card">
          <div className="flex items-center gap-3">
            <h2 className="text-[15px] font-bold">{pipeline.name}</h2>
            <span className="ml-auto flex gap-3 text-[12px] font-semibold">
              <Link to={`/pipelines/${pipeline.id}/matrix`} className="text-primary hover:underline">{g.matrix} ↗</Link>
              <Link to={`/pipelines/${pipeline.id}/timeline`} className="text-primary hover:underline">{g.timeline} ↗</Link>
              <Link to={`/govern/enforcement?pipeline=${pipeline.id}`} className="text-primary hover:underline">{g.enforcement} ↗</Link>
            </span>
          </div>
          <Stages pipelineId={pipeline.id} />
        </li>
      ))}
    </ul>
  );
}

function Stages({ pipelineId }: { pipelineId: string }) {
  const t = useMessages();
  const g = t.projects.gov.pipelines;
  const stages = useStages(pipelineId);
  const environments = useEnvironments();
  const code = (id: string) => environments.data?.find((env) => env.id === id)?.code ?? '…';
  const ordered = [...(stages.data ?? [])].sort((a, b) => a.sortOrder - b.sortOrder);
  return (
    <ol aria-label={g.stages} className="mt-3 flex flex-wrap items-center gap-2">
      {ordered.map((stage, index) => (
        <li key={stage.id} className="flex items-center gap-2">
          {index > 0 && <span aria-hidden className="text-text-3">→</span>}
          <span className="inline-flex items-center gap-1.5 rounded-control border border-border px-2 py-1">
            <EnvBadge code={code(stage.environmentId)} />
            {stage.requiresApproval && <StatusBadge tone="info">{g.approval}</StatusBadge>}
            {stage.requiresApproval && stage.autoRequest && <StatusBadge tone="neutral">{g.auto}</StatusBadge>}
          </span>
        </li>
      ))}
    </ol>
  );
}

/** Everything of the project that waits for a decision, with what each one does, and who may decide. */
export function ApprovalsTab({ projectId }: { projectId: string }) {
  const t = useMessages();
  const g = t.projects.gov.approvals;
  const locale = useLocale();
  const approvals = useProjectApprovals(projectId);
  const items = approvals.data?.items ?? [];

  if (approvals.isError) {
    return <ErrorCard message={t.projects.loadError} onRetry={() => approvals.refetch()} />;
  }
  if (approvals.data && items.length === 0) {
    return <EmptyState title={g.empty.title} hint={g.empty.hint} />;
  }
  return (
    <ul className="flex flex-col gap-3">
      {items.map((item) => (
        <li key={`${item.kind}-${item.id}`} className="rounded-card border border-border bg-surface p-4 shadow-card">
          <div className="flex flex-wrap items-center gap-2">
            <StatusBadge tone="info">{g.kinds[item.kind]}</StatusBadge>
            <span className="font-semibold">{describe(item, g)}</span>
            <span className="text-text-2">{t.matrix.pending.by.replace('{name}', item.requestedBy)} · {formatDateTime(item.at, locale)}</span>
            <StatusBadge tone={item.decidable ? 'success' : 'neutral'}>{item.mine ? g.mine : item.decidable ? g.decidable : g.waiting}</StatusBadge>
            <Link to={target(item)} className="ml-auto text-[12px] font-semibold text-primary hover:underline">{g.decide} ↗</Link>
          </div>
          <Detail item={item} />
        </li>
      ))}
    </ul>
  );
}

function describe(item: ApprovalItem, g: ReturnType<typeof useMessages>['projects']['gov']['approvals']): string {
  switch (item.kind) {
    case 'PROMOTION':
      return fill(g.promotion, { env: item.environment ?? '', pipeline: item.pipeline ?? '', release: item.release ? ` (${item.release})` : '' });
    case 'ROLLBACK':
      return fill(g.rollback, { env: item.environment ?? '', changeset: item.changeset ?? '' });
    default:
      return fill(g.exception, { check: item.check ?? '', changeset: item.changeset ? `: ${item.changeset}` : '' });
  }
}

function target(item: ApprovalItem): string {
  switch (item.kind) {
    case 'PROMOTION': return `/pipelines/${item.pipelineId}/matrix`;
    case 'ROLLBACK': return `/operations/${item.operationId}`;
    default: return '/govern/enforcement';
  }
}

/** What the request is about, readable in place: a promotion lists what it would apply, the others the changeset they name. */
function Detail({ item }: { item: ApprovalItem }) {
  if (item.kind === 'PROMOTION') {
    return <PromotionDetail id={item.id} />;
  }
  return item.changesetId && item.changeset ? <div className="mt-2"><ChangesetPeek id={item.changesetId} label={item.changeset} /></div> : null;
}

function PromotionDetail({ id }: { id: string }) {
  const changes = usePromotionChanges(id);
  const rows = changes.data ?? [];
  if (rows.length === 0) {
    return null;
  }
  return (
    <ul className="mt-2 flex flex-col gap-1.5 border-l-2 border-border pl-3">
      {rows.map((change) => (
        <li key={change.id}><ChangesetPeek id={change.id} label={change.changesetId} detail={`${change.author} · ${change.changelog}`} /></li>
      ))}
    </ul>
  );
}
