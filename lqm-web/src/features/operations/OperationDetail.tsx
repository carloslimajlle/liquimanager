import { useState } from 'react';
import type { ReactNode } from 'react';
import { Link, useParams } from 'react-router-dom';

import { ApiError } from '../../api/client';
import {
  useOperation, useOperationChangesets, useOperationText,
} from '../../api/operations';
import type { OperationChangeset, OperationDetail as Detail } from '../../api/operations';
import CodeBlock from '../../components/CodeBlock';
import CommandChip from '../../components/CommandChip';
import DataTable from '../../components/DataTable';
import type { Column } from '../../components/DataTable';
import EnvBadge from '../../components/EnvBadge';
import StatusBadge from '../../components/StatusBadge';
import type { Tone } from '../../components/StatusBadge';
import { EmptyState, ErrorCard } from '../../components/States';
import { fill } from '../../i18n';
import { useLocale, useMessages } from '../../i18n/useMessages';
import { formatDateTime, formatDuration, formatShortDateTime } from '../../lib/format';
import Topbar from '../../shell/Topbar';
import { crumb } from '../../shell/crumbs';
import RollbackPanel from './RollbackPanel';
import { isFailure, outcomeTone, statusTone, stateTone } from './rules';

const SEVERITY_TONE: Tone[] = ['info', 'minor', 'warning', 'danger', 'danger'];

function Card({ children, className = '' }: { children: ReactNode; className?: string }) {
  return (
    <div className={`rounded-card border border-border bg-surface shadow-card ${className}`}>{children}</div>
  );
}

function Fact({ label, children }: { label: string; children: ReactNode }) {
  return (
    <Card className="px-3.5 py-3">
      <div className="text-[11px] font-semibold tracking-[0.06em] text-text-2 uppercase">{label}</div>
      <div className="mt-1.5 text-[14px]">{children}</div>
    </Card>
  );
}

