import StatusBadge from './StatusBadge';
import { fill } from '../i18n';
import { useMessages } from '../i18n/useMessages';

/** Says where a changeset that is not on the default branch lives: "only in MR !18" with the merge request's title, or the branch. */
export default function OriginBadge({ origin }: { origin?: { branch?: string | null; mrIid?: string | null; mrTitle?: string | null; mrUrl?: string | null } }) {
  const t = useMessages();
  const o = t.origin;
  const label = origin?.mrIid ? fill(o.onlyInMr, { iid: origin.mrIid }) : origin?.branch ? fill(o.onlyInBranch, { branch: origin.branch }) : o.notInMain;
  const badge = <StatusBadge tone="info">{label}</StatusBadge>;
  return (
    <span className="inline-flex flex-wrap items-center gap-1.5" title={o.hint}>
      {origin?.mrUrl ? <a href={origin.mrUrl} target="_blank" rel="noreferrer" className="hover:underline">{badge}</a> : badge}
      {origin?.mrTitle && <span className="text-text-3">{origin.mrTitle}</span>}
    </span>
  );
}
