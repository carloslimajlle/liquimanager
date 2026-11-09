import { Link } from 'react-router-dom';

import type { TimelineEvent } from '../../api/matrix';
import StatusBadge from '../../components/StatusBadge';
import { EmptyState, ErrorCard } from '../../components/States';
import { fill } from '../../i18n';
import { useLocale, useMessages } from '../../i18n/useMessages';
import { formatDay, formatTime } from '../../lib/format';
import { groupByDay } from './rules';

/** The Timeline mode of screen 04: deploys and promotions of a pipeline, a day at a time. */
export default function Timeline({ events, release, error, onRetry }: {
  events: TimelineEvent[] | undefined;
  release: string;
  error: boolean;
  onRetry: () => void;
}) {
  const t = useMessages();
  const locale = useLocale();
  const tl = t.matrix.timeline;

  if (error) {
    return <ErrorCard message={tl.error} onRetry={onRetry} />;
  }
  if (!events) {
    return <div aria-hidden className="m-3.5 h-24 animate-pulse rounded bg-border" />;
  }
  const days = groupByDay(events, release);
  if (days.length === 0) {
    return <EmptyState title={tl.empty} hint={tl.emptyHint} />;
  }

  return (
    <div className="flex flex-col gap-5 p-3.5">
      {days.map(({ day, events: list }) => (
        <section key={day} aria-label={formatDay(day, locale)}>
          <h2 className="mb-2 text-[12px] font-semibold tracking-[0.06em] text-text-2 uppercase">{formatDay(day, locale)}</h2>
          <ol className="flex flex-col border-l-2 border-border pl-4">
            {list.map((event, index) => (
              <li key={`${event.kind}-${event.at}-${event.environment}-${index}`} className="relative py-1.5 text-[13px]">
                <span aria-hidden className="absolute top-3 -left-[21px] h-2 w-2 rounded-full border-2 border-surface bg-primary" />
                <span className="mr-2 font-mono text-[12px] text-text-3">{formatTime(event.at, locale)}</span>
                <Sentence event={event} />
              </li>
            ))}
          </ol>
        </section>
      ))}
    </div>
  );
}

function Sentence({ event }: { event: TimelineEvent }) {
  const t = useMessages();
  const tl = t.matrix.timeline;
  const env = event.environment;
  const release = event.release?.replace(/^release-/, '') ?? '';
  const n = event.changesets ?? 0;
  const count = n === 1 ? tl.changesetOne : fill(tl.changesetMany, { n: String(n) });

  const text = event.kind === 'PROMOTION'
    ? fill(release ? tl.promotionRelease : tl.promotion, { release, env })
    : event.release
      ? fill(tl.deploy, { release, env, count })
      : fill(tl.deployNoRelease, { env, count });

  return (
    <>
      <span>{text}</span>
      <span className="text-text-2"> · {fill(tl.by, { name: event.by })}</span>
      {event.ciPipelineId && <span className="ml-1 font-mono text-[12px] text-text-3">#{event.ciPipelineId}</span>}
      {event.status === 'FAILED' && <span className="ml-2"><StatusBadge tone="danger">{`✕ ${tl.promotionFailed}`}</StatusBadge></span>}
      {event.status === 'REQUESTED' && <span className="ml-2"><StatusBadge tone="info">{tl.promotionRequested}</StatusBadge></span>}
      {event.status === 'REJECTED' && <span className="ml-2"><StatusBadge tone="neutral">{tl.promotionRejected}</StatusBadge></span>}
      {event.status === 'CANCELED' && <span className="ml-2"><StatusBadge tone="neutral">{tl.promotionCanceled}</StatusBadge></span>}
      {event.status === 'EXPIRED' && <span className="ml-2"><StatusBadge tone="neutral">{tl.promotionExpired}</StatusBadge></span>}
      {event.outOfProcess && <span className="ml-2"><StatusBadge tone="warning">{tl.outOfProcess}</StatusBadge></span>}
      {event.operationId && (
        <Link to={`/operations/${event.operationId}`} className="ml-2 text-[12px] font-semibold text-primary hover:underline">
          {tl.openOperation}
        </Link>
      )}
      {event.kind === 'PROMOTION' && event.url && (
        <a href={event.url} target="_blank" rel="noreferrer" className="ml-2 text-[12px] font-semibold text-primary hover:underline">
          {tl.openRun}
        </a>
      )}
    </>
  );
}
