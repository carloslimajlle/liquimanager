import { useState } from 'react';
import type { ReactNode } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';

import { useChangeset, useChangesetVersions } from '../../api/changesets';
import type { ChangesetDetail as Detail, ChangesetVersion } from '../../api/changesets';
import CommandChip from '../../components/CommandChip';
import DataTable from '../../components/DataTable';
import type { Column } from '../../components/DataTable';
import EnvBadge from '../../components/EnvBadge';
import PageHeader from '../../components/PageHeader';
import StatusBadge from '../../components/StatusBadge';
import { ApiError } from '../../api/client';
import { EmptyState, ErrorCard } from '../../components/States';
import { useLocale, useMessages } from '../../i18n/useMessages';
import { formatDateTime } from '../../lib/format';
import Topbar from '../../shell/Topbar';
import ChangesetSource from './ChangesetSource';
import GenerateSql from './GenerateSql';
import { gateLabel, gateTone } from '../governance/rules';
import { outcomeTone, stateTone } from '../operations/rules';

/** /changesets/:id: one changeset and everything the control plane knows about it, across every database. */
export default function ChangesetDetail() {
  const t = useMessages();
  const p = t.projects;
  const c = p.changeset;
  const locale = useLocale();
  const navigate = useNavigate();
  const { id } = useParams();
  const query = useChangeset(id);
  const versions = useChangesetVersions(id);
  const data = query.data;
  const [sqlOpen, setSqlOpen] = useState(false);
  const when = (iso?: string | null) => (iso ? formatDateTime(iso, locale) : t.common.none);
  const statusText = (status: string) => p.status[status as keyof typeof p.status] ?? status;

  const states: Column<Detail['states'][number]>[] = [
    { key: 'environment', header: c.states.columns.environment, cell: (row) => <EnvBadge code={row.environment} /> },
    { key: 'connection', header: c.states.columns.connection, cell: (row) => <span className="font-mono">{row.connection}</span> },
    { key: 'status', header: c.states.columns.status, cell: (row) => <StatusBadge tone={stateTone(row.status)}>{statusText(row.status)}</StatusBadge> },
    { key: 'type', header: c.states.columns.type, cell: (row) => <span className="font-mono text-text-2">{row.execType ?? t.common.none}</span> },
    { key: 'deployed', header: c.states.columns.deployed, cell: (row) => <span className="whitespace-nowrap">{when(row.deployedAt)}</span> },
    { key: 'tag', header: c.states.columns.tag, cell: (row) => <span className="font-mono text-text-2">{row.tag ?? t.common.none}</span> },
  ];
  const operations: Column<Detail['operations'][number]>[] = [
    { key: 'time', header: c.operations.columns.time, cell: (row) => <span className="whitespace-nowrap">{when(row.startedAt)}</span> },
    { key: 'command', header: c.operations.columns.command, cell: (row) => <CommandChip command={row.command} /> },
    { key: 'environment', header: c.operations.columns.environment, cell: (row) => <EnvBadge code={row.environment} /> },
    { key: 'connection', header: c.operations.columns.connection, cell: (row) => <span className="font-mono">{row.connection}</span> },
    {
      key: 'outcome', header: c.operations.columns.outcome,
      cell: (row) => <StatusBadge tone={outcomeTone(row.outcome)}>{t.detail.changesets.outcome[row.outcome as keyof typeof t.detail.changesets.outcome] ?? row.outcome}</StatusBadge>,
    },
    { key: 'by', header: c.operations.columns.by, cell: (row) => <span className="text-text-2">{row.executedBy ?? t.common.none}</span> },
  ];
  const findings: Column<Detail['findings'][number]>[] = [
    { key: 'check', header: c.findings.columns.check, cell: (row) => <span className="font-mono">{row.check}</span> },
    { key: 'result', header: c.findings.columns.result, cell: (row) => <StatusBadge tone={gateTone(row.result as never)}>{gateLabel(row.result as never, t)}</StatusBadge> },
    { key: 'message', header: c.findings.columns.message, cell: (row) => row.message },
    { key: 'time', header: c.findings.columns.time, cell: (row) => <span className="whitespace-nowrap">{when(row.at)}</span> },
  ];
  const rollbacks: Column<Detail['rollbacks'][number]>[] = [
    { key: 'time', header: c.rollbacks.columns.time, cell: (row) => <span className="whitespace-nowrap">{when(row.at)}</span> },
    { key: 'environment', header: c.rollbacks.columns.environment, cell: (row) => <EnvBadge code={row.environment} /> },
    { key: 'status', header: c.rollbacks.columns.status, cell: (row) => <StatusBadge tone="neutral">{row.status}</StatusBadge> },
    { key: 'by', header: c.rollbacks.columns.by, cell: (row) => <span className="text-text-2">{row.requestedBy}</span> },
    { key: 'reason', header: c.rollbacks.columns.reason, cell: (row) => row.reason ?? t.common.none },
  ];

  return (
    <>
      <Topbar trail={[t.app.name, p.title, data?.project ?? '…', data?.changesetId ?? '…']} />
      <PageHeader
        title={data?.changesetId ?? p.changeset.back}
        subtitle={data ? `${data.author} · ${data.filePath}` : undefined}
        right={data && (
          <>
            <Link to={`/projects/${data.projectId}?tab=changesets`} className="text-[12px] font-semibold text-primary hover:underline">← {data.project}</Link>
            <button type="button" onClick={() => setSqlOpen(true)}
              className="rounded-control border border-border px-3 py-1.5 text-[12px] font-semibold hover:bg-surface-alt">{t.projects.sql.button}</button>
          </>
        )}
      />
      {sqlOpen && data && <GenerateSql projectId={data.projectId} project={data.project} initial={{ mode: 'ids', value: data.id }} onClose={() => setSqlOpen(false)} />}
      {query.isError ? (
        query.error instanceof ApiError && query.error.status === 403
          ? <EmptyState title={t.common.forbidden} />
          : <ErrorCard message={c.loadError} onRetry={() => query.refetch()} />
      ) : !data ? null : (
        <div className="grid gap-3.5">
          <section className="rounded-card border border-border bg-surface p-4 shadow-card">
            <dl className="grid grid-cols-[160px_1fr] gap-y-2 text-[13px]">
              <Term>{c.identity.author}</Term><dd>{data.author}</dd>
              <Term>{c.identity.file}</Term><dd><code className="font-mono text-[12px]">{data.filePath}</code></dd>
              <Term>{c.identity.changelog}</Term>
              <dd><Link to={`/projects/${data.projectId}/changelogs/${data.changelogId}`} className="text-primary hover:underline">{data.changelog}</Link></dd>
              <Term>{c.identity.release}</Term><dd>{data.release ? <code className="font-mono text-[12px]">{data.release}</code> : t.common.none}</dd>
              <Term>{c.identity.labels}</Term><dd>{data.labels ? <code className="font-mono text-[12px]">{data.labels}</code> : t.common.none}</dd>
              <Term>{c.identity.contexts}</Term><dd>{data.contexts ? <code className="font-mono text-[12px]">{data.contexts}</code> : t.common.none}</dd>
              <Term>{c.identity.checksum}</Term><dd>{data.checksum ? <code className="font-mono text-[12px]">{data.checksum}</code> : t.common.none}</dd>
              <Term>{c.identity.rollback}</Term>
              <dd><StatusBadge tone={data.hasRollback ? 'success' : 'warning'}>{data.hasRollback ? c.hasRollback : c.noRollback}</StatusBadge></dd>
              <Term>{c.identity.firstSeen}</Term><dd>{when(data.firstSeenAt)}</dd>
            </dl>
          </section>

          <section>
            <h2 className="mb-2 text-[15px] font-bold">{p.source.title}</h2>
            <ChangesetSource changesetId={data.id} />
          </section>

          {(versions.data?.length ?? 0) > 0 && (
            <section>
              <h2 className="mb-2 text-[15px] font-bold">{c.versions.title}</h2>
              {versions.data!.some((version) => version.changed) && <p className="mb-2 text-[12.5px] text-text-2">{c.versions.warning}</p>}
              <ul className="flex flex-col gap-3">
                {versions.data!.map((version) => <VersionRow key={`${version.environment}-${version.connection}`} version={version} />)}
              </ul>
            </section>
          )}

          <Block title={c.states.title} empty={data.states.length === 0 ? c.states.empty : null}>
            <DataTable columns={states} rows={data.states} rowKey={(row) => `${row.environment}-${row.connection}`}
              onRowClick={(row) => row.operationId && navigate(`/operations/${row.operationId}`)} caption={c.states.title} />
          </Block>
          <Block title={c.operations.title} empty={data.operations.length === 0 ? c.operations.empty : null}>
            <DataTable columns={operations} rows={data.operations} rowKey={(row) => row.id} onRowClick={(row) => navigate(`/operations/${row.id}`)}
              rowLabel={(row) => row.id.slice(0, 8)} caption={c.operations.title} />
          </Block>
          <Block title={c.findings.title} empty={data.findings.length === 0 ? c.findings.empty : null}>
            <DataTable columns={findings} rows={data.findings} rowKey={(row) => `${row.operationId}-${row.check}-${row.at}`} caption={c.findings.title} />
          </Block>
          <Block title={c.rollbacks.title} empty={data.rollbacks.length === 0 ? c.rollbacks.empty : null}>
            <DataTable columns={rollbacks} rows={data.rollbacks} rowKey={(row) => row.id}
              onRowClick={(row) => row.operationId && navigate(`/operations/${row.operationId}`)} caption={c.rollbacks.title} />
          </Block>
        </div>
      )}
    </>
  );
}

