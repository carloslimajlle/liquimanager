import { useState } from 'react';

import { usePromotionPreview } from '../../api/matrix';
import type { MatrixStage, Promotion } from '../../api/matrix';
import { fill } from '../../i18n';
import { useMessages } from '../../i18n/useMessages';

const field = 'rounded-control border border-border bg-surface px-3 py-1.5 text-[13px] font-normal';

/** "Promover para X" (screen 04): fires the GitLab trigger for the stage; the API decides whether the caller may. */
export default function PromoteDialog({ stage, pipelineId, releases, defaultRelease, onPromote, onClose }: {
  stage: MatrixStage;
  pipelineId: string | undefined;
  releases: string[];
  defaultRelease: string;
  onPromote: (stageId: string, release: string) => Promise<Promotion>;
  onClose: () => void;
}) {
  const t = useMessages();
  const p = t.matrix.promote;
  const [release, setRelease] = useState(defaultRelease);
  const preview = usePromotionPreview(pipelineId, stage.stageId);
  const pv = preview.data;
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState<Promotion | null>(null);

  async function submit() {
    setBusy(true);
    setError(null);
    try {
      setDone(await onPromote(stage.stageId, release));
    } catch (err) {
      setError(err instanceof Error && err.message ? err.message : p.error);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="fixed inset-0 z-20 flex items-center justify-center bg-black/30" role="presentation">
      <div role="dialog" aria-modal="true" aria-label={fill(p.title, { env: stage.env })}
        className="flex w-[420px] flex-col gap-3 rounded-card border border-border bg-surface p-4 shadow-card">
        <h2 className="text-[15px] font-bold">{fill(p.title, { env: stage.env })}</h2>
        <p className="text-[12.5px] text-text-2">{p.hint}</p>
        <label className="flex flex-col gap-1 text-[12px] font-semibold">
          {p.release}
          <select className={field} value={release} onChange={(event) => setRelease(event.target.value)} disabled={busy || !!done}>
            <option value="">{p.releaseNone}</option>
            {releases.map((value) => <option key={value} value={value}>{value.replace(/^release-/, '')}</option>)}
          </select>
          {releases.length === 0 && <span className="text-[12px] font-normal text-text-2">{p.releaseHint}</span>}
        </label>
        {pv?.gated && (
          <div className="flex flex-col gap-1.5 rounded-control border border-border bg-surface-alt p-2.5 text-[12.5px]" aria-label={p.preview.title}>
            <p><b>{p.preview.ready(pv.readyCount ?? 0, pv.previousEnv ?? '', stage.env)}</b></p>
            {(pv.editedCount ?? 0) > 0 && (
              <div role="alert" className="text-danger">
                <p>{p.preview.edited(pv.editedCount ?? 0, pv.previousEnv ?? '', stage.env)}</p>
                <ul className="ml-4 list-disc font-mono text-[12px]">
                  {(pv.edited ?? []).slice(0, 5).map((item) => <li key={item.id + item.filePath}>{item.id}</li>)}
                </ul>
              </div>
            )}
            {(pv.removedCount ?? 0) > 0 && (
              <div className="text-text-2">
                <p>{p.preview.removed(pv.removedCount ?? 0, pv.previousEnv ?? '')}</p>
                <ul className="ml-4 list-disc font-mono text-[12px]">
                  {(pv.removed ?? []).slice(0, 5).map((item) => <li key={item.id + item.filePath}>{item.id}</li>)}
                </ul>
              </div>
            )}
            {(pv.notInPreviousCount ?? 0) > 0 && (
              <div className="text-warning">
                <p>{p.preview.notInPrevious(pv.notInPreviousCount ?? 0, pv.previousEnv ?? '', stage.env)}</p>
                <ul className="ml-4 list-disc font-mono text-[12px]">
                  {(pv.notInPrevious ?? []).slice(0, 5).map((item) => <li key={item.id + item.filePath}>{item.id}</li>)}
                </ul>
              </div>
            )}
          </div>
        )}
        {error && <p role="alert" className="text-[12.5px] text-danger">{error}</p>}
        {done && (
          <p role="status" className="text-[12.5px] text-success">
            {done.status === 'REQUESTED' ? p.requested : p.done}{' '}
            {done.gitlabUrl && <a href={done.gitlabUrl} target="_blank" rel="noreferrer" className="font-semibold text-primary hover:underline">{p.openRun}</a>}
          </p>
        )}
        <div className="flex justify-end gap-2">
          <button type="button" onClick={onClose} className="rounded-control border border-border px-3 py-1.5 text-[12px] font-semibold hover:bg-surface-alt">
            {done ? p.close : p.cancel}
          </button>
          {!done && (
            <button type="button" onClick={submit} disabled={busy}
              className="rounded-control bg-primary px-3 py-1.5 text-[12px] font-semibold text-white disabled:opacity-50">{p.confirm}</button>
          )}
        </div>
      </div>
    </div>
  );
}
