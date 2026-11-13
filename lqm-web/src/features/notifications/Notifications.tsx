import { useState } from 'react';

import { useEnvironments } from '../../api/catalog';
import { useCan } from '../../auth/useCan';
import { EVENTS, useDeliveries, useNotificationActions, useNotificationRules } from '../../api/notifications';
import type { Channel, NotificationRule } from '../../api/notifications';
import DataTable from '../../components/DataTable';
import type { Column } from '../../components/DataTable';
import PageHeader from '../../components/PageHeader';
import StatusBadge from '../../components/StatusBadge';
import type { Tone } from '../../components/StatusBadge';
import { EmptyState, ErrorCard } from '../../components/States';
import { fill } from '../../i18n';
import { useLocale, useMessages } from '../../i18n/useMessages';
import { formatShortDateTime } from '../../lib/format';
import AdminTabs from '../../shell/AdminTabs';
import Topbar from '../../shell/Topbar';
import { crumb } from '../../shell/crumbs';

const DIGESTS = [5, 15, 30, 60, 240, 1440] as const;

/** The zone the person is in, as the default of the quiet hours; UTC when the browser will not say. */
function browserZone(): string {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC';
  } catch {
    return 'UTC';
  }
}

const DELIVERY_TONE: Record<string, Tone> = { PENDING: 'info', SENT: 'success', FAILED: 'danger' };

/** /admin/notifications: the rules, and what was actually sent. */
export default function Notifications() {
  const t = useMessages();
  const n = t.notifications;
  const locale = useLocale();
  const rules = useNotificationRules();
  const deliveries = useDeliveries();
  const actions = useNotificationActions();
  const editable = useCan()('workspace:admin');
  const [editing, setEditing] = useState<NotificationRule | 'new' | null>(null);
  const [message, setMessage] = useState<string | null>(null);

  async function test(rule: NotificationRule) {
    setMessage(null);
    try {
      const result = await actions.test(rule.id);
      setMessage(result.ok ? n.testOk : fill(n.testFailed, { error: result.error ?? '' }));
    } catch (err) {
      setMessage(fill(n.testFailed, { error: err instanceof Error ? err.message : '' }));
    }
  }

  const link = 'text-[12px] font-semibold text-primary hover:underline';
  const columns: Column<NotificationRule>[] = [
    { key: 'name', header: n.columns.name, cell: (r) => {
      const extras = [
        r.minSeverity != null ? n.options.severity(t.governance.severity[r.minSeverity as 0 | 1 | 2 | 3 | 4]) : null,
        r.quietStart && r.quietEnd ? n.options.quiet(r.quietStart, r.quietEnd) : null,
        r.digestMinutes != null ? n.options.digest(r.digestMinutes) : null,
      ].filter(Boolean);
      return <span>{r.name}{extras.length > 0 && <span className="block text-[12px] text-text-3">{extras.join(' · ')}</span>}</span>;
    } },
    { key: 'event', header: n.columns.event, cell: (r) => n.events[r.event] },
    { key: 'channel', header: n.columns.channel, cell: (r) => n.channels[r.channel] },
    { key: 'target', header: n.columns.target, cell: (r) => <code className="font-mono text-[12px]">{r.target}</code> },
    { key: 'scope', header: n.columns.scope, cell: (r) => [r.environment, r.project].filter(Boolean).join(' · ') || n.allScopes },
    {
      key: 'enabled', header: n.columns.enabled,
      cell: (r) => <StatusBadge tone={r.enabled ? 'success' : 'neutral'}>{r.enabled ? n.enabled : n.disabled}</StatusBadge>,
    },
    {
      key: 'actions', header: n.columns.actions, align: 'right',
      cell: (r) => !editable ? null : (
        <span className="flex justify-end gap-3">
          <button type="button" className={link} onClick={() => test(r)}>{n.test}</button>
          <button type="button" className={link} onClick={() => setEditing(r)}>{n.edit}</button>
          <button type="button" className="text-[12px] font-semibold text-danger hover:underline"
            onClick={() => window.confirm(n.confirmDelete) && actions.remove(r.id)}>{n.delete}</button>
        </span>
      ),
    },
  ];

  return (
    <>
      <Topbar trail={[crumb(n.breadcrumbRoot, '/admin'), n.title]} />
      <PageHeader
        title={n.title}
        subtitle={n.subtitle}
        right={editable &&
          <button type="button" onClick={() => setEditing('new')}
            className="rounded-control bg-primary px-3 py-1.5 text-[12px] font-semibold text-white">{n.newRule}</button>
        }
      />
      <AdminTabs />
      {message && <p role="status" className="mb-3 rounded-control bg-info-bg px-3 py-2 text-[12.5px] text-info">{message}</p>}

      <section className="mb-3.5 rounded-card border border-border bg-surface shadow-card">
        {rules.isError ? (
          <ErrorCard message={n.error} onRetry={() => rules.refetch()} />
        ) : rules.data && rules.data.length === 0 ? (
          <EmptyState title={n.empty.title} hint={n.empty.hint} />
        ) : (
          <DataTable columns={columns} rows={rules.data ?? []} rowKey={(r) => r.id} loading={rules.isLoading} caption={n.title} />
        )}
      </section>

      <section className="rounded-card border border-border bg-surface p-4 shadow-card">
        <h2 className="mb-2 text-[14px] font-semibold">{n.deliveries.title}</h2>
        {deliveries.data && deliveries.data.length === 0 && <p className="text-[12.5px] text-text-2">{n.deliveries.none}</p>}
        <ul className="flex flex-col gap-2">
          {(deliveries.data ?? []).slice(0, 15).map((d) => (
            <li key={d.id} className="flex items-center gap-2 text-[12.5px]">
              <StatusBadge tone={DELIVERY_TONE[d.status]}>{n.deliveries.status[d.status]}</StatusBadge>
              <span>{d.subject}</span>
              <span className="text-text-3">{d.rule} · {n.channels[d.channel]}</span>
              {d.status !== 'SENT' && d.attempts > 0 && <span className="text-text-3">{fill(n.deliveries.attempts, { n: d.attempts })}</span>}
              {d.error && <span className="text-danger">{d.error}</span>}
              <span className="ml-auto text-text-3">{formatShortDateTime(d.createdAt, locale)}</span>
            </li>
          ))}
        </ul>
      </section>

      {editing && <RuleDialog rule={editing === 'new' ? null : editing} onClose={() => setEditing(null)} />}
    </>
  );
}

