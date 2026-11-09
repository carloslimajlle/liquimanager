import { useState } from 'react';

import { TOKEN_SCOPES, useAdminActions, useProjectList, useServiceTokens } from '../../api/admin';
import type { IssuedToken, ServiceToken } from '../../api/admin';
import { useCan } from '../../auth/useCan';
import DataTable from '../../components/DataTable';
import type { Column } from '../../components/DataTable';
import FormDialog, { Field, dangerLinkClass, fieldClass, messageOf, primaryButton } from '../../components/FormDialog';
import PageHeader from '../../components/PageHeader';
import Pagination from '../../components/Pagination';
import StatusBadge from '../../components/StatusBadge';
import type { Tone } from '../../components/StatusBadge';
import { EmptyState, ErrorCard } from '../../components/States';
import { useLocale, useMessages } from '../../i18n/useMessages';
import { formatShortDateTime } from '../../lib/format';
import AdminTabs from '../../shell/AdminTabs';
import Topbar from '../../shell/Topbar';
import { usePaged } from './paging';
import { stateOf, toggle } from './rules';

const DAY_MS = 24 * 60 * 60 * 1000;

const STATE_TONE: Record<'active' | 'revoked' | 'expired', Tone> = { active: 'success', revoked: 'neutral', expired: 'warning' };

/** /admin/tokens (RF-ADM-03): the service tokens of the collectors and the runner. The value is shown once. */
export default function Tokens() {
  const t = useMessages();
  const a = t.admin;
  const k = a.tokens;
  const locale = useLocale();
  const tokens = useServiceTokens();
  const projects = useProjectList();
  const actions = useAdminActions();
  const editable = useCan()('token:manage');
  const paged = usePaged(tokens.data);
  const [creating, setCreating] = useState(false);
  const [issued, setIssued] = useState<IssuedToken | null>(null);

  const projectName = (id: string) => (projects.data ?? []).find((project) => project.id === id)?.slug ?? id;
  const now = Date.now();
  const columns: Column<ServiceToken>[] = [
    { key: 'name', header: k.columns.name, cell: (r) => <span className="font-semibold">{r.name}</span> },
    { key: 'prefix', header: k.columns.prefix, cell: (r) => <code className="font-mono text-[12px]">{`lqm_${r.prefix}…`}</code> },
    { key: 'scopes', header: k.columns.scopes, cell: (r) => <span className="font-mono text-[12px]">{r.scopes.join(', ')}</span> },
    { key: 'projects', header: k.columns.projects, cell: (r) => r.projectIds.length === 0 ? k.allProjects : r.projectIds.map(projectName).join(', ') },
    { key: 'createdBy', header: k.columns.createdBy, cell: (r) => r.createdBy ?? a.common.none },
    { key: 'lastUsed', header: k.columns.lastUsed, cell: (r) => r.lastUsedAt ? formatShortDateTime(r.lastUsedAt, locale) : k.never },
    { key: 'expires', header: k.columns.expires, cell: (r) => r.expiresAt ? formatShortDateTime(r.expiresAt, locale) : k.noExpiry },
    { key: 'state', header: k.columns.state, cell: (r) => { const s = stateOf(r, now); return <StatusBadge tone={STATE_TONE[s]}>{k[s]}</StatusBadge>; } },
    {
      key: 'actions', header: a.common.actions, align: 'right',
      cell: (r) => editable && stateOf(r, now) === 'active'
        ? <button type="button" className={dangerLinkClass} onClick={() => window.confirm(k.confirmRevoke) && actions.revokeToken(r.id)}>{k.revoke}</button>
        : null,
    },
  ];

  return (
    <>
      <Topbar trail={[a.breadcrumbRoot, k.title]} />
      <PageHeader title={k.title} subtitle={k.subtitle}
        right={editable && <button type="button" className={primaryButton} onClick={() => setCreating(true)}>{k.newToken}</button>} />
      <AdminTabs />
      <section className="rounded-card border border-border bg-surface shadow-card">
        {tokens.isError ? (
          <ErrorCard message={a.common.loadError} onRetry={() => tokens.refetch()} />
        ) : tokens.data && tokens.data.length === 0 ? (
          <EmptyState title={k.empty.title} hint={k.empty.hint} />
        ) : (
          <>
            <DataTable columns={columns} rows={paged.rows} rowKey={(r) => r.id} loading={tokens.isLoading} caption={k.title} />
            <div className="px-4 py-3"><Pagination page={paged.page} totalPages={paged.totalPages} onPage={paged.setPage} /></div>
          </>
        )}
      </section>
      {creating && <TokenDialog onClose={() => setCreating(false)} onIssued={(value) => { setCreating(false); setIssued(value); }} />}
      {issued && <IssuedDialog token={issued} onDone={() => setIssued(null)} />}
    </>
  );
}

