import { useState } from 'react';
import { Link } from 'react-router-dom';

import { useAdminActions, useConnectionList, usePipelineList, useProjectList, useStages } from '../../api/admin';
import type { Pipeline, Stage } from '../../api/admin';
import { useEnvironments } from '../../api/catalog';
import { usePolicyPackages } from '../../api/governance';
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
import { useMessages } from '../../i18n/useMessages';
import AdminTabs from '../../shell/AdminTabs';
import Topbar from '../../shell/Topbar';
import { crumb } from '../../shell/crumbs';
import { usePaged } from './paging';

/** /admin/pipelines (RF-CAD-06): a pipeline per project and, below the selected one, its stages in order. */
export default function Pipelines() {
  const t = useMessages();
  const a = t.admin;
  const p = a.pipelines;
  const pipelines = usePipelineList();
  const projects = useProjectList();
  const actions = useAdminActions();
  const editable = useCan()('pipeline:edit');
  const paged = usePaged(pipelines.data);
  const [editing, setEditing] = useState<Pipeline | 'new' | null>(null);
  const [selected, setSelected] = useState<Pipeline | null>(null);

  const projectName = (id: string) => (projects.data ?? []).find((project) => project.id === id)?.name ?? a.common.none;
  const columns: Column<Pipeline>[] = [
    { key: 'pipeline', header: p.columns.pipeline, cell: (r) => <span className="font-semibold">{r.name}</span> },
    { key: 'project', header: p.columns.project, cell: (r) => projectName(r.projectId) },
    { key: 'gitlab', header: p.columns.gitlab, cell: (r) => r.gitlabProjectId
      ? <code className="font-mono text-[12px]">{`#${r.gitlabProjectId} · ${r.gitlabTriggerRef ?? 'main'}`}</code>
      : <span className="text-text-3">{p.gitlabNone}</span> },
    {
      key: 'actions', header: a.common.actions, align: 'right',
      cell: (r) => (
        <span className="flex justify-end gap-3">
          <button type="button" className={linkClass} onClick={() => setSelected(r)}>{p.columns.stages}</button>
          <Link className={linkClass} to={`/pipelines/${r.id}/matrix`}>{p.matrix}</Link>
          {editable && <button type="button" className={linkClass} onClick={() => setEditing(r)}>{a.common.edit}</button>}
          {editable && <button type="button" className={dangerLinkClass}
            onClick={() => window.confirm(p.confirmDelete) && actions.deletePipeline(r.id).then(() => setSelected(null))}>{a.common.remove}</button>}
        </span>
      ),
    },
  ];

  return (
    <>
      <Topbar trail={[crumb(a.breadcrumbRoot, '/admin'), p.title]} />
      <PageHeader title={p.title} subtitle={p.subtitle}
        right={editable && <button type="button" className={primaryButton} onClick={() => setEditing('new')}>{p.newPipeline}</button>} />
      <AdminTabs />
      <section className="mb-3.5 rounded-card border border-border bg-surface shadow-card">
        {pipelines.isError ? (
          <ErrorCard message={a.common.loadError} onRetry={() => pipelines.refetch()} />
        ) : pipelines.data && pipelines.data.length === 0 ? (
          <EmptyState title={p.empty.title} hint={p.empty.hint} />
        ) : (
          <>
            <DataTable columns={columns} rows={paged.rows} rowKey={(r) => r.id} loading={pipelines.isLoading} caption={p.title}
              rowClass={(r) => (selected?.id === r.id ? 'bg-primary-50' : undefined)} />
            <div className="px-4 py-3"><Pagination page={paged.page} totalPages={paged.totalPages} onPage={paged.setPage} /></div>
          </>
        )}
      </section>
      {selected && <StagesPanel pipeline={selected} />}
      {editing && <PipelineDialog pipeline={editing === 'new' ? null : editing} onClose={() => setEditing(null)} />}
    </>
  );
}