/** Screen 02: one operation in detail, with the diagnosis a failure needs. */
export default function OperationDetail() {
  const t = useMessages();
  const locale = useLocale();
  const { id = '' } = useParams();
  const operation = useOperation(id);
  const changesets = useOperationChangesets(id, 0, 25);

  const shortId = id.slice(0, 8);

  if (operation.isError) {
    const missing = operation.error instanceof ApiError && (operation.error.status === 404 || operation.error.status === 403);
    const forbidden = operation.error instanceof ApiError && operation.error.status === 403;
    return (
      <>
        <Topbar trail={[crumb(t.detail.breadcrumbRoot, '/operations'), t.detail.notFound]} />
        <Card className="mt-3">
          {missing ? (
            <EmptyState
              title={forbidden ? t.common.forbidden : t.detail.notFound}
              action={<Link to="/operations" className="font-semibold text-primary hover:underline">{t.detail.back}</Link>}
            />
          ) : (
            <ErrorCard message={t.detail.error} onRetry={() => operation.refetch()} />
          )}
        </Card>
      </>
    );
  }

  const data = operation.data;
  if (!data) {
    return (
      <>
        <Topbar trail={[crumb(t.detail.breadcrumbRoot, '/operations'), crumb(t.detail.breadcrumbMiddle, '/operations'), `database-change #${shortId}`]} />
        <div className="mt-3 space-y-3" aria-busy="true" aria-label={t.common.loading}>
          <div className="h-10 w-72 animate-pulse rounded bg-border" />
          <div className="h-24 animate-pulse rounded-card bg-border" />
          <div className="h-64 animate-pulse rounded-card bg-border" />
        </div>
      </>
    );
  }

  const evaluated = data.summary.executed + data.summary.previouslyRun + data.summary.filtered + data.summary.failed;
  const failed = isFailure(data.status);

  return (
    <>
      <Topbar trail={[crumb(t.detail.breadcrumbRoot, '/operations'), crumb(t.detail.breadcrumbMiddle, '/operations'), `database-change #${shortId}`]} />

      <div className="mt-1.5 mb-3 flex items-center gap-3">
        <h1 className="text-[24px] font-bold tracking-[-0.02em]">{t.detail.title}</h1>
        <CommandChip command={data.command} />
        <StatusBadge tone={statusTone(data.status)}>{t.operations.status[data.status]}</StatusBadge>
        <span className="font-mono text-text-3">{shortId}</span>
        <div className="ml-auto flex gap-4 text-text-2">
          <span>{t.detail.links.database} <b className="font-semibold text-primary">{data.connection}</b> <EnvBadge code={data.environment} /></span>
          {data.changelog && <span>{t.detail.links.changelog} <b className="font-semibold text-primary">{data.changelog}</b></span>}
          {data.project && <span>{t.detail.links.project} <b className="font-semibold text-primary">{data.project}</b></span>}
          <span>{t.detail.links.executedBy} <b className="font-semibold text-primary">{data.source} · {data.executedBy}</b></span>
        </div>
      </div>

      <div className="mb-3 grid grid-cols-[1fr_1fr_0.7fr_1fr_1.6fr] gap-3">
        <Fact label={t.detail.facts.start}>{formatDateTime(data.startedAt, locale)}</Fact>
        <Fact label={t.detail.facts.end}>{data.endedAt ? formatDateTime(data.endedAt, locale) : t.common.none}</Fact>
        <Fact label={t.detail.facts.duration}>
          {data.durationMs == null ? t.common.none : formatDuration(data.durationMs, locale)}
        </Fact>
        <Fact label={t.detail.facts.deploymentId}>
          <span className="font-mono">{data.deploymentId ?? t.common.none}</span>
        </Fact>
        <Fact label={fill(t.detail.facts.result, { n: evaluated })}>
          <span className="flex flex-wrap gap-1.5">
            <StatusBadge tone="neutral">{fill(t.detail.outcome.executed, { n: data.summary.executed })}</StatusBadge>
            {data.summary.previouslyRun > 0 && (
              <StatusBadge tone="info">{fill(t.detail.outcome.previouslyRun, { n: data.summary.previouslyRun })}</StatusBadge>
            )}
            {data.summary.filtered > 0 && (
              <StatusBadge tone="warning">{fill(t.detail.outcome.filtered, { n: data.summary.filtered })}</StatusBadge>
            )}
            {data.summary.failed > 0 && (
              <StatusBadge tone="danger">{fill(t.detail.outcome.failed, { n: data.summary.failed })}</StatusBadge>
            )}
          </span>
        </Fact>
      </div>

      {failed && <Diagnosis data={data} />}

      <Card className="mb-3 overflow-hidden">
        <div className="flex items-center px-3.5 pt-3 pb-1 font-bold">
          {t.detail.changesets.title}
          <span className="ml-auto text-[12px] font-normal text-text-3">
            {fill(t.detail.changesets.meta, {
              shown: changesets.data?.content.length ?? 0,
              total: changesets.data?.totalElements ?? 0,
              changelog: data.changelog ?? t.common.none,
            })}
          </span>
        </div>
        <ChangesetTable rows={changesets.data?.content ?? []} loading={changesets.isLoading} />
      </Card>

      <RollbackPanel operationId={id} changesets={changesets.data?.content ?? []} />

      <TextTabs id={id} data={data} />
    </>
  );
}

// ---------------------------------------------------------------- diagnosis

/**
 * Failure diagnosis: everything already collected that a person needs to understand
 * why this broke. It stands in for AI analysis, a future feature (D-13), and the most
 * telling block is the last: the SAME changeset in the other environments. "Passed in
 * DEV, TST and HML, failed in PRD" points at the data, not at the changeset.
 */
