import { useState } from 'react';

import { useAdminActions, useConnectionList, useProjectList } from '../../api/admin';
import type { Connection, ConnectionBody, ConnectionTest } from '../../api/admin';
import { useEnvironments } from '../../api/catalog';
import { useCan } from '../../auth/useCan';
import DataTable from '../../components/DataTable';
import type { Column } from '../../components/DataTable';
import EnvBadge from '../../components/EnvBadge';
import FormDialog, { Field, dangerLinkClass, fieldClass, linkClass, messageOf, primaryButton } from '../../components/FormDialog';
import PageHeader from '../../components/PageHeader';
import Pagination from '../../components/Pagination';
import StatusBadge from '../../components/StatusBadge';
import { EmptyState, ErrorCard } from '../../components/States';
import { fill } from '../../i18n';
import { toggle } from './rules';
import { useLocale, useMessages } from '../../i18n/useMessages';
import { formatShortDateTime } from '../../lib/format';
import AdminTabs from '../../shell/AdminTabs';
import Topbar from '../../shell/Topbar';
import { crumb } from '../../shell/crumbs';
import { usePaged } from './paging';

const ENGINES = ['SQLSERVER', 'POSTGRESQL', 'ORACLE', 'MYSQL'];
const PROVIDERS = ['NONE', 'ENV', 'VAULT', 'CONSUL', 'AZURE_KV'];

/** /admin/connections (RF-CAD-05): where each database lives, how its secret is found, and whether it answers. */
export default function Connections() {
  const t = useMessages();
  const a = t.admin;
  const c = a.connections;
  const locale = useLocale();
  const connections = useConnectionList();
  const environments = useEnvironments();
  const actions = useAdminActions();
  const editable = useCan()('connection:edit');
  const paged = usePaged(connections.data);
  const [editing, setEditing] = useState<Connection | 'new' | null>(null);
  const [testing, setTesting] = useState<string | null>(null);
  const [result, setResult] = useState<{ connection: string; test: ConnectionTest } | null>(null);

  const envCode = (id: string) => (environments.data ?? []).find((env) => env.id === id)?.code;

  async function test(connection: Connection) {
    setTesting(connection.id);
    setResult(null);
    try {
      setResult({ connection: connection.displayName, test: await actions.testConnection(connection.id) });
    } catch (err) {
      setResult({ connection: connection.displayName, test: { ok: false, message: messageOf(err, a.common.saveError), latencyMs: 0 } });
    } finally {
      setTesting(null);
    }
  }

  const columns: Column<Connection>[] = [
    { key: 'connection', header: c.columns.connection, cell: (r) => (
      <span>
        <span className="font-semibold">{r.displayName}</span> <code className="font-mono text-[12px] text-text-3">{r.slug}</code>
        {!r.verified && <> <StatusBadge tone="warning">{c.unverified}</StatusBadge></>}
      </span>
    ) },
    { key: 'environment', header: c.columns.environment, cell: (r) => { const code = envCode(r.environmentId); return code ? <EnvBadge code={code} /> : a.common.none; } },
    { key: 'engine', header: c.columns.engine, cell: (r) => r.engine },
    { key: 'version', header: c.columns.version, cell: (r) => r.dbVersion ?? a.common.none },
    { key: 'secret', header: c.columns.secret, cell: (r) => r.secretProvider && r.secretProvider !== 'NONE' ? <code className="font-mono text-[12px]">{r.secretProvider}</code> : a.common.none },
    { key: 'drift', header: c.columns.drift, cell: (r) => <StatusBadge tone={r.driftEnabled ? 'info' : 'neutral'}>{r.driftEnabled ? a.common.on : a.common.off}</StatusBadge> },
    { key: 'scanner', header: c.columns.scanner, cell: (r) => r.scannerEnabled ? fill(c.scannerEvery, { n: r.scannerIntervalMin ?? 15 }) : a.common.off },
    { key: 'test', header: c.columns.test, cell: (r) => r.lastTestAt
      ? <StatusBadge tone={r.lastTestOk ? 'success' : 'danger'}>{`${r.lastTestOk ? c.testOk : c.testFailed} · ${formatShortDateTime(r.lastTestAt, locale)}`}</StatusBadge>
      : <span className="text-text-3">{c.neverTested}</span> },
    {
      key: 'actions', header: a.common.actions, align: 'right',
      cell: (r) => (
        editable ? (
          <span className="flex justify-end gap-3">
            <button type="button" className={linkClass} disabled={testing === r.id} onClick={() => test(r)}>{testing === r.id ? c.testing : c.test}</button>
            <button type="button" className={linkClass} onClick={() => setEditing(r)}>{a.common.edit}</button>
            <button type="button" className={dangerLinkClass}
              onClick={() => window.confirm(c.confirmArchive) && actions.archiveConnection(r.id)}>{a.common.archive}</button>
          </span>
        ) : null
      ),
    },
  ];

  return (
    <>
      <Topbar trail={[crumb(a.breadcrumbRoot, '/admin'), c.title]} />
      <PageHeader title={c.title} subtitle={c.subtitle}
        right={editable && <button type="button" className={primaryButton} onClick={() => setEditing('new')}>{c.newConnection}</button>} />
      <AdminTabs />
      {result && (
        <p role="status" className={`mb-3 rounded-control px-3 py-2 text-[12.5px] ${result.test.ok ? 'bg-success-bg text-success' : 'bg-danger-bg text-danger'}`}>
          <strong>{result.connection}:</strong>{' '}
          {result.test.ok
            ? fill(c.testResultOk, { ms: result.test.latencyMs, version: result.test.dbVersion ?? a.common.none })
            : fill(c.testResultFailed, { ms: result.test.latencyMs, message: result.test.message ?? '' })}
          {(result.test.warnings ?? []).map((warning) => (
            <span key={warning} className="mt-1 block text-warning"><strong>{c.warning}</strong> {warning}</span>
          ))}
        </p>
      )}
      <section className="rounded-card border border-border bg-surface shadow-card">
        {connections.isError ? (
          <ErrorCard message={a.common.loadError} onRetry={() => connections.refetch()} />
        ) : connections.data && connections.data.length === 0 ? (
          <EmptyState title={c.empty.title} hint={c.empty.hint} />
        ) : (
          <>
            <DataTable columns={columns} rows={paged.rows} rowKey={(r) => r.id} loading={connections.isLoading} caption={c.title} />
            <div className="px-4 py-3"><Pagination page={paged.page} totalPages={paged.totalPages} onPage={paged.setPage} /></div>
          </>
        )}
      </section>
      {editing && <ConnectionDialog connection={editing === 'new' ? null : editing} onClose={() => setEditing(null)} />}
    </>
  );
}

