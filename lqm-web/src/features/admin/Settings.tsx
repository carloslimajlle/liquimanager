import { useState } from 'react';

import { useAdminActions, useMe, useWorkspaces } from '../../api/admin';
import type { Workspace } from '../../api/admin';
import { useCan } from '../../auth/useCan';
import { Field, fieldClass, messageOf, primaryButton } from '../../components/FormDialog';
import PageHeader from '../../components/PageHeader';
import StatusBadge from '../../components/StatusBadge';
import { ErrorCard } from '../../components/States';
import { useLocale, useMessages } from '../../i18n/useMessages';
import { formatDateTime } from '../../lib/format';
import Topbar from '../../shell/Topbar';

/**
 * /settings: the workspace in use, its retention and deadlines, and who the API thinks you are. AI is off by decision D-13,
 * so this screen tells and does not pretend.
 */
export default function Settings() {
  const t = useMessages();
  const s = t.admin.settings;
  const locale = useLocale();
  const workspaces = useWorkspaces();
  const me = useMe();
  // A request with no X-LQM-Workspace header is the `default` workspace (docs/09 § 8): that is the one in use here.
  const workspace = workspaces.data?.find((item) => item.slug === 'default') ?? workspaces.data?.[0];

  const card = 'rounded-card border border-border bg-surface p-4 shadow-card';
  const term = 'text-[12px] font-semibold text-text-2';

  return (
    <>
      <Topbar trail={[t.admin.breadcrumbRoot, s.breadcrumb]} />
      <PageHeader title={s.title} subtitle={s.subtitle} />
      <div className="grid grid-cols-2 gap-3.5">
        <section className={card}>
          <h2 className="mb-3 text-[14px] font-semibold">{s.workspace}</h2>
          {workspaces.isError ? (
            <ErrorCard message={t.admin.common.loadError} onRetry={() => workspaces.refetch()} />
          ) : workspace && (
            <dl className="grid grid-cols-[120px_1fr] gap-y-2 text-[13px]">
              <dt className={term}>{t.admin.common.name}</dt><dd>{workspace.name}</dd>
              <dt className={term}>{s.slug}</dt><dd><code className="font-mono text-[12px]">{workspace.slug}</code></dd>
              <dt className={term}>{s.created}</dt><dd>{formatDateTime(workspace.createdAt, locale)}</dd>
              <dt className={term}>{s.ai}</dt>
              <dd><StatusBadge tone={workspace.aiEnabled ? 'info' : 'neutral'}>{workspace.aiEnabled ? t.admin.common.on : s.aiOff}</StatusBadge></dd>
            </dl>
          )}
          <p className="mt-3 text-[12px] text-text-3">{s.aiNote}</p>
          {workspace && <RetentionForm key={workspace.id} workspace={workspace} />}
        </section>

        <section className={card}>
          <h2 className="mb-1 text-[14px] font-semibold">{s.identity}</h2>
          <p className="mb-3 text-[12px] text-text-3">{s.identityNote}</p>
          {me.isError ? (
            <ErrorCard message={t.admin.common.loadError} onRetry={() => me.refetch()} />
          ) : me.data && (
            <dl className="grid grid-cols-[120px_1fr] gap-y-2 text-[13px]">
              <dt className={term}>{s.user}</dt>
              <dd>{me.data.devMode ? s.anonymous : me.data.name}{me.data.email ? ` · ${me.data.email}` : ''}</dd>
              <dt className={term}>{s.groups}</dt>
              <dd>{me.data.groups && me.data.groups.length > 0 ? me.data.groups.join(', ') : t.admin.common.none}</dd>
              <dt className={term}>{s.permissions}</dt>
              <dd className="flex flex-wrap gap-1">
                {me.data.permissions.map((permission) => (
                  <code key={permission} className="rounded-badge bg-surface-alt px-1.5 py-0.5 font-mono text-[11.5px]">{permission}</code>
                ))}
              </dd>
            </dl>
          )}
        </section>
      </div>
    </>
  );
}

/** What the housekeeping jobs keep, and how long a promotion request waits. Only `workspace:admin` writes (the server enforces it). */
function RetentionForm({ workspace }: { workspace: Workspace }) {
  const t = useMessages();
  const s = t.admin.settings;
  const f = s.fields;
  const actions = useAdminActions();
  const editable = useCan()('workspace:admin');
  const [jobRuns, setJobRuns] = useState(workspace.jobRunRetentionDays);
  const [notifications, setNotifications] = useState(workspace.notificationRetentionDays);
  const [ttl, setTtl] = useState(workspace.promotionTtlHours);
  const [message, setMessage] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function save() {
    setBusy(true);
    setMessage(null);
    try {
      await actions.saveWorkspaceSettings(workspace.id, { jobRunRetentionDays: jobRuns, notificationRetentionDays: notifications, promotionTtlHours: ttl });
      setMessage(s.saved);
    } catch (err) {
      setMessage(messageOf(err, t.admin.common.saveError));
    } finally {
      setBusy(false);
    }
  }

  const number = (value: number, set: (next: number) => void, min: number, max: number) => (
    <input className={fieldClass} type="number" min={min} max={max} value={value} disabled={!editable}
      onChange={(ev) => set(Number(ev.target.value))} />
  );

  return (
    <div className="mt-4">
      <h3 className="text-[13px] font-semibold">{s.retention}</h3>
      <p className="mt-1 mb-3 text-[12px] text-text-3">{s.retentionNote}</p>
      <div className="flex flex-col gap-3">
        <Field label={f.jobRunRetentionDays} hint={f.jobRunRetentionHint}>{number(jobRuns, setJobRuns, 7, 3650)}</Field>
        <Field label={f.notificationRetentionDays} hint={f.notificationRetentionHint}>{number(notifications, setNotifications, 7, 3650)}</Field>
        <Field label={f.promotionTtlHours} hint={f.promotionTtlHint}>{number(ttl, setTtl, 0, 8760)}</Field>
      </div>
      {message && <p role="status" className="mt-3 rounded-control bg-info-bg px-3 py-2 text-[12.5px] text-info">{message}</p>}
      <div className="mt-3 flex items-center gap-3">
        <button type="button" className={primaryButton} disabled={!editable || busy} onClick={save}>{s.save}</button>
        {!editable && <span className="text-[12px] text-text-3">{s.notAllowed}</span>}
      </div>
    </div>
  );
}