function RuleDialog({ rule, onClose }: { rule: NotificationRule | null; onClose: () => void }) {
  const t = useMessages();
  const n = t.notifications;
  const d = n.dialog;
  const actions = useNotificationActions();
  const environments = useEnvironments();

  const [name, setName] = useState(rule?.name ?? '');
  const [event, setEvent] = useState<string>(rule?.event ?? EVENTS[0]);
  const [channel, setChannel] = useState<Channel>(rule?.channel ?? 'EMAIL');
  // For an existing webhook the screen has nothing to show: the API never returns it. Empty means "keep".
  const [target, setTarget] = useState(rule && rule.channel === 'EMAIL' ? rule.target : '');
  const [environment, setEnvironment] = useState(rule?.environment ?? '');
  const [project, setProject] = useState(rule?.project ?? '');
  const [enabled, setEnabled] = useState(rule?.enabled ?? true);
  const [minSeverity, setMinSeverity] = useState<string>(rule?.minSeverity == null ? '' : String(rule.minSeverity));
  const [quietStart, setQuietStart] = useState(rule?.quietStart ?? '');
  const [quietEnd, setQuietEnd] = useState(rule?.quietEnd ?? '');
  const [quietZone, setQuietZone] = useState(rule?.quietZone ?? browserZone());
  const [digest, setDigest] = useState<string>(rule?.digestMinutes == null ? '' : String(rule.digestMinutes));
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const webhook = channel !== 'EMAIL';
  const keepsWebhook = rule !== null && webhook && rule.channel === channel;
  const halfQuiet = (quietStart === '') !== (quietEnd === '');
  const ready = name.trim() !== '' && (target.trim() !== '' || keepsWebhook) && !halfQuiet;

  async function save() {
    setBusy(true);
    setError(null);
    try {
      const body = {
        name: name.trim(), event, channel, enabled,
        target: target.trim() || undefined, environment, project,
        minSeverity: minSeverity === '' ? undefined : Number(minSeverity),
        quietStart: quietStart || undefined, quietEnd: quietEnd || undefined, quietZone: quietStart && quietEnd ? quietZone : undefined,
        digestMinutes: digest === '' ? undefined : Number(digest),
      };
      if (rule) {
        await actions.update(rule.id, body);
      } else {
        await actions.create(body);
      }
      onClose();
    } catch (err) {
      setError(err instanceof Error && err.message ? err.message : d.error);
    } finally {
      setBusy(false);
    }
  }

  const field = 'rounded-control border border-border bg-surface px-3 py-1.5 text-[13px] font-normal';
  const label = 'flex flex-col gap-1 text-[12px] font-semibold';

  return (
    <div role="dialog" aria-modal="true" aria-label={rule ? d.titleEdit : d.titleNew}
      className="fixed inset-0 z-50 flex items-center justify-center bg-text/30 p-6">
      <div className="flex w-[560px] flex-col gap-3 rounded-card border border-border bg-surface p-5 shadow-overlay">
        <h2 className="text-[18px] font-bold">{rule ? d.titleEdit : d.titleNew}</h2>
        <label className={label}>{d.name}<input className={field} value={name} onChange={(e) => setName(e.target.value)} /></label>
        <div className="grid grid-cols-2 gap-3">
          <label className={label}>{d.event}
            <select className={field} value={event} onChange={(e) => setEvent(e.target.value)}>
              {EVENTS.map((ev) => <option key={ev} value={ev}>{n.events[ev]}</option>)}
            </select>
          </label>
          <label className={label}>{d.channel}
            <select className={field} value={channel} onChange={(e) => { setChannel(e.target.value as Channel); setTarget(''); }}>
              {(['EMAIL', 'TEAMS', 'SLACK'] as const).map((c) => <option key={c} value={c}>{n.channels[c]}</option>)}
            </select>
          </label>
        </div>
        <label className={label}>{webhook ? d.targetWebhook : d.targetEmail}
          <input className={`${field} font-mono`} value={target} onChange={(e) => setTarget(e.target.value)}
            placeholder={keepsWebhook ? d.targetKeep : undefined} type={webhook ? 'password' : 'text'} autoComplete="off" />
          {webhook && <span className="font-normal text-text-3">{d.webhookWarning}</span>}
        </label>
        <div className="grid grid-cols-2 gap-3">
          <label className={label}>{d.environment}
            <select className={field} value={environment} onChange={(e) => setEnvironment(e.target.value)}>
              <option value="">{d.anyEnvironment}</option>
              {(environments.data ?? []).map((env) => <option key={env.id} value={env.code}>{env.code}</option>)}
            </select>
          </label>
          <label className={label}>{d.project}
            <input className={`${field} font-mono`} value={project} onChange={(e) => setProject(e.target.value)} />
          </label>
        </div>
        <div className="grid grid-cols-2 gap-3">
          <label className={label}>{d.minSeverity}
            <select className={field} value={minSeverity} onChange={(e) => setMinSeverity(e.target.value)}>
              <option value="">{d.anySeverity}</option>
              {([0, 1, 2, 3, 4] as const).map((value) => <option key={value} value={value}>{`${t.governance.severity[value]} ${d.orAbove}`}</option>)}
            </select>
            <span className="font-normal text-text-3">{d.severityHint}</span>
          </label>
          <label className={label}>{d.digest}
            <select className={field} value={digest} onChange={(e) => setDigest(e.target.value)}>
              <option value="">{d.noDigest}</option>
              {DIGESTS.map((minutes) => <option key={minutes} value={minutes}>{fill(d.digestEvery, { minutes })}</option>)}
            </select>
            <span className="font-normal text-text-3">{d.digestHint}</span>
          </label>
        </div>
        <fieldset className="flex flex-col gap-1.5 rounded-control border border-border p-2.5">
          <legend className="px-1 text-[12px] font-semibold">{d.quiet}</legend>
          <div className="grid grid-cols-3 gap-3">
            <label className={label}>{d.quietFrom}<input className={field} type="time" value={quietStart} onChange={(e) => setQuietStart(e.target.value)} /></label>
            <label className={label}>{d.quietTo}<input className={field} type="time" value={quietEnd} onChange={(e) => setQuietEnd(e.target.value)} /></label>
            <label className={label}>{d.quietZone}<input className={`${field} font-mono`} value={quietZone} onChange={(e) => setQuietZone(e.target.value)} /></label>
          </div>
          <span className="text-[12px] font-normal text-text-3">{halfQuiet ? d.quietBoth : d.quietHint}</span>
        </fieldset>
        <label className="flex items-center gap-2 text-[13px]">
          <input type="checkbox" checked={enabled} onChange={(e) => setEnabled(e.target.checked)} /> {d.enabled}
        </label>
        {error && <p role="alert" className="text-[12.5px] text-danger">{error}</p>}
        <div className="flex justify-end gap-2">
          <button type="button" onClick={onClose}
            className="rounded-control border border-border px-3 py-1.5 text-[12px] font-semibold hover:bg-surface-alt">{d.cancel}</button>
          <button type="button" disabled={!ready || busy} onClick={save}
            className="rounded-control bg-primary px-3 py-1.5 text-[12px] font-semibold text-white disabled:opacity-50">{d.save}</button>
        </div>
      </div>
    </div>
  );
}