function Term({ children }: { children: ReactNode }) {
  return <dt className="text-[12px] font-semibold text-text-2">{children}</dt>;
}

function Block({ title, empty, children }: { title: string; empty: string | null; children: ReactNode }) {
  return (
    <section>
      <h2 className="mb-2 text-[15px] font-bold">{title}</h2>
      {empty ? <EmptyState title={empty} /> : children}
    </section>
  );
}

/** One database: the version it ran against the repository's, and when they differ the lines that changed. */
function VersionRow({ version }: { version: ChangesetVersion }) {
  const t = useMessages();
  const v = t.projects.changeset.versions;
  const locale = useLocale();
  return (
    <li className="rounded-card border border-border bg-surface p-3.5 shadow-card">
      <div className="flex flex-wrap items-center gap-2">
        <EnvBadge code={version.environment} />
        <span className="font-mono">{version.connection}</span>
        <StatusBadge tone={version.changed ? 'danger' : 'success'}>{version.changed ? v.changed : v.same}</StatusBadge>
        {version.appliedAt && <span className="text-text-2">{formatDateTime(version.appliedAt, locale)}</span>}
        <span className="ml-auto font-mono text-[12px] text-text-3">{version.appliedChecksum}{version.changed ? ` → ${version.currentChecksum}` : ''}</span>
      </div>
      {version.changed && (
        <div className="mt-2.5">
          {version.sourceKnown === false || !version.diff ? (
            <p className="text-[12.5px] text-text-2">{v.unknown}</p>
          ) : version.equivalent ? (
            <p className="text-[12.5px] text-warning">{v.equivalent}</p>
          ) : (
            <>
              <p className="mb-1.5 text-[12.5px] text-danger">{v.structural}</p>
              <div role="region" aria-label={v.diffLabel} className="overflow-auto rounded-control border border-border bg-surface-alt py-1.5 font-mono text-[12.5px] leading-[21px]">
                {version.diff.map((line, index) => (
                  <div key={index} className={`flex whitespace-pre px-3 ${line.op === '-' ? 'bg-row-danger text-danger' : 'text-success'}`}>
                    <span aria-hidden className="w-4 select-none">{line.op}</span>
                    <span className="sr-only">{line.op === '-' ? v.removed : v.added}: </span>
                    <span>{line.text}</span>
                  </div>
                ))}
              </div>
            </>
          )}
        </div>
      )}
    </li>
  );
}