function PipelineDialog({ pipeline, onClose }: { pipeline: Pipeline | null; onClose: () => void }) {
  const t = useMessages();
  const a = t.admin;
  const d = a.pipelines.dialog;
  const actions = useAdminActions();
  const projects = useProjectList();
  const [name, setName] = useState(pipeline?.name ?? '');
  const [projectId, setProjectId] = useState(pipeline?.projectId ?? '');
  const [gitlabProject, setGitlabProject] = useState(pipeline?.gitlabProjectId ? String(pipeline.gitlabProjectId) : '');
  const [ref, setRef] = useState(pipeline?.gitlabTriggerRef ?? 'main');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const project = projectId || projects.data?.[0]?.id || '';

  async function save() {
    setBusy(true);
    setError(null);
    const body = {
      name: name.trim(), projectId: project,
      gitlabProjectId: gitlabProject.trim() === '' ? null : Number(gitlabProject),
      gitlabTriggerRef: gitlabProject.trim() === '' ? null : ref.trim() || 'main',
    };
    try {
      await (pipeline ? actions.updatePipeline(pipeline.id, body) : actions.createPipeline(body));
      onClose();
    } catch (err) {
      setError(messageOf(err, a.common.saveError));
    } finally {
      setBusy(false);
    }
  }

  return (
    <FormDialog title={pipeline ? d.titleEdit : d.titleNew} onClose={onClose} onSave={save} busy={busy} error={error}
      saveDisabled={name.trim() === '' || project === '' || (gitlabProject.trim() !== '' && Number.isNaN(Number(gitlabProject)))}>
      <Field label={d.name}><input className={fieldClass} value={name} onChange={(ev) => setName(ev.target.value)} /></Field>
      <Field label={d.project}>
        <select className={fieldClass} value={project} onChange={(ev) => setProjectId(ev.target.value)}>
          {(projects.data ?? []).map((item) => <option key={item.id} value={item.id}>{item.name}</option>)}
        </select>
      </Field>
      <div className="grid grid-cols-2 gap-3">
        <Field label={d.gitlabProject} hint={d.gitlabHint}>
          <input className={`${fieldClass} font-mono`} inputMode="numeric" value={gitlabProject} onChange={(ev) => setGitlabProject(ev.target.value)} />
        </Field>
        <Field label={d.gitlabRef}>
          <input className={`${fieldClass} font-mono`} value={ref} disabled={gitlabProject.trim() === ''} onChange={(ev) => setRef(ev.target.value)} />
        </Field>
      </div>
    </FormDialog>
  );
}

/** The stages of a pipeline in order, each with the connection it deploys to and the package that gates it. */
function StagesPanel({ pipeline }: { pipeline: Pipeline }) {
  const t = useMessages();
  const a = t.admin;
  const s = a.pipelines.stages;
  const stages = useStages(pipeline.id);
  const environments = useEnvironments();
  const connections = useConnectionList();
  const packages = usePolicyPackages();
  const actions = useAdminActions();
  const can = useCan();
  const [editing, setEditing] = useState<Stage | 'new' | null>(null);
  const [message, setMessage] = useState<string | null>(null);

  const envCode = (id: string) => (environments.data ?? []).find((env) => env.id === id)?.code;
  const connectionName = (id?: string | null) => (connections.data ?? []).find((item) => item.id === id)?.displayName ?? s.noConnection;

  async function choose(stage: Stage, packageId: string) {
    setMessage(null);
    try {
      if (packageId === '') {
        if (stage.policyPackageId) await actions.unassignPackage(stage.policyPackageId, { stageId: stage.id });
      } else {
        await actions.assignPackage(packageId, { stageId: stage.id });
      }
      setMessage(s.packageChanged);
    } catch (err) {
      setMessage(messageOf(err, a.common.saveError));
    }
  }

  const columns: Column<Stage>[] = [
    { key: 'order', header: s.columns.order, cell: (r) => r.sortOrder },
    { key: 'environment', header: s.columns.environment, cell: (r) => { const code = envCode(r.environmentId); return code ? <EnvBadge code={code} /> : a.common.none; } },
    { key: 'connection', header: s.columns.connection, cell: (r) => connectionName(r.dbConnectionId) },
    {
      key: 'package', header: s.columns.package,
      cell: (r) => (
        <select aria-label={`${s.columns.package}: ${envCode(r.environmentId) ?? r.sortOrder}`} className={fieldClass}
          value={r.policyPackageId ?? ''} disabled={!can('policy:edit')} onChange={(ev) => choose(r, ev.target.value)}>
          <option value="">{s.noPackage}</option>
          {(packages.data ?? []).map((item) => <option key={item.id} value={item.id}>{item.name}</option>)}
        </select>
      ),
    },
    { key: 'approval', header: s.columns.approval, cell: (r) => <StatusBadge tone={r.requiresApproval ? 'info' : 'neutral'}>{r.requiresApproval ? a.common.yes : a.common.no}</StatusBadge> },
    {
      key: 'actions', header: a.common.actions, align: 'right',
      cell: (r) => (
        can('pipeline:edit') ? (
          <span className="flex justify-end gap-3">
            <button type="button" className={linkClass} onClick={() => setEditing(r)}>{a.common.edit}</button>
            <button type="button" className={dangerLinkClass}
              onClick={() => window.confirm(s.confirmDelete) && actions.deleteStage(pipeline.id, r.id)}>{a.common.remove}</button>
          </span>
        ) : null
      ),
    },
  ];

  return (
    <section className="rounded-card border border-border bg-surface shadow-card">
      <div className="flex items-center gap-2 px-4 pt-3">
        <h2 className="text-[14px] font-semibold">{fill(s.title, { pipeline: pipeline.name })}</h2>
        {can('pipeline:edit') && <button type="button" className={`${linkClass} ml-auto`} onClick={() => setEditing('new')}>{s.add}</button>}
      </div>
      {message && <p role="status" className="mx-4 mt-2 rounded-control bg-info-bg px-3 py-2 text-[12.5px] text-info">{message}</p>}
      {stages.isError ? (
        <ErrorCard message={a.common.loadError} onRetry={() => stages.refetch()} />
      ) : stages.data && stages.data.length === 0 ? (
        <p className="px-4 py-6 text-[12.5px] text-text-2">{s.empty}</p>
      ) : (
        <DataTable columns={columns} rows={stages.data ?? []} rowKey={(r) => r.id} loading={stages.isLoading} caption={s.title} />
      )}
      {editing && <StageDialog pipeline={pipeline} stage={editing === 'new' ? null : editing}
        next={(stages.data?.length ?? 0) + 1} onClose={() => setEditing(null)} />}
    </section>
  );
}

