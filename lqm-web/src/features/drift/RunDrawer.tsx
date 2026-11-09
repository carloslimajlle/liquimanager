import { useState } from 'react';
import { Link } from 'react-router-dom';

import { downloadDriftChangelog, useDriftActions, useDriftRun } from '../../api/drift';
import { useCan } from '../../auth/useCan';
import type { DriftItem } from '../../api/drift';
import EnvBadge from '../../components/EnvBadge';
import StatusBadge from '../../components/StatusBadge';
import type { Tone } from '../../components/StatusBadge';
import { fill } from '../../i18n';
import { useLocale, useMessages } from '../../i18n/useMessages';
import { formatDateTime } from '../../lib/format';
import { differences } from './rules';

const CHANGE_TONE: Record<DriftItem['change'], Tone> = { ADDED: 'success', REMOVED: 'danger', MODIFIED: 'warning' };

/** A run in detail: its items grouped by type, and what can be done about them. */
export default function RunDrawer({ runId, onClose }: { runId: string; onClose: () => void }) {
  const t = useMessages();
  const d = t.drift.drawer;
  const locale = useLocale();
  const run = useDriftRun(runId);
  const actions = useDriftActions();
  const can = useCan();
  const [accepting, setAccepting] = useState<string | null>(null);
  const [reason, setReason] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const data = run.data;

  async function attempt(action: () => Promise<unknown>) {
    setError(null);
    setBusy(true);
    try {
      await action();
    } catch (err) {
      setError(err instanceof Error && err.message ? err.message : d.error);
    } finally {
      setBusy(false);
    }
  }

  const groups = new Map<string, DriftItem[]>();
  (data?.items ?? []).forEach((item) => groups.set(item.objectType, [...(groups.get(item.objectType) ?? []), item]));

  const button = 'rounded-control border border-border px-3 py-1.5 text-[12px] font-semibold hover:bg-surface-alt disabled:opacity-50';

  return (
    <div role="dialog" aria-modal="true" aria-label={d.title} className="fixed inset-0 z-50 flex justify-end bg-text/30">
      <div className="flex h-full w-[560px] flex-col gap-3 overflow-auto bg-surface p-5 shadow-overlay">
        <div className="flex items-start gap-2">
          <div>
            <h2 className="text-[18px] font-bold">{d.title}</h2>
            {data && (
              <p className="flex items-center gap-2 text-[12.5px] text-text-2">
                <code className="font-mono">{data.connection}</code> <EnvBadge code={data.environment} />
                {formatDateTime(data.startedAt, locale)}
              </p>
            )}
          </div>
          <button type="button" onClick={onClose} className={`${button} ml-auto`}>{d.close}</button>
        </div>

        {data?.status === 'FAILED' && (
          <p role="alert" className="rounded-control bg-danger-bg px-3 py-2 text-[12.5px] text-danger">
            {fill(d.failed, { error: data.error ?? '' })}
          </p>
        )}
        {data?.reference && <p className="text-[12px] text-text-3">{fill(d.reference, { reference: (d.referenceKinds as Record<string, string>)[data.reference] ?? data.reference })}</p>}
        {error && <p role="alert" className="text-[12.5px] text-danger">{error}</p>}

        {data && data.items.length === 0 && data.status === 'SUCCESS' && <p className="text-[13px] text-text-2">{d.noItems}</p>}

        {Array.from(groups.entries()).map(([type, items]) => (
          <section key={type}>
            <h3 className="mb-1 text-[11px] font-semibold tracking-[0.08em] text-text-2 uppercase">
              {t.drift.objectTypes[type as keyof typeof t.drift.objectTypes] ?? type} · {items.length}
            </h3>
            <ul className="flex flex-col gap-2">
              {items.map((item) => (
                <li key={item.id} className="rounded-control border border-border p-2.5 text-[12.5px]">
                  <div className="flex items-center gap-2">
                    <StatusBadge tone={CHANGE_TONE[item.change]}>{d.changes[item.change]}</StatusBadge>
                    <code className="font-mono text-[12px]">{item.objectName}</code>
                    {item.accepted && <span className="ml-auto text-[11.5px] text-text-3">{fill(d.accepted, { name: item.acceptedBy ?? '' })}</span>}
                  </div>
                  {item.change === 'MODIFIED' && differences(item.details).map((diff) => (
                    <div key={diff.field} className="mt-1 font-mono text-[11.5px] text-text-2">
                      {diff.field}: {d.before} <b>{diff.before}</b> → {d.after} <b>{diff.after}</b>
                    </div>
                  ))}
                  {item.accepted && item.acceptedReason && <p className="mt-1 text-text-3">{item.acceptedReason}</p>}
                  {!item.accepted && accepting !== item.id && can('drift:accept') && (
                    <button type="button" onClick={() => { setAccepting(item.id); setReason(''); }}
                      className="mt-1.5 text-[12px] font-semibold text-primary hover:underline">{d.accept}</button>
                  )}
                  {accepting === item.id && (
                    <div className="mt-2 flex flex-col gap-1.5">
                      <label className="text-[12px] font-semibold">{d.acceptReason}
                        <textarea value={reason} onChange={(e) => setReason(e.target.value)} rows={2}
                          className="mt-1 w-full rounded-control border border-border p-2 text-[12.5px] font-normal" />
                      </label>
                      <span className="text-[11.5px] text-text-3">{d.acceptHint}</span>
                      <div className="flex gap-2">
                        <button type="button" disabled={busy || reason.trim().length < 10}
                          onClick={() => attempt(async () => { await actions.accept(item.id, reason.trim()); setAccepting(null); })}
                          className="rounded-control bg-primary px-3 py-1 text-[12px] font-semibold text-white disabled:opacity-50">{d.acceptSubmit}</button>
                        <button type="button" onClick={() => setAccepting(null)} className={button}>{d.cancel}</button>
                      </div>
                    </div>
                  )}
                </li>
              ))}
            </ul>
          </section>
        ))}

        {data && (
          <div className="mt-auto flex flex-wrap gap-2 border-t border-border pt-3">
            {data.items.some((i) => !i.accepted) && (
              <button type="button" disabled={busy} className={button} onClick={() => attempt(() => downloadDriftChangelog(data.id))}>
                {d.generate}
              </button>
            )}
            {data.operationId && (
              <Link to={`/operations/${data.operationId}`} className={button}>{d.openOperation}</Link>
            )}
            {can('drift:run') && (
              <button type="button" disabled={busy} className={button} onClick={() => attempt(() => actions.runNow(data.connectionId))}>
                {d.runAgain}
              </button>
            )}
            {can('drift:accept') && (
              <button type="button" disabled={busy} className={button}
                onClick={() => window.confirm(d.confirmRebaseline) && attempt(() => actions.rebaseline(data.connectionId))}>
                {d.rebaseline}
              </button>
            )}
          </div>
        )}
      </div>
    </div>
  );
}
