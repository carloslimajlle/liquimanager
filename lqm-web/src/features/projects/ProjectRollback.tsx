import { useState } from 'react';
import { Link } from 'react-router-dom';

import { useProjectConnections } from '../../api/admin';
import { useAppliedChangesets, useConnectionRollbackRequests, useRollbackActions } from '../../api/rollback';
import type { RollbackItem } from '../../api/rollback';
import { useCan } from '../../auth/useCan';
import DataTable from '../../components/DataTable';
import type { Column } from '../../components/DataTable';
import EnvBadge from '../../components/EnvBadge';
import StatusBadge from '../../components/StatusBadge';
import { EmptyState, ErrorCard } from '../../components/States';
import { fill } from '../../i18n';
import { useLocale, useMessages } from '../../i18n/useMessages';
import { formatDateTime } from '../../lib/format';
import { RequestDialog, RollbackRequestList } from '../operations/RollbackPanel';

/**
 * The project's Rollback tab: pick a database, see what is applied on it newest first, and go back to a changeset from the list. Going
 * back undoes that changeset and everything applied after it; the dialog shows the exact list before anything is requested.
 */
export function RollbackTab({ projectId }: { projectId: string }) {
  const t = useMessages();
  const r = t.projects.rollbackTab;
  const locale = useLocale();
  const connections = useProjectConnections(projectId);
  const [chosen, setChosen] = useState('');
  const connection = chosen || connections.data?.[0]?.id || '';
  const current = connections.data?.find((item) => item.id === connection);
  const applied = useAppliedChangesets(connection);
  const requests = useConnectionRollbackRequests(connection);
  const actions = useRollbackActions();
  const can = useCan();
  const [target, setTarget] = useState<RollbackItem | null>(null);

  if (connections.isError) {
    return <ErrorCard message={r.loadError} onRetry={() => connections.refetch()} />;
  }
  if (connections.data && connections.data.length === 0) {
    return <EmptyState title={r.noConnections} hint={r.noConnectionsHint} />;
  }

  const allowed = current ? can(`rollback:${current.environmentCode.toLowerCase()}`) : false;
  const columns: Column<RollbackItem>[] = [
    {
      key: 'changeset', header: r.columns.changeset,
      cell: (row) => <Link to={`/changesets/${row.changesetUuid}`} className="font-mono font-semibold text-primary hover:underline">{row.changesetId}</Link>,
    },
    { key: 'author', header: r.columns.author, cell: (row) => row.author },
    {
      key: 'deployedAt', header: r.columns.deployedAt,
      cell: (row) => (row.deployedAt ? <span className="whitespace-nowrap">{formatDateTime(row.deployedAt, locale)}</span> : <span className="text-text-3">{t.common.none}</span>),
    },
    {
      key: 'rollback', header: r.columns.rollback,
      cell: (row) => (row.hasRollback
        ? <StatusBadge tone="success">{r.reversible}</StatusBadge>
        : <StatusBadge tone="danger">{t.rollback.dialog.noRollback}</StatusBadge>),
    },
    {
      key: 'action', header: '',
      cell: (row) => (
        <button type="button" disabled={!allowed || !row.operationId} title={allowed ? undefined : r.noPermission}
          onClick={(event) => { event.stopPropagation(); setTarget(row); }}
          className="whitespace-nowrap rounded-control border border-border px-2.5 py-1 text-[12px] font-semibold hover:bg-surface-alt disabled:opacity-50">
          {r.goBack}
        </button>
      ),
    },
  ];

  return (
    <div className="flex flex-col gap-4">
      <p className="max-w-[760px] text-[12.5px] text-text-2">{r.intro}</p>
      <label className="flex w-fit items-center gap-2 text-[12px] font-semibold">{r.connection}
        <select className="rounded-control border border-border bg-surface px-3 py-1.5 text-[13px] font-normal" value={connection}
          onChange={(event) => setChosen(event.target.value)}>
          {(connections.data ?? []).map((item) => (
            <option key={item.id} value={item.id}>{item.slug} · {item.environmentCode}</option>
          ))}
        </select>
        {current && <EnvBadge code={current.environmentCode} />}
      </label>

      <section>
        <h2 className="mb-2 font-bold">{fill(r.applied, { connection: current?.slug ?? '' })}</h2>
        {applied.isError ? (
          <ErrorCard message={r.loadError} onRetry={() => applied.refetch()} />
        ) : applied.data && applied.data.items.length === 0 ? (
          <EmptyState title={r.emptyApplied} />
        ) : (
          <DataTable columns={columns} rows={applied.data?.items ?? []} rowKey={(row) => row.changesetUuid} loading={applied.isLoading}
            caption={fill(r.applied, { connection: current?.slug ?? '' })} />
        )}
      </section>

      <section className="rounded-card border border-border bg-surface p-3.5 shadow-card">
        <h2 className="font-bold">{r.requests}</h2>
        <RollbackRequestList rows={requests.data ?? []} />
      </section>

      {target && target.operationId && (
        <RequestDialog
          operationId={target.operationId}
          fixed={target.changesetId}
          changesets={[]}
          onSubmit={(body) => actions.request(body, target.operationId ?? undefined)}
          onClose={() => setTarget(null)}
        />
      )}
    </div>
  );
}