function TokenDialog({ onClose, onIssued }: { onClose: () => void; onIssued: (token: IssuedToken) => void }) {
  const t = useMessages();
  const a = t.admin;
  const d = a.tokens.dialog;
  const actions = useAdminActions();
  const projects = useProjectList();
  const [name, setName] = useState('');
  const [scopes, setScopes] = useState<string[]>(['ingest']);
  const [projectIds, setProjectIds] = useState<string[]>([]);
  const [expires, setExpires] = useState(() => new Date(Date.now() + 90 * DAY_MS).toISOString().slice(0, 10));
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);


  async function save() {
    setBusy(true);
    setError(null);
    try {
      // End of the chosen day, in UTC: the token works through the whole day the person picked.
      const issued = await actions.issueToken({ name: name.trim(), scopes, projectIds, expiresAt: `${expires}T23:59:59Z` });
      onIssued(issued);
    } catch (err) {
      setError(messageOf(err, a.common.saveError));
    } finally {
      setBusy(false);
    }
  }

  return (
    <FormDialog title={d.title} onClose={onClose} onSave={save} busy={busy} error={error}
      saveDisabled={name.trim() === '' || scopes.length === 0 || expires === ''}>
      <Field label={d.name}><input className={fieldClass} value={name} onChange={(ev) => setName(ev.target.value)} /></Field>
      <fieldset className="flex flex-col gap-1">
        <legend className="mb-1 text-[12px] font-semibold">{d.scopes}</legend>
        {TOKEN_SCOPES.map((scope) => (
          <label key={scope} className="flex items-center gap-2 text-[13px]">
            <input type="checkbox" checked={scopes.includes(scope)} onChange={() => setScopes(toggle(scopes, scope))} />
            <span className="font-mono text-[12.5px]">{a.tokens.scopes[scope]}</span>
          </label>
        ))}
      </fieldset>
      <fieldset className="flex max-h-40 flex-col gap-1 overflow-auto">
        <legend className="mb-1 text-[12px] font-semibold">{d.projects}</legend>
        {(projects.data ?? []).map((project) => (
          <label key={project.id} className="flex items-center gap-2 text-[13px]">
            <input type="checkbox" checked={projectIds.includes(project.id)} onChange={() => setProjectIds(toggle(projectIds, project.id))} />
            {project.name} <code className="font-mono text-[12px] text-text-3">{project.slug}</code>
          </label>
        ))}
        <span className="text-[12px] text-text-3">{d.projectsHint}</span>
      </fieldset>
      <Field label={d.expires} hint={d.expiresHint}>
        <input className={fieldClass} type="date" value={expires} onChange={(ev) => setExpires(ev.target.value)} />
      </Field>
    </FormDialog>
  );
}

/** The only time the value exists on screen. Closing it is the person saying they copied it. */
function IssuedDialog({ token, onDone }: { token: IssuedToken; onDone: () => void }) {
  const t = useMessages();
  const i = t.admin.tokens.issued;
  const [copied, setCopied] = useState(false);

  async function copy() {
    try {
      await navigator.clipboard.writeText(token.token);
      setCopied(true);
    } catch {
      setCopied(false);
    }
  }

  return (
    <div role="dialog" aria-modal="true" aria-label={i.title} className="fixed inset-0 z-50 flex items-center justify-center bg-text/30 p-6">
      <div className="flex w-[600px] flex-col gap-3 rounded-card border border-border bg-surface p-5 shadow-overlay">
        <h2 className="text-[18px] font-bold">{i.title}</h2>
        <p role="alert" className="rounded-control bg-warning-bg px-3 py-2 text-[12.5px] text-warning">{i.warning}</p>
        <code className="block break-all rounded-control border border-border bg-surface-alt p-3 font-mono text-[12.5px]">{token.token}</code>
        <div className="flex justify-end gap-2">
          <button type="button" onClick={copy}
            className="rounded-control border border-border px-3 py-1.5 text-[12px] font-semibold hover:bg-surface-alt">{copied ? i.copied : i.copy}</button>
          <button type="button" onClick={onDone} className={primaryButton}>{i.done}</button>
        </div>
      </div>
    </div>
  );
}