function StageDialog({ pipeline, stage, next, onClose }: { pipeline: Pipeline; stage: Stage | null; next: number; onClose: () => void }) {
  const t = useMessages();
  const a = t.admin;
  const d = a.pipelines.stages.dialog;
  const actions = useAdminActions();
  const environments = useEnvironments();
  const connections = useConnectionList();
  const [order, setOrder] = useState(stage?.sortOrder ?? next);
  const [environmentId, setEnvironmentId] = useState(stage?.environmentId ?? '');
  const [connectionId, setConnectionId] = useState(stage?.dbConnectionId ?? '');
  const [approval, setApproval] = useState(stage?.requiresApproval ?? false);
  const [autoRequest, setAutoRequest] = useState(stage?.autoRequest ?? false);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const envId = environmentId || environments.data?.[0]?.id || '';
  // Only the connections of the chosen environment make sense for the stage.
  const candidates = (connections.data ?? []).filter((item) => item.environmentId === envId);
  // The API requires the connection of a stage: default to the first one of the environment.
  const chosenConnection = candidates.some((item) => item.id === connectionId) ? connectionId : (candidates[0]?.id ?? '');

  async function save() {
    setBusy(true);
    setError(null);
    // The package is written as given by the API, so an edit hands back the one the stage already has.
    const body = {
      sortOrder: order, environmentId: envId, dbConnectionId: chosenConnection,
      policyPackageId: stage?.policyPackageId ?? null, requiresApproval: approval, autoRequest: approval && autoRequest,
    };
    try {
      await (stage ? actions.updateStage(pipeline.id, stage.id, body) : actions.createStage(pipeline.id, body));
      onClose();
    } catch (err) {
      setError(messageOf(err, a.common.saveError));
    } finally {
      setBusy(false);
    }
  }

  return (
    <FormDialog title={stage ? d.titleEdit : d.titleNew} onClose={onClose} onSave={save} busy={busy} error={error} saveDisabled={envId === '' || chosenConnection === ''}>
      <div className="grid grid-cols-2 gap-3">
        <Field label={d.order}>
          <input className={fieldClass} type="number" min={1} value={order} onChange={(ev) => setOrder(Number(ev.target.value))} />
        </Field>
        <Field label={d.environment}>
          <select className={fieldClass} value={envId} onChange={(ev) => { setEnvironmentId(ev.target.value); setConnectionId(''); }}>
            {(environments.data ?? []).map((env) => <option key={env.id} value={env.id}>{env.code} — {env.name}</option>)}
          </select>
        </Field>
      </div>
      <Field label={d.connection}>
        <select className={fieldClass} value={chosenConnection} onChange={(ev) => setConnectionId(ev.target.value)}>
          {candidates.map((item) => <option key={item.id} value={item.id}>{item.displayName}</option>)}
        </select>
      </Field>
      <label className="flex items-center gap-2 text-[13px]">
        <input type="checkbox" checked={approval} onChange={(ev) => setApproval(ev.target.checked)} /> {d.approval}
      </label>
      <label className="flex items-center gap-2 text-[13px]">
        <input type="checkbox" checked={approval && autoRequest} disabled={!approval} onChange={(ev) => setAutoRequest(ev.target.checked)} /> {d.autoRequest}
      </label>
      <p className="text-[12px] text-text-2">{d.autoRequestHint}</p>
    </FormDialog>
  );
}
