import { useState } from 'react';
import { ArrowDown, ArrowUp, GripVertical } from 'lucide-react';

import { useAdminActions } from '../../api/admin';
import type { EnvironmentBody } from '../../api/admin';
import { useEnvironments } from '../../api/catalog';
import { useCan } from '../../auth/useCan';
import type { Environment } from '../../api/catalog';
import DataTable from '../../components/DataTable';
import type { Column } from '../../components/DataTable';
import EnvBadge from '../../components/EnvBadge';
import FormDialog, { Field, fieldClass, linkClass, messageOf, primaryButton } from '../../components/FormDialog';
import { fill } from '../../i18n';
import PageHeader from '../../components/PageHeader';
import StatusBadge from '../../components/StatusBadge';
import { EmptyState, ErrorCard } from '../../components/States';
import { useMessages } from '../../i18n/useMessages';
import AdminTabs from '../../shell/AdminTabs';
import Topbar from '../../shell/Topbar';
import { moveItem } from './rules';

/** The colour tokens of the design system (`--env-*-fg/bg` in theme.css). */
const COLOR_TOKENS = ['dev', 'tst', 'hml', 'prd'];

/** /admin/environments (RF-CAD-04): the pipeline order, one row per environment. */
export default function Environments() {
  const t = useMessages();
  const a = t.admin;
  const e = a.environments;
  const environments = useEnvironments();
  const can = useCan();
  const editable = can('workspace:admin');
  const [editing, setEditing] = useState<Environment | 'new' | null>(null);

  const yesNo = (value: boolean) => <StatusBadge tone={value ? 'info' : 'neutral'}>{value ? a.common.yes : a.common.no}</StatusBadge>;
  const columns: Column<Environment>[] = [
    { key: 'order', header: e.columns.order, cell: (r) => r.sortOrder },
    { key: 'code', header: e.columns.code, cell: (r) => <EnvBadge code={r.code} /> },
    { key: 'name', header: e.columns.name, cell: (r) => r.name },
    { key: 'color', header: e.columns.color, cell: (r) => <code className="font-mono text-[12px]">{r.colorToken}</code> },
    { key: 'prod', header: e.columns.production, cell: (r) => yesNo(r.isProduction) },
    { key: 'approval', header: e.columns.approval, cell: (r) => yesNo(r.requiresApproval) },
    {
      key: 'actions', header: a.common.actions, align: 'right',
      cell: (r) => editable ? <button type="button" className={linkClass} onClick={() => setEditing(r)}>{a.common.edit}</button> : null,
    },
  ];

  return (
    <>
      <Topbar trail={[a.breadcrumbRoot, e.title]} />
      <PageHeader title={e.title} subtitle={e.subtitle}
        right={editable && <button type="button" className={primaryButton} onClick={() => setEditing('new')}>{e.newEnvironment}</button>} />
      <AdminTabs />
      <section className="rounded-card border border-border bg-surface shadow-card">
        {environments.isError ? (
          <ErrorCard message={a.common.loadError} onRetry={() => environments.refetch()} />
        ) : environments.data && environments.data.length === 0 ? (
          <EmptyState title={e.empty.title} hint={e.empty.hint} />
        ) : (
          <DataTable columns={columns} rows={environments.data ?? []} rowKey={(r) => r.id} loading={environments.isLoading} caption={e.title} />
        )}
      </section>
      {editable && (environments.data?.length ?? 0) > 1 && <OrderPanel environments={environments.data ?? []} />}
      {editing && <EnvironmentDialog environment={editing === 'new' ? null : editing}
        next={(environments.data?.length ?? 0) + 1} onClose={() => setEditing(null)} />}
    </>
  );
}

