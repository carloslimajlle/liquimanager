import { useState } from 'react';

import { useMe } from '../../api/admin';
import { usePendingPromotions, usePromotionActions } from '../../api/matrix';
import { usePromotionChanges } from '../../api/changesets';
import type { Promotion } from '../../api/matrix';
import { useCan } from '../../auth/useCan';
import EnvBadge from '../../components/EnvBadge';
import { fill } from '../../i18n';
import { useLocale, useMessages } from '../../i18n/useMessages';
import { formatShortDateTime } from '../../lib/format';
import { ChangesetPeek } from '../projects/ChangesetSource';

const button = 'rounded-control border border-border px-2 py-0.5 text-[11.5px] font-semibold hover:bg-surface-alt disabled:opacity-50';

/**
 * Promotions waiting for a second person (a stage that requires approval). Whoever asked sees "Cancelar"; somebody else with
 * `promote:<env>` sees "Aprovar" and "Rejeitar". The API enforces both (SoD-2); this only shows what is theirs to do.
 */
export default function PendingPromotions({ pipelineId }: { pipelineId: string | undefined }) {
  const t = useMessages();
  const p = t.matrix.pending;
  const locale = useLocale();
  const can = useCan();
  const me = useMe();
  const pending = usePendingPromotions(pipelineId);
  const actions = usePromotionActions(pipelineId);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const rows = pending.data ?? [];
  if (rows.length === 0) {
    return null;
  }
  const mine = (promotion: Promotion) => !!me.data && !me.data.devMode && promotion.requestedBy === me.data.name;

  async function run(action: () => Promise<unknown>) {
    setBusy(true);
    setError(null);
    try {
      await action();
    } catch (err) {
      setError(err instanceof Error && err.message ? err.message : p.error);
    } finally {
      setBusy(false);
    }
  }

  return (
    <section aria-label={p.title} className="mb-4 rounded-card border border-border bg-surface p-4 shadow-card">
      <h2 className="text-[14px] font-semibold">{p.title}</h2>
      {error && <p role="alert" className="mt-2 text-[12.5px] text-danger">{error}</p>}
      <ul className="mt-3 flex flex-col gap-2">
        {rows.map((promotion) => (
          <li key={promotion.id} className="text-[12.5px]">
            <div className="flex flex-wrap items-center gap-2">
            <EnvBadge code={promotion.environment} />
            <span className="font-semibold">{promotion.release ? promotion.release.replace(/^release-/, '') : p.noRelease}</span>
            <span className="text-text-2">
              {fill(p.by, { name: promotion.requestedBy ?? '' })}
              {promotion.createdAt ? ` · ${formatShortDateTime(promotion.createdAt, locale)}` : ''}
            </span>
            <span className="ml-auto flex gap-2">
              {mine(promotion) ? (
                <button type="button" disabled={busy} className={button} onClick={() => run(() => actions.cancel(promotion.id))}>{p.cancel}</button>
              ) : can(`promote:${promotion.environment.toLowerCase()}`) && (
                <>
                  <button type="button" disabled={busy} className={button} onClick={() => run(() => actions.approve(promotion.id))}>{p.approve}</button>
                  <button type="button" disabled={busy} className={button} onClick={() => run(() => actions.reject(promotion.id))}>{p.reject}</button>
                </>
              )}
            </span>
            </div>
            <Changes promotion={promotion} />
          </li>
        ))}
      </ul>
      <p className="mt-3 text-[11.5px] text-text-3">{p.footnote}</p>
    </section>
  );
}

/** What approving this request would apply, each changeset one click from its definition: the point of approving is reading it. */
function Changes({ promotion }: { promotion: Promotion }) {
  const t = useMessages();
  const p = t.matrix.pending;
  const changes = usePromotionChanges(promotion.id);
  if (changes.isLoading || changes.isError) {
    return null;
  }
  const rows = changes.data ?? [];
  return (
    <div className="mt-2 ml-1 border-l-2 border-border pl-3">
      <div className="mb-1 text-[11.5px] font-semibold text-text-2">{fill(p.changesTitle, { env: promotion.environment })}</div>
      {rows.length === 0 ? (
        <p className="text-[12px] text-text-3">{p.changesNone}</p>
      ) : (
        <ul className="flex flex-col gap-1.5">
          {rows.map((change) => (
            <li key={change.id}>
              <ChangesetPeek id={change.id} label={change.changesetId} detail={`${change.author} · ${change.changelog}`} />
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
