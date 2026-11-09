import { useState } from 'react';

import { useExceptionActions, usePolicyExceptions } from '../../api/governance';
import type { EnforcementFinding, PolicyException } from '../../api/governance';
import StatusBadge from '../../components/StatusBadge';
import type { Tone } from '../../components/StatusBadge';
import { useCan } from '../../auth/useCan';
import { fill } from '../../i18n';
import { useLocale, useMessages } from '../../i18n/useMessages';
import { formatShortDateTime } from '../../lib/format';

/** The workspace limit is the API's to enforce; the dialog only offers a sensible range. */
const MAX_DAYS = 30;

const STATUS_TONE: Record<PolicyException['status'], Tone> = {
  REQUESTED: 'warning', APPROVED: 'success', REJECTED: 'danger', REVOKED: 'neutral', EXPIRED: 'neutral',
};

/** Open exceptions with the actions the API allows; the API refuses what separation of duties forbids. */
export function ExceptionsPanel() {
  const t = useMessages();
  const x = t.governance.exceptions;
  const locale = useLocale();
  const exceptions = usePolicyExceptions();
  const actions = useExceptionActions();
  const canDecide = useCan()('exception:approve');
  const [error, setError] = useState<string | null>(null);

  const open = (exceptions.data ?? []).filter((e) => e.status === 'REQUESTED' || e.status === 'APPROVED');

  async function run(action: () => Promise<unknown>) {
    setError(null);
    try {
      await action();
    } catch (err) {
      setError(err instanceof Error && err.message ? err.message : x.error);
    }
  }

  const button = 'rounded-control border border-border px-2 py-0.5 text-[11.5px] font-semibold hover:bg-surface-alt';

  return (
    <section className="rounded-card border border-border bg-surface p-4 shadow-card">
      <div className="flex items-center gap-2">
        <h2 className="text-[14px] font-semibold">{x.title}</h2>
        <span className="ml-auto"><StatusBadge tone="warning">{fill(x.badge, { n: open.length })}</StatusBadge></span>
      </div>
      {error && <p role="alert" className="mt-2 text-[12px] text-danger">{error}</p>}
      {open.length === 0 ? (
        <p className="mt-2 text-[12.5px] text-text-2">{x.none}</p>
      ) : (
        <ul className="mt-3 flex flex-col gap-3">
          {open.map((e) => (
            <li key={e.id} className="text-[12.5px]">
              <div className="flex items-center gap-2">
                <code className="font-mono text-[12px]">{e.check}</code>
                <StatusBadge tone={STATUS_TONE[e.status]}>{x.status[e.status]}</StatusBadge>
              </div>
              <div className="text-text-2">
                {e.changeset ?? x.scopeAll} · {fill(x.by, { name: e.requestedBy })} · {fill(x.until, { date: formatShortDateTime(e.expiresAt, locale) })}
              </div>
              <div className="mt-1 flex gap-2">
                {canDecide && e.status === 'REQUESTED' && (
                  <>
                    <button type="button" className={button} onClick={() => run(() => actions.approve(e.id))}>{x.approve}</button>
                    <button type="button" className={button} onClick={() => run(() => actions.reject(e.id))}>{x.reject}</button>
                  </>
                )}
                {canDecide && e.status === 'APPROVED' && (
                  <button type="button" className={button} onClick={() => run(() => actions.revoke(e.id))}>{x.revoke}</button>
                )}
              </div>
            </li>
          ))}
        </ul>
      )}
      <p className="mt-3 text-[11.5px] text-text-3">{x.footnote}</p>
    </section>
  );
}

/** "Solicitar exceção" for one finding: justification and a deadline, nothing else to decide. */
export function RequestExceptionDialog({
  finding, project, onClose,
}: { finding: EnforcementFinding; project?: string; onClose: () => void }) {
  const t = useMessages();
  const d = t.governance.exceptions.dialog;
  const actions = useExceptionActions();
  const [justification, setJustification] = useState('');
  const [days, setDays] = useState(7);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit() {
    setBusy(true);
    setError(null);
    try {
      await actions.request({
        check: finding.check,
        project,
        changeset: finding.changeset ?? undefined,
        environment: finding.env,
        justification,
        expiresAt: new Date(Date.now() + days * 86_400_000).toISOString(),
      });
      onClose();
    } catch (err) {
      setError(err instanceof Error && err.message ? err.message : t.governance.exceptions.error);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div role="dialog" aria-modal="true" aria-label={d.title}
      className="fixed inset-0 z-50 flex items-center justify-center bg-text/30 p-6">
      <div className="flex w-[520px] flex-col gap-3 rounded-card border border-border bg-surface p-5 shadow-overlay">
        <div>
          <h2 className="text-[18px] font-bold">{d.title}</h2>
          <p className="text-[12.5px] text-text-2">{d.subtitle}</p>
          <p className="mt-1 font-mono text-[12px]">{finding.check} · {finding.changeset ?? ''} · {finding.env}</p>
        </div>
        <label className="flex flex-col gap-1 text-[12px] font-semibold">
          {d.justification}
          <textarea
            value={justification}
            onChange={(e) => setJustification(e.target.value)}
            rows={4}
            className="rounded-control border border-border bg-surface p-2 text-[13px] font-normal"
          />
          <span className="font-normal text-text-3">{d.justificationHint}</span>
        </label>
        <label className="flex flex-col gap-1 text-[12px] font-semibold">
          {d.days}
          <input
            type="number"
            min={1}
            max={MAX_DAYS}
            value={days}
            onChange={(e) => setDays(Math.max(1, Math.min(MAX_DAYS, Number(e.target.value) || 1)))}
            className="w-24 rounded-control border border-border bg-surface px-2 py-1 text-[13px] font-normal"
          />
          <span className="font-normal text-text-3">{fill(d.daysHint, { max: MAX_DAYS })}</span>
        </label>
        {error && <p role="alert" className="text-[12.5px] text-danger">{error}</p>}
        <div className="flex justify-end gap-2">
          <button type="button" onClick={onClose}
            className="rounded-control border border-border px-3 py-1.5 text-[12px] font-semibold hover:bg-surface-alt">{d.cancel}</button>
          <button type="button" onClick={submit} disabled={busy || justification.trim().length < 20}
            className="rounded-control bg-primary px-3 py-1.5 text-[12px] font-semibold text-white disabled:opacity-50">{d.submit}</button>
        </div>
      </div>
    </div>
  );
}