/** The pipeline order, by dragging a row or with the buttons (the keyboard and screen readers get the buttons). One audited write per move. */
function OrderPanel({ environments }: { environments: Environment[] }) {
  const t = useMessages();
  const r = t.admin.environments.reorder;
  const actions = useAdminActions();
  const [dragging, setDragging] = useState<number | null>(null);
  const [message, setMessage] = useState<string | null>(null);

  async function move(from: number, to: number) {
    const ids = moveItem(environments.map((item) => item.id), from, to);
    if (ids.every((id, index) => id === environments[index].id)) return;
    try {
      await actions.reorderEnvironments(ids);
      setMessage(r.saved);
    } catch (err) {
      setMessage(messageOf(err, t.admin.common.saveError));
    }
  }

  return (
    <section className="mt-3.5 rounded-card border border-border bg-surface p-4 shadow-card">
      <h2 className="text-[14px] font-semibold">{r.title}</h2>
      <p className="mb-2 text-[12px] text-text-3">{r.hint}</p>
      {message && <p role="status" className="mb-2 rounded-control bg-info-bg px-3 py-2 text-[12.5px] text-info">{message}</p>}
      <ol className="flex flex-col gap-1.5">
        {environments.map((env, index) => (
          <li key={env.id} draggable
            onDragStart={() => setDragging(index)}
            onDragOver={(ev) => ev.preventDefault()}
            onDrop={() => { if (dragging !== null) void move(dragging, index); setDragging(null); }}
            onDragEnd={() => setDragging(null)}
            className={`flex items-center gap-2 rounded-control border border-border px-2 py-1.5 ${dragging === index ? 'opacity-50' : ''}`}>
            <GripVertical size={16} aria-label={fill(r.handle, { code: env.code })} className="cursor-grab text-text-3" />
            <span className="w-6 text-[12px] text-text-3">{index + 1}</span>
            <EnvBadge code={env.code} />
            <span className="text-[13px]">{env.name}</span>
            <span className="ml-auto flex gap-1">
              <button type="button" className={linkClass} aria-label={fill(r.up, { code: env.code })} disabled={index === 0}
                onClick={() => move(index, index - 1)}><ArrowUp size={14} /></button>
              <button type="button" className={linkClass} aria-label={fill(r.down, { code: env.code })} disabled={index === environments.length - 1}
                onClick={() => move(index, index + 1)}><ArrowDown size={14} /></button>
            </span>
          </li>
        ))}
      </ol>
    </section>
  );
}

function EnvironmentDialog({ environment, next, onClose }: { environment: Environment | null; next: number; onClose: () => void }) {
  const t = useMessages();
  const a = t.admin;
  const d = a.environments.dialog;
  const actions = useAdminActions();
  const [code, setCode] = useState(environment?.code ?? '');
  const [name, setName] = useState(environment?.name ?? '');
  const [order, setOrder] = useState(environment?.sortOrder ?? next);
  const [color, setColor] = useState(environment?.colorToken ?? 'dev');
  const [production, setProduction] = useState(environment?.isProduction ?? false);
  const [approval, setApproval] = useState(environment?.requiresApproval ?? false);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function save() {
    setBusy(true);
    setError(null);
    const body: EnvironmentBody = {
      code: code.trim().toUpperCase(), name: name.trim(), sortOrder: order, colorToken: color,
      isProduction: production, requiresApproval: approval,
    };
    try {
      await (environment ? actions.updateEnvironment(environment.id, body) : actions.createEnvironment(body));
      onClose();
    } catch (err) {
      setError(messageOf(err, a.common.saveError));
    } finally {
      setBusy(false);
    }
  }

  return (
    <FormDialog title={environment ? d.titleEdit : d.titleNew} onClose={onClose} onSave={save} busy={busy} error={error}
      saveDisabled={code.trim() === '' || name.trim() === ''}>
      <div className="grid grid-cols-2 gap-3">
        <Field label={d.code} hint={d.codeHint}>
          <input className={`${fieldClass} font-mono`} value={code} onChange={(ev) => setCode(ev.target.value)} />
        </Field>
        <Field label={d.name}><input className={fieldClass} value={name} onChange={(ev) => setName(ev.target.value)} /></Field>
      </div>
      <div className="grid grid-cols-2 gap-3">
        <Field label={d.order}>
          <input className={fieldClass} type="number" min={1} value={order} onChange={(ev) => setOrder(Number(ev.target.value))} />
        </Field>
        <Field label={d.color} hint={d.colorHint}>
          <select className={fieldClass} value={color} onChange={(ev) => setColor(ev.target.value)}>
            {[...new Set([...COLOR_TOKENS, color])].map((token) => <option key={token} value={token}>{token}</option>)}
          </select>
        </Field>
      </div>
      <label className="flex items-center gap-2 text-[13px]">
        <input type="checkbox" checked={production} onChange={(ev) => setProduction(ev.target.checked)} /> {d.production}
        <span className="text-text-3">— {d.productionHint}</span>
      </label>
      <label className="flex items-center gap-2 text-[13px]">
        <input type="checkbox" checked={approval} onChange={(ev) => setApproval(ev.target.checked)} /> {d.approval}
      </label>
      <div className="flex items-center gap-2 text-[12px] text-text-2">{d.preview}: <EnvBadge code={code.trim().toUpperCase() || '—'} /></div>
    </FormDialog>
  );
}
