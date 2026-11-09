import { useState } from 'react';
import { LogOut, ShieldCheck } from 'lucide-react';

import { useAskAccess, useMyAccessRequest } from '../../api/accessRequests';
import type { Me } from '../../api/admin';
import { authEnabled, signOut } from '../../auth/auth';
import { fill } from '../../i18n';
import { useLocale, useMessages } from '../../i18n/useMessages';
import { formatDateTime } from '../../lib/format';

const DAY_MS = 24 * 60 * 60 * 1000;

/**
 * What a person sees when they have signed in but nobody has granted them anything yet (the default is no access, by design). Instead of
 * screens that stay empty, it says why, shows the login and groups an administrator needs, and lets them ASK: the request reaches whoever
 * administers access in the bell, with no e-mail to write. Asking grants nothing; the administrator chooses the role and the scope.
 */
export default function NoAccess({ me }: { me: Me }) {
  const t = useMessages();
  const w = t.welcome;
  const locale = useLocale();
  const groups = me.groups ?? [];
  const mine = useMyAccessRequest();
  const ask = useAskAccess();
  const [message, setMessage] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const asked = mine.data?.id ? mine.data : null;
  const canRemind = asked?.requestedAt ? Date.now() - new Date(asked.requestedAt).getTime() > DAY_MS : false;

  async function submit() {
    setBusy(true);
    setError(null);
    try {
      await ask(message);
      setMessage('');
    } catch (err) {
      setError(err instanceof Error && err.message ? err.message : w.error);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="grid min-h-full place-items-center p-8">
      <section aria-labelledby="welcome-title" className="flex w-[560px] max-w-full flex-col gap-4 rounded-card border border-border bg-surface p-7 shadow-card">
        <div className="flex items-center gap-3">
          <span className="grid h-10 w-10 place-items-center rounded-full bg-info-bg text-info"><ShieldCheck size={20} strokeWidth={2} aria-hidden /></span>
          <div>
            <h1 id="welcome-title" className="text-[20px] font-bold">{w.title}</h1>
            <p className="text-[13px] text-text-2">{fill(w.signedAs, { name: me.name })}</p>
          </div>
        </div>
        <p className="text-[13.5px]">{w.noAccess}</p>

        <div className="rounded-control border border-border bg-surface-alt p-3.5 text-[13px]">
          <p className="mb-2 font-semibold">{w.whatTheyNeed}</p>
          <dl className="grid grid-cols-[88px_1fr] gap-x-3 gap-y-1.5">
            <dt className="text-text-2">{w.login}</dt>
            <dd className="font-mono">{me.name}</dd>
            {me.email && (
              <>
                <dt className="text-text-2">{w.email}</dt>
                <dd>{me.email}</dd>
              </>
            )}
            <dt className="text-text-2">{w.groups}</dt>
            <dd className="flex flex-wrap gap-1.5">
              {groups.length > 0
                ? groups.map((group) => <span key={group} className="rounded-badge bg-surface px-2 py-0.5 font-mono text-[12px] ring-1 ring-border">{group}</span>)
                : <span className="text-text-3">{w.noGroups}</span>}
            </dd>
          </dl>
          {groups.length === 0 && <p className="mt-2 text-[12.5px] text-text-2">{w.noGroupsHint}</p>}
        </div>

        {asked ? (
          <div role="status" className="rounded-control border border-border bg-info-bg p-3.5 text-[13px]">
            <p className="font-semibold">{fill(w.sent, { when: asked.requestedAt ? formatDateTime(asked.requestedAt, locale) : '' })}</p>
            <p className="mt-1 text-text-2">{w.sentHint}</p>
            {asked.message && <p className="mt-2 text-text-2">&ldquo;{asked.message}&rdquo;</p>}
            {canRemind && (
              <button type="button" disabled={busy} onClick={submit}
                className="mt-2 rounded-control border border-border bg-surface px-3 py-1 text-[12px] font-semibold hover:bg-surface-alt disabled:opacity-50">{w.remind}</button>
            )}
          </div>
        ) : (
          <div className="flex flex-col gap-2">
            <label className="flex flex-col gap-1 text-[12px] font-semibold">{w.messageLabel}
              <textarea rows={3} maxLength={500} value={message} onChange={(event) => setMessage(event.target.value)} placeholder={w.messagePlaceholder}
                className="rounded-control border border-border bg-surface p-2 text-[13px] font-normal" />
            </label>
            <div>
              <button type="button" disabled={busy} onClick={submit}
                className="rounded-control bg-primary px-3.5 py-1.5 text-[12.5px] font-semibold text-white disabled:opacity-50">{w.ask}</button>
            </div>
          </div>
        )}
        {error && <p role="alert" className="text-[12.5px] text-danger">{error}</p>}
        {authEnabled && (
          <div>
            <button type="button" onClick={() => void signOut()}
              className="inline-flex items-center gap-1.5 rounded-control border border-border px-3.5 py-1.5 text-[12.5px] font-semibold hover:bg-surface-alt">
              <LogOut size={14} strokeWidth={2} aria-hidden />{w.signOut}
            </button>
          </div>
        )}
        {!asked && <p className="text-[12px] text-text-3">{w.afterGrant}</p>}
      </section>
    </div>
  );
}