function ConnectionDialog({ connection, onClose }: { connection: Connection | null; onClose: () => void }) {
  const t = useMessages();
  const a = t.admin;
  const d = a.connections.dialog;
  const actions = useAdminActions();
  const environments = useEnvironments();
  const projects = useProjectList();
  const [projectIds, setProjectIds] = useState<string[]>(connection?.projectIds ?? []);
  const [displayName, setDisplayName] = useState(connection?.displayName ?? '');
  const [slug, setSlug] = useState(connection?.slug ?? '');
  const [environmentId, setEnvironmentId] = useState(connection?.environmentId ?? '');
  const [engine, setEngine] = useState(connection?.engine ?? 'SQLSERVER');
  const [jdbcUrl, setJdbcUrl] = useState(connection?.jdbcUrl ?? '');
  const [schema, setSchema] = useState(connection?.defaultSchema ?? '');
  const [provider, setProvider] = useState(connection?.secretProvider ?? 'NONE');
  const [secretRef, setSecretRef] = useState(connection?.secretRef ?? '');
  const [drift, setDrift] = useState(connection?.driftEnabled ?? false);
  const [cron, setCron] = useState(connection?.driftCron ?? '0 0 */6 * * *');
  const [scanner, setScanner] = useState(connection?.scannerEnabled ?? true);
  const [interval, setIntervalMin] = useState(connection?.scannerIntervalMin ?? 15);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const envId = environmentId || environments.data?.[0]?.id || '';

  async function save() {
    setBusy(true);
    setError(null);
    const body: ConnectionBody = {
      environmentId: envId, slug: slug.trim(), displayName: displayName.trim(), engine, jdbcUrl: jdbcUrl.trim(),
      defaultSchema: schema.trim() || null, secretProvider: provider, secretRef: provider === 'NONE' ? null : secretRef.trim() || null,
      driftEnabled: drift, driftCron: cron.trim() || null, scannerEnabled: scanner, scannerIntervalMin: interval, projectIds,
    };
    try {
      await (connection ? actions.updateConnection(connection.id, body) : actions.createConnection(body));
      onClose();
    } catch (err) {
      setError(messageOf(err, a.common.saveError));
    } finally {
      setBusy(false);
    }
  }

  return (
    <FormDialog title={connection ? d.titleEdit : d.titleNew} onClose={onClose} onSave={save} busy={busy} error={error} width={640}
      saveDisabled={displayName.trim() === '' || slug.trim() === '' || jdbcUrl.trim() === '' || envId === ''}>
      <div className="grid grid-cols-2 gap-3">
        <Field label={d.displayName}><input className={fieldClass} value={displayName} onChange={(ev) => setDisplayName(ev.target.value)} /></Field>
        <Field label={d.slug}><input className={`${fieldClass} font-mono`} value={slug} onChange={(ev) => setSlug(ev.target.value)} /></Field>
      </div>
      <div className="grid grid-cols-2 gap-3">
        <Field label={d.environment}>
          <select className={fieldClass} value={envId} onChange={(ev) => setEnvironmentId(ev.target.value)}>
            {(environments.data ?? []).map((env) => <option key={env.id} value={env.id}>{env.code} — {env.name}</option>)}
          </select>
        </Field>
        <Field label={d.engine}>
          <select className={fieldClass} value={engine} onChange={(ev) => setEngine(ev.target.value)}>
            {[...new Set([...ENGINES, engine])].map((item) => <option key={item} value={item}>{item}</option>)}
          </select>
        </Field>
      </div>
      <Field label={d.jdbcUrl} hint={d.jdbcHint}>
        <input className={`${fieldClass} font-mono`} value={jdbcUrl} onChange={(ev) => setJdbcUrl(ev.target.value)}
          placeholder="jdbc:sqlserver://host:1433;databaseName=app" />
      </Field>
      <Field label={d.schema}><input className={`${fieldClass} font-mono`} value={schema} onChange={(ev) => setSchema(ev.target.value)} /></Field>
      <div className="grid grid-cols-[180px_1fr] gap-3">
        <Field label={d.secretProvider}>
          <select className={fieldClass} value={provider} onChange={(ev) => setProvider(ev.target.value)}>
            {PROVIDERS.map((item) => <option key={item} value={item}>{item}</option>)}
          </select>
        </Field>
        <Field label={d.secretRef} hint={provider === 'ENV' ? d.secretHint : undefined}>
          <input className={`${fieldClass} font-mono`} value={secretRef} disabled={provider === 'NONE'}
            onChange={(ev) => setSecretRef(ev.target.value)} />
        </Field>
      </div>
      <div className="grid grid-cols-2 gap-3">
        <div className="flex flex-col gap-2">
          <label className="flex items-center gap-2 text-[13px]">
            <input type="checkbox" checked={drift} onChange={(ev) => setDrift(ev.target.checked)} /> {d.drift}
          </label>
          <Field label={d.driftCron}>
            <input className={`${fieldClass} font-mono`} value={cron} disabled={!drift} onChange={(ev) => setCron(ev.target.value)} />
          </Field>
        </div>
        <div className="flex flex-col gap-2">
          <label className="flex items-center gap-2 text-[13px]">
            <input type="checkbox" checked={scanner} onChange={(ev) => setScanner(ev.target.checked)} /> {d.scanner}
          </label>
          <Field label={d.scannerInterval}>
            <input className={fieldClass} type="number" min={1} value={interval} disabled={!scanner}
              onChange={(ev) => setIntervalMin(Number(ev.target.value))} />
          </Field>
        </div>
      </div>
      <Field label={d.projects}>
        {(projects.data ?? []).length === 0 ? (
          <span className="text-[12.5px] text-text-2">{d.noProjects}</span>
        ) : (
          <div className="flex max-h-[120px] flex-col gap-1 overflow-auto rounded-control border border-border p-2">
            {(projects.data ?? []).map((project) => (
              <label key={project.id} className="flex items-center gap-2 text-[13px] font-normal">
                <input type="checkbox" checked={projectIds.includes(project.id)} onChange={() => setProjectIds(toggle(projectIds, project.id))} />
                {project.name} <code className="font-mono text-[12px] text-text-3">{project.slug}</code>
              </label>
            ))}
          </div>
        )}
      </Field>
    </FormDialog>
  );
}
