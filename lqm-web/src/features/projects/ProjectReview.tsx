import { useState } from 'react';
import { Link } from 'react-router-dom';

import { refOf, useInReview, useInReviewActions } from '../../api/inReview';
import type { ReviewChangeset, ReviewGroup } from '../../api/inReview';
import { useCan } from '../../auth/useCan';
import DataTable from '../../components/DataTable';
import type { Column } from '../../components/DataTable';
import StatusBadge from '../../components/StatusBadge';
import { EmptyState, ErrorCard } from '../../components/States';
import { fill } from '../../i18n';
import { useLocale, useMessages } from '../../i18n/useMessages';
import { formatDateTime } from '../../lib/format';

const MIN_NOTE = 5;

/**
 * The project's "In review" tab: the changesets that only a merge request (or a branch) has, grouped by merge request. They leave by
 * themselves when the merge request is merged (then they are in the changelog). LiquiManager is never told that one was closed or deleted,
 * so a person closes the group here, with a note.
 */
export function ReviewTab({ projectId }: { projectId: string }) {
  const t = useMessages();
  const r = t.projects.review;
  const [showClosed, setShowClosed] = useState(false);
  const review = useInReview(projectId, showClosed);
  const [closing, setClosing] = useState<ReviewGroup | null>(null);

  if (review.isError) {
    return <ErrorCard message={r.loadError} onRetry={() => review.refetch()} />;
  }
  const data = review.data;

  return (
    <div className="flex flex-col gap-4">
      <p className="max-w-[820px] text-[12.5px] text-text-2">{r.intro}</p>
      <label className="flex w-fit items-center gap-2 text-[12.5px]">
        <input type="checkbox" checked={showClosed} onChange={(event) => setShowClosed(event.target.checked)} />
        {fill(r.showClosed, { n: data?.closedCount ?? 0 })}
      </label>
      {data && data.groups.length === 0 ? (
        <EmptyState title={showClosed ? r.empty : r.emptyOpen} hint={r.emptyHint} />
      ) : (
        (data?.groups ?? []).map((group) => (
          <GroupCard key={group.mrIid ? `mr:${group.mrIid}` : `branch:${group.branch}`} projectId={projectId} group={group} onClose={() => setClosing(group)} />
        ))
      )}
      {closing && <CloseDialog projectId={projectId} group={closing} onDone={() => setClosing(null)} />}
    </div>
  );
}

function nameOf(group: ReviewGroup, r: { mr: string; branch: string }): string {
  return group.mrIid ? fill(r.mr, { iid: group.mrIid }) : fill(r.branch, { branch: group.branch ?? '' });
}

