import { useState } from 'react';
import { Link } from 'react-router-dom';

import type { OperationChangeset } from '../../api/operations';
import { useRollbackActions, useRollbackPreview, useRollbackRequests } from '../../api/rollback';
import { useCan } from '../../auth/useCan';
import type { RollbackItem, RollbackRequest, RollbackStatus } from '../../api/rollback';
import StatusBadge from '../../components/StatusBadge';
import type { Tone } from '../../components/StatusBadge';
import { fill } from '../../i18n';
import { useLocale, useMessages } from '../../i18n/useMessages';
import { formatDateTime } from '../../lib/format';

const TONE: Record<RollbackStatus, Tone> = {
  REQUESTED: 'warning', APPROVED: 'info', REJECTED: 'danger', CANCELED: 'neutral', RUNNING: 'info', DONE: 'success', FAILED: 'danger',
};

/** Rollbacks asked for on this operation, with what each person can do about them; the API refuses what SoD forbids. */
export default function RollbackPanel({ operationId, changesets }: { operationId: string; changesets: OperationChangeset[] }) {
  const t = useMessages();
  const r = t.rollback;
  const requests = useRollbackRequests(operationId);
  const actions = useRollbackActions(operationId);
  const [open, setOpen] = useState(false);

  return (
    <section className="mb-3 rounded-card border border-border bg-surface p-3.5 shadow-card">
      <div className="flex items-center">
        <h2 className="font-bold">{r.title}</h2>
        <button type="button" onClick={() => setOpen(true)}
          className="ml-auto rounded-control bg-primary px-3 py-1 text-[12px] font-semibold text-white">{r.request}</button>
      </div>
      <RollbackRequestList rows={requests.data ?? []} />
      {open && (
        <RequestDialog
          operationId={operationId}
          changesets={changesets.filter((c) => c.outcome === 'EXECUTED')}
          onSubmit={(body) => actions.request(body)}
          onClose={() => setOpen(false)}
        />
      )}
    </section>
  );
}

/** The requests, each with the list it will undo and what this person can do about it (approve, reject, cancel). */
export function RollbackRequestList({ rows }: { rows: RollbackRequest[] }) {
  const t = useMessages();
  const r = t.rollback;
  const actions = useRollbackActions();
  const canApprove = useCan()('rollback:approve');
  const [error, setError] = useState<string | null>(null);

  async function attempt(action: () => Promise<unknown>) {
    setError(null);
    try {
      await action();
    } catch (err) {
      setError(err instanceof Error && err.message ? err.message : r.error);
    }
  }

  const button = 'rounded-control border border-border px-2 py-0.5 text-[11.5px] font-semibold hover:bg-surface-alt';

  return (
    <>
      {error && <p role="alert" className="mt-2 text-[12.5px] text-danger">{error}</p>}
      {rows.length === 0 ? (
        <p className="mt-2 text-[12.5px] text-text-2">{r.none}</p>
      ) : (
        <ul className="mt-3 flex flex-col gap-3">
          {rows.map((request) => (
            <li key={request.id} className="text-[12.5px]">
              <div className="flex items-center gap-2">
                <StatusBadge tone={TONE[request.status]}>{r.status[request.status]}</StatusBadge>
                <span>
                  {fill(r.line, {
                    what: request.kind === 'TAG' ? fill(r.byTag, { tag: request.tag ?? '' }) : fill(r.toChangeset, { id: request.changeset ?? '' }),
                    requester: request.requestedBy,
                  })}
                </span>
                {request.count !== undefined && <span className="text-text-3">· {fill(r.count, { n: request.count })}</span>}
                {request.approvedBy && <span className="text-text-3">{fill(r.approvedBy, { name: request.approvedBy })}</span>}
              </div>
              <p className="text-text-2">{request.reason}</p>
              {request.plan && request.plan.length > 0 && (
                <details className="mt-1">
                  <summary className="cursor-pointer text-[12px] font-semibold text-primary">{r.plan}</summary>
                  <UndoList items={request.plan} />
                </details>
              )}
              {request.error && <p className="text-danger">{request.error}</p>}
              <div className="mt-1 flex gap-2">
                {canApprove && request.status === 'REQUESTED' && (
                  <>
                    <button type="button" className={button} onClick={() => attempt(() => actions.approve(request.id))}>{r.approve}</button>
                    <button type="button" className={button} onClick={() => attempt(() => actions.reject(request.id))}>{r.reject}</button>
                  </>
                )}
                {(request.status === 'REQUESTED' || request.status === 'APPROVED') && (
                  <button type="button" className={button} onClick={() => attempt(() => actions.cancel(request.id))}>{r.cancel}</button>
                )}
                {request.resultOperationId && (
                  <Link to={`/operations/${request.resultOperationId}`} className="text-[12px] font-semibold text-primary hover:underline">
                    {r.openResult}
                  </Link>
                )}
              </div>
            </li>
          ))}
        </ul>
      )}
    </>
  );
}