function Diagnosis({ data }: { data: Detail }) {
  const t = useMessages();
  const locale = useLocale();
  const sql = useOperationText(data.id, 'sql', data.hasSql);
  const failedChangeset = data.failedChangeset;

  const others = (failedChangeset?.environments ?? []).filter((e) => e.environment !== data.environment);
  const passedEverywhereElse = others.length > 0 && others.every((e) => e.status === 'EXECUTED');
  const message = data.errorMessage ?? failedChangeset?.errorMessage;
  const stateLabel = (status: string) => (t.detail.state as Record<string, string>)[status] ?? status;

  return (
    <Card className="mb-3 px-4 py-3.5">
      <div className="mb-3 flex items-center gap-2.5">
        <div>
          <b>{t.detail.diagnosis.title}</b>
          <div className="text-[12px] text-text-2">{t.detail.diagnosis.subtitle}</div>
        </div>
        {failedChangeset && (
          <div className="ml-auto flex items-center gap-2 text-[12px] text-text-2">
            {t.detail.diagnosis.changeset}
            <span className="font-mono">{failedChangeset.id}</span>
            <StatusBadge tone="danger">{t.operations.status.FAILURE}</StatusBadge>
          </div>
        )}
      </div>

      <div className="grid grid-cols-[1fr_420px] gap-5">
        <div>
          {message && (
            <>
              <h5 className="mt-2.5 mb-1 text-[12px] font-bold">{t.detail.diagnosis.error}</h5>
              <CodeBlock variant="error">{message}</CodeBlock>
            </>
          )}

          <h5 className="mt-2.5 mb-1 text-[12px] font-bold">{t.detail.diagnosis.failedSql}</h5>
          {sql.data ? (
            <CodeBlock copyable>{sql.data}</CodeBlock>
          ) : (
            <p className="text-text-2">{sql.isLoading ? t.common.loading : t.detail.diagnosis.noSql}</p>
          )}

          {data.findings.length > 0 && (
            <>
              <h5 className="mt-2.5 mb-1 text-[12px] font-bold">{t.detail.diagnosis.findings}</h5>
              <ul className="space-y-1.5">
                {data.findings.map((finding) => (
                  <li key={`${finding.check}-${finding.changeset}`} className="flex items-center gap-2 text-[12px]">
                    <StatusBadge tone={SEVERITY_TONE[finding.severity] ?? 'neutral'}>
                      {t.detail.severity[finding.severity] ?? String(finding.severity)}
                    </StatusBadge>
                    <span className="font-mono">{finding.check}</span>
                    <span className="text-text-2">{finding.message}</span>
                  </li>
                ))}
              </ul>
            </>
          )}
        </div>

        <div>
          <h5 className="mt-2.5 mb-1 text-[12px] font-bold">{t.detail.diagnosis.context}</h5>
          <dl className="grid grid-cols-[auto_1fr_auto_1fr] gap-x-2.5 gap-y-1 text-[12px]">
            <dt className="text-text-2">{t.detail.diagnosis.engine}</dt>
            <dd className="font-mono">{[data.runtime.engine, data.runtime.dbVersion].filter(Boolean).join(' ') || t.common.none}</dd>
            <dt className="text-text-2">{t.detail.diagnosis.liquibase}</dt>
            <dd className="font-mono">{data.runtime.liquibaseVersion ?? t.common.none}</dd>
            <dt className="text-text-2">{t.detail.diagnosis.collector}</dt>
            <dd className="font-mono">{data.runtime.collector ?? t.common.none}</dd>
            <dt className="text-text-2">{t.detail.diagnosis.pipeline}</dt>
            <dd className="font-mono">
              {data.ciJobUrl
                ? <a href={data.ciJobUrl} className="text-primary hover:underline" target="_blank" rel="noreferrer">{data.ciPipelineId ?? 'job'}</a>
                : data.ciPipelineId ?? t.common.none}
            </dd>
          </dl>

          <h5 className="mt-3.5 mb-1 text-[12px] font-bold">{t.detail.diagnosis.environments}</h5>
          {failedChangeset && failedChangeset.environments.length > 0 ? (
            <>
              <ul className="space-y-1.5">
                {failedChangeset.environments.map((e) => (
                  <li key={e.environment} className="flex items-center gap-2 text-[12px]">
                    <EnvBadge code={e.environment} />
                    <StatusBadge tone={stateTone(e.status)}>{stateLabel(e.status)}</StatusBadge>
                    <span className="ml-auto text-text-3">{e.deployedAt ? formatShortDateTime(e.deployedAt, locale) : ''}</span>
                  </li>
                ))}
              </ul>
              {passedEverywhereElse && (
                <p className="mt-2 text-[12px] text-text-2">
                  {fill(t.detail.diagnosis.sameEverywhere, { env: data.environment })}
                </p>
              )}
            </>
          ) : (
            <p className="text-[12px] text-text-2">{t.detail.diagnosis.neverRan}</p>
          )}
        </div>
      </div>
    </Card>
  );
}