function GroupCard({ projectId, group, onClose }: { projectId: string; group: ReviewGroup; onClose: () => void }) {
  const t = useMessages();
  const r = t.projects.review;
  const locale = useLocale();
  const actions = useInReviewActions(projectId);
  const canEdit = useCan()('catalog:edit');
  const [error, setError] = useState<string | null>(null);

  const columns: Column<ReviewChangeset>[] = [
    {
      key: 'changeset', header: r.columns.changeset,
      cell: (row) => <Link to={`/changesets/${row.id}`} className="font-mono font-semibold text-primary hover:underline">{row.changesetId}</Link>,
    },
    { key: 'author', header: r.columns.author, cell: (row) => row.author },
    { key: 'file', header: r.columns.file, cell: (row) => <span className="font-mono text-text-2">{row.filePath}</span> },
    { key: 'release', header: r.columns.release, cell: (row) => row.release ?? <span className="text-text-3">{t.common.none}</span> },
    {
      key: 'rollback', header: r.columns.rollback,
      cell: (row) => (row.hasRollback ? <StatusBadge tone="success">{r.reversible}</StatusBadge> : <StatusBadge tone="danger">{t.rollback.dialog.noRollback}</StatusBadge>),
    },
  ];

  async function reopen() {
    setError(null);
    try {
      await actions.reopen(refOf(group));
    } catch (err) {
      setError(err instanceof Error && err.message ? err.message : r.error);
    }
  }

  const label = nameOf(group, r);
  return (
    <section className={`rounded-card border border-border bg-surface p-3.5 shadow-card ${group.closed ? 'opacity-80' : ''}`}>
      <div className="mb-2 flex flex-wrap items-center gap-2">
        {group.mrUrl
          ? <a href={group.mrUrl} target="_blank" rel="noreferrer" className="font-bold text-primary hover:underline">{label} ↗</a>
          : <b>{label}</b>}
        {group.mrTitle && <span className="text-text-2">{group.mrTitle}</span>}
        {group.mrIid && group.branch && <span className="font-mono text-[12px] text-text-3">{group.branch}</span>}
        <StatusBadge tone="info">{r.count(group.changesets.length)}</StatusBadge>
        {group.closed && <StatusBadge tone="neutral">{r.closed}</StatusBadge>}
        {group.lastSeenAt && <span className="text-[12px] text-text-3">{fill(r.lastReport, { when: formatDateTime(group.lastSeenAt, locale) })}</span>}
        <span className="ml-auto flex gap-2">
          {canEdit && !group.closed && (
            <button type="button" onClick={onClose}
              className="rounded-control border border-border px-3 py-1 text-[12px] font-semibold hover:bg-surface-alt">{r.close}</button>
          )}
          {canEdit && group.closed && (
            <button type="button" onClick={reopen}
              className="rounded-control border border-border px-3 py-1 text-[12px] font-semibold hover:bg-surface-alt">{r.reopen}</button>
          )}
        </span>
      </div>
      {group.closed && (
        <p className="mb-2 rounded-control bg-surface-alt px-3 py-2 text-[12.5px]">
          <b>{fill(r.closedBy, { who: group.closedBy ?? '', when: group.closedAt ? formatDateTime(group.closedAt, locale) : '' })}</b>{' '}
          {group.closedNote}
        </p>
      )}
      {error && <p role="alert" className="mb-2 text-[12.5px] text-danger">{error}</p>}
      <DataTable columns={columns} rows={group.changesets} rowKey={(row) => row.id} caption={label} />
    </section>
  );
}

function CloseDialog({ projectId, group, onDone }: { projectId: string; group: ReviewGroup; onDone: () => void }) {
  const t = useMessages();
  const r = t.projects.review;
  const actions = useInReviewActions(projectId);
  const [note, setNote] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const label = nameOf(group, r);

  async function submit() {
    setBusy(true);
    setError(null);
    try {
      await actions.close(refOf(group), note.trim());
      onDone();
    } catch (err) {
      setError(err instanceof Error && err.message ? err.message : r.error);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div role="dialog" aria-modal="true" aria-label={fill(r.dialog.title, { name: label })}
      className="fixed inset-0 z-50 flex items-center justify-center bg-text/30 p-6">
      <div className="flex w-[520px] flex-col gap-3 rounded-card border border-border bg-surface p-5 shadow-overlay">
        <div>
          <h2 className="text-[18px] font-bold">{fill(r.dialog.title, { name: label })}</h2>
          <p className="text-[12.5px] text-text-2">{r.dialog.hint(group.changesets.length)}</p>
        </div>
        <label className="flex flex-col gap-1 text-[12px] font-semibold">{r.dialog.note}
          <textarea rows={3} className="rounded-control border border-border p-2 text-[13px] font-normal" value={note}
            onChange={(event) => setNote(event.target.value)} />
          <span className="font-normal text-text-3">{fill(r.dialog.noteHint, { n: MIN_NOTE })}</span>
        </label>
        {error && <p role="alert" className="text-[12.5px] text-danger">{error}</p>}
        <div className="flex justify-end gap-2">
          <button type="button" onClick={onDone}
            className="rounded-control border border-border px-3 py-1.5 text-[12px] font-semibold hover:bg-surface-alt">{r.dialog.cancel}</button>
          <button type="button" disabled={note.trim().length < MIN_NOTE || busy} onClick={submit}
            className="rounded-control bg-primary px-3 py-1.5 text-[12px] font-semibold text-white disabled:opacity-50">{r.dialog.submit}</button>
        </div>
      </div>
    </div>
  );
}