/** `fixed`: the changeset to go back to is already chosen (from a list), so it is shown instead of asked, and there is no tag option. */
export function RequestDialog({
  operationId, changesets, fixed, onSubmit, onClose,
}: {
  operationId: string;
  changesets: OperationChangeset[];
  fixed?: string;
  onSubmit: (body: { changeset?: string; tag?: string; reason: string }) => Promise<RollbackRequest>;
  onClose: () => void;
}) {
  const t = useMessages();
  const d = t.rollback.dialog;
  const [mode, setMode] = useState<'changeset' | 'tag'>('changeset');
  const [changeset, setChangeset] = useState(fixed ?? '');
  const [tag, setTag] = useState('');
  const [reason, setReason] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  // Going back to a changeset undoes it and everything after it: the list is asked for before anything is requested.
  const preview = useRollbackPreview(operationId, mode === 'changeset' ? changeset : '');
  const unreversible = mode === 'changeset' && preview.data?.reversible === false;
  const ready = reason.trim().length >= 10 && !unreversible
    && (mode === 'changeset' ? changeset !== '' && preview.data !== undefined : tag.trim() !== '');

  async function submit() {
    setBusy(true);
    setError(null);
    try {
      await onSubmit(mode === 'changeset' ? { changeset, reason: reason.trim() } : { tag: tag.trim(), reason: reason.trim() });
      onClose();
    } catch (err) {
      setError(err instanceof Error && err.message ? err.message : t.rollback.error);
    } finally {
      setBusy(false);
    }
  }

  const field = 'rounded-control border border-border bg-surface px-3 py-1.5 text-[13px]';

  return (
    <div role="dialog" aria-modal="true" aria-label={d.title} className="fixed inset-0 z-50 flex items-center justify-center bg-text/30 p-6">
      <div className="flex max-h-[90vh] w-[600px] flex-col gap-3 overflow-y-auto rounded-card border border-border bg-surface p-5 shadow-overlay">
        <div>
          <h2 className="text-[18px] font-bold">{d.title}</h2>
          <p className="text-[12.5px] text-text-2">{d.subtitle}</p>
        </div>
        {fixed === undefined && <div role="radiogroup" className="flex gap-4 text-[13px]">
          <label className="flex items-center gap-1.5"><input type="radio" checked={mode === 'changeset'} onChange={() => setMode('changeset')} /> {d.byChangeset}</label>
          <label className="flex items-center gap-1.5"><input type="radio" checked={mode === 'tag'} onChange={() => setMode('tag')} /> {d.byTag}</label>
        </div>}
        {fixed !== undefined ? (
          <p className="text-[13px]"><span className="font-semibold">{d.changeset}:</span> <span className="font-mono">{fixed}</span></p>
        ) : mode === 'changeset' ? (
          <label className="flex flex-col gap-1 text-[12px] font-semibold">{d.changeset}
            <select className={field} value={changeset} onChange={(e) => setChangeset(e.target.value)}>
              <option value="" />
              {changesets.map((c) => (
                <option key={c.changesetId} value={c.changesetId}>{c.changesetId}</option>
              ))}
            </select>
            <span className="font-normal text-text-3">{d.changesetHint}</span>
          </label>
        ) : (
          <label className="flex flex-col gap-1 text-[12px] font-semibold">{d.tag}
            <input className={`${field} font-mono`} value={tag} onChange={(e) => setTag(e.target.value)} />
          </label>
        )}
        {mode === 'changeset' && changeset !== '' && <UndoPreview preview={preview} />}
        <label className="flex flex-col gap-1 text-[12px] font-semibold">{d.reason}
          <textarea rows={3} className="rounded-control border border-border p-2 text-[13px] font-normal" value={reason}
            onChange={(e) => setReason(e.target.value)} />
          <span className="font-normal text-text-3">{d.reasonHint}</span>
        </label>
        {error && <p role="alert" className="text-[12.5px] text-danger">{error}</p>}
        <div className="flex justify-end gap-2">
          <button type="button" onClick={onClose}
            className="rounded-control border border-border px-3 py-1.5 text-[12px] font-semibold hover:bg-surface-alt">{d.cancel}</button>
          <button type="button" disabled={!ready || busy} onClick={submit}
            className="rounded-control bg-primary px-3 py-1.5 text-[12px] font-semibold text-white disabled:opacity-50">{d.submit}</button>
        </div>
      </div>
    </div>
  );
}

/** What the request would undo, in the order it is undone: the person sees it before asking, and the approver sees it frozen. */
function UndoList({ items }: { items: RollbackItem[] }) {
  const t = useMessages();
  const d = t.rollback.dialog;
  const locale = useLocale();
  return (
    <ol className="mt-1 flex flex-col gap-1">
      {items.map((item, index) => (
        <li key={item.changesetUuid} className="flex flex-wrap items-center gap-2 rounded-control border border-border bg-surface-alt px-2.5 py-1.5 text-[12.5px]">
          <span className="w-5 shrink-0 text-right font-mono text-text-3">{index + 1}.</span>
          <span className="font-mono font-semibold">{item.changesetId}</span>
          <span className="text-text-3">{fill(d.by, { author: item.author })}</span>
          {item.deployedAt && <span className="text-text-3">· {formatDateTime(item.deployedAt, locale)}</span>}
          {item.target && <StatusBadge tone="info">{d.target}</StatusBadge>}
          {!item.hasRollback && <StatusBadge tone="danger">{d.noRollback}</StatusBadge>}
        </li>
      ))}
    </ol>
  );
}

function UndoPreview({ preview }: { preview: ReturnType<typeof useRollbackPreview> }) {
  const t = useMessages();
  const d = t.rollback.dialog;
  if (preview.isPending) {
    return <p role="status" className="text-[12.5px] text-text-2">{d.previewLoading}</p>;
  }
  if (preview.isError || !preview.data) {
    return <p role="alert" className="text-[12.5px] text-danger">{d.previewError}</p>;
  }
  const { items, total, reversible, blockers } = preview.data;
  return (
    <div>
      <p className="text-[12.5px] font-semibold">{total === 1 ? d.previewOne : fill(d.previewTitle, { n: String(total) })}</p>
      <UndoList items={items} />
      {!reversible && <p role="alert" className="mt-2 text-[12.5px] text-danger">{fill(d.blocked, { list: blockers.join(', ') })}</p>}
    </div>
  );
}