// ---------------------------------------------------------------- changesets

function ChangesetTable({ rows, loading }: { rows: OperationChangeset[]; loading: boolean }) {
  const t = useMessages();
  const locale = useLocale();
  const label = (outcome: string) =>
    (t.detail.changesets.outcome as Record<string, string>)[outcome] ?? outcome;

  const columns: Column<OperationChangeset>[] = [
    {
      key: 'changeset', header: t.detail.changesets.columns.changeset,
      cell: (row) => <span className="font-mono">{row.changesetId}</span>,
    },
    { key: 'author', header: t.detail.changesets.columns.author, cell: (row) => row.author },
    {
      key: 'result', header: t.detail.changesets.columns.result,
      cell: (row) => (
        <StatusBadge tone={outcomeTone(row.outcome)}>
          {row.outcome === 'FILTERED' && row.filterReason
            ? `${label(row.outcome)} (${row.filterReason})`
            : label(row.outcome)}
        </StatusBadge>
      ),
    },
    {
      key: 'release', header: t.detail.changesets.columns.release,
      cell: (row) => <span className="font-mono text-text-3">{row.release ?? t.common.none}</span>,
    },
    {
      key: 'time', header: t.detail.changesets.columns.time, align: 'right',
      cell: (row) => (
        <span className="text-text-3">
          {row.startedAt ? formatShortDateTime(row.startedAt, locale) : t.common.none}
        </span>
      ),
    },
  ];

  return (
    <DataTable
      columns={columns}
      rows={rows}
      rowKey={(row) => `${row.changesetId}-${row.author}-${row.filePath}`}
      loading={loading}
      rowClass={(row) => (row.outcome === 'FAILED' ? 'bg-row-danger' : undefined)}
      caption={t.detail.changesets.title}
    />
  );
}

// ------------------------------------------------------------------ log / sql

function TextTabs({ id, data }: { id: string; data: Detail }) {
  const t = useMessages();
  const [tab, setTab] = useState<'log' | 'sql'>('log');
  const text = useOperationText(id, tab, tab === 'log' ? data.hasLog : data.hasSql);
  const available = tab === 'log' ? data.hasLog : data.hasSql;

  return (
    <Card className="mb-6 overflow-hidden">
      <div role="tablist" className="flex gap-1 border-b border-border px-3 pt-2">
        {(['log', 'sql'] as const).map((name) => (
          <button
            key={name}
            role="tab"
            type="button"
            aria-selected={tab === name}
            onClick={() => setTab(name)}
            className={`px-3 py-2 text-[12px] font-semibold ${
              tab === name ? 'border-b-2 border-primary text-primary' : 'text-text-2 hover:text-text'
            }`}
          >
            {t.detail.tabs[name]}
          </button>
        ))}
      </div>
      <div role="tabpanel" className="p-3.5">
        {!available ? (
          <p className="text-text-2">{tab === 'log' ? t.detail.noLog : t.detail.noSqlTab}</p>
        ) : text.isLoading ? (
          <p className="text-text-2">{t.common.loading}</p>
        ) : text.isError && text.error instanceof ApiError && text.error.status === 404 ? (
          // The row says there is a blob but storage no longer has it: say so plainly,
          // instead of a generic error with a retry button that can never succeed.
          <p className="text-text-2">{tab === 'log' ? t.detail.noLog : t.detail.noSqlTab}</p>
        ) : text.isError ? (
          <ErrorCard message={t.detail.error} onRetry={() => text.refetch()} />
        ) : (
          <CodeBlock copyable>{text.data ?? ''}</CodeBlock>
        )}
      </div>
    </Card>
  );
}
