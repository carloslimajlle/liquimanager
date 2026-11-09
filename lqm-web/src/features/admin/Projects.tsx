import { useState } from 'react';
import { Link } from 'react-router-dom';

import { useAdminActions, useChangelogs, useProjectList, useTeams } from '../../api/admin';
import type { Changelog, Project } from '../../api/admin';
import { usePolicyPackages } from '../../api/governance';
import { useCan } from '../../auth/useCan';
import DataTable from '../../components/DataTable';
import type { Column } from '../../components/DataTable';
import FormDialog, { Field, dangerLinkClass, fieldClass, linkClass, messageOf, primaryButton } from '../../components/FormDialog';
import PageHeader from '../../components/PageHeader';
import Pagination from '../../components/Pagination';
import StatusBadge from '../../components/StatusBadge';
import { EmptyState, ErrorCard } from '../../components/States';
import { fill } from '../../i18n';
import { useMessages } from '../../i18n/useMessages';
import AdminTabs from '../../shell/AdminTabs';
import Topbar from '../../shell/Topbar';
import { usePaged } from './paging';

const NEW_TEAM = '__new__';

/** /admin/projects (RF-CAD-02/03/07): the projects; each opens its own page (ProjectDetail). */
export default function Projects() {
  const t = useMessages();
  const a = t.admin;
  const p = a.projects;
  const projects = useProjectList();
  const teams = useTeams();
  const actions = useAdminActions();
  const editable = useCan()('catalog:edit');
  const paged = usePaged(projects.data);
  const [editing, setEditing] = useState<Project | 'new' | null>(null);

  const teamName = (id?: string | null) => (teams.data ?? []).find((team) => team.id === id)?.name ?? p.noTeam;
  const columns: Column<Project>[] = [
    { key: 'project', header: p.columns.project, cell: (r) => (
      <span><span className="font-semibold">{r.name}</span> <code className="font-mono text-[12px] text-text-3">{r.slug}</code></span>
    ) },
    { key: 'team', header: p.columns.team, cell: (r) => teamName(r.teamId) },
    { key: 'repo', header: p.columns.repo, cell: (r) => r.repoUrl ? <code className="font-mono text-[12px]">{r.repoUrl}</code> : a.common.none },
    { key: 'branch', header: p.columns.branch, cell: (r) => <code className="font-mono text-[12px]">{r.defaultBranch}</code> },
    {
      key: 'actions', header: a.common.actions, align: 'right',
      cell: (r) => (
        <span className="flex justify-end gap-3">
          <Link to={`/admin/projects/${r.id}`} className={linkClass}>{p.open}</Link>
          {editable && <button type="button" className={linkClass} onClick={() => setEditing(r)}>{a.common.edit}</button>}
          {editable && <button type="button" className={dangerLinkClass}
            onClick={() => window.confirm(p.confirmArchive) && actions.archiveProject(r.id)}>{a.common.archive}</button>}
        </span>
      ),
    },
  ];

  return (
    <>
      <Topbar trail={[a.breadcrumbRoot, p.title]} />
      <PageHeader title={p.title} subtitle={p.subtitle}
        right={editable && <button type="button" className={primaryButton} onClick={() => setEditing('new')}>{p.newProject}</button>} />
      <AdminTabs />
      <section className="mb-3.5 rounded-card border border-border bg-surface shadow-card">
        {projects.isError ? (
          <ErrorCard message={a.common.loadError} onRetry={() => projects.refetch()} />
        ) : projects.data && projects.data.length === 0 ? (
          <EmptyState title={p.empty.title} hint={p.empty.hint} />
        ) : (
          <>
            <DataTable columns={columns} rows={paged.rows} rowKey={(r) => r.id} loading={projects.isLoading} caption={p.title} />
            <div className="px-4 py-3"><Pagination page={paged.page} totalPages={paged.totalPages} onPage={paged.setPage} /></div>
          </>
        )}
      </section>
      {editing && <ProjectDialog project={editing === 'new' ? null : editing} onClose={() => setEditing(null)} />}
    </>
  );
}

function ProjectDialog({ project, onClose }: { project: Project | null; onClose: () => void }) {
  const t = useMessages();
  const a = t.admin;
  const d = a.projects.dialog;
  const actions = useAdminActions();
  const teams = useTeams();
  const [slug, setSlug] = useState(project?.slug ?? '');
  const [name, setName] = useState(project?.name ?? '');
  const [team, setTeam] = useState(project?.teamId ?? '');
  const [newTeam, setNewTeam] = useState('');
  const [repo, setRepo] = useState(project?.repoUrl ?? '');
  const [branch, setBranch] = useState(project?.defaultBranch ?? 'main');
  const [description, setDescription] = useState(project?.description ?? '');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function save() {
    setBusy(true);
    setError(null);
    try {
      let teamId: string | null = team === '' || team === NEW_TEAM ? null : team;
      if (team === NEW_TEAM && newTeam.trim() !== '') {
        teamId = (await actions.createTeam(newTeam.trim())).id;
      }
      const body = {
        slug: slug.trim(), name: name.trim(), teamId, repoUrl: repo.trim() || null,
        defaultBranch: branch.trim() || null, description: description.trim() || null,
      };
      await (project ? actions.updateProject(project.id, body) : actions.createProject(body));
      onClose();
    } catch (err) {
      setError(messageOf(err, a.common.saveError));
    } finally {
      setBusy(false);
    }
  }

  return (
    <FormDialog title={project ? d.titleEdit : d.titleNew} onClose={onClose} onSave={save} busy={busy} error={error}
      saveDisabled={slug.trim() === '' || name.trim() === '' || (team === NEW_TEAM && newTeam.trim() === '')}>
      <div className="grid grid-cols-2 gap-3">
        <Field label={d.slug} hint={d.slugHint}>
          <input className={`${fieldClass} font-mono`} value={slug} onChange={(ev) => setSlug(ev.target.value)} />
        </Field>
        <Field label={d.name}><input className={fieldClass} value={name} onChange={(ev) => setName(ev.target.value)} /></Field>
      </div>
      <Field label={d.team}>
        <select className={fieldClass} value={team} onChange={(ev) => setTeam(ev.target.value)}>
          <option value="">{a.projects.noTeam}</option>
          {(teams.data ?? []).map((item) => <option key={item.id} value={item.id}>{item.name}</option>)}
          <option value={NEW_TEAM}>{d.newTeam}</option>
        </select>
      </Field>
      {team === NEW_TEAM && (
        <Field label={d.newTeamName}><input className={fieldClass} value={newTeam} onChange={(ev) => setNewTeam(ev.target.value)} /></Field>
      )}
      <div className="grid grid-cols-[1fr_140px] gap-3">
        <Field label={d.repo} hint={d.repoHint}>
          <input className={`${fieldClass} font-mono`} value={repo} onChange={(ev) => setRepo(ev.target.value)} />
        </Field>
        <Field label={d.branch}>
          <input className={`${fieldClass} font-mono`} value={branch} onChange={(ev) => setBranch(ev.target.value)} />
        </Field>
      </div>
      <Field label={d.description}>
        <input className={fieldClass} value={description} onChange={(ev) => setDescription(ev.target.value)} />
      </Field>
    </FormDialog>
  );
}

/** The changelogs of one project, each with the policy package it is held to (the audited assign/unassign). */
export function ChangelogsPanel({ project }: { project: Project }) {
  const t = useMessages();
  const a = t.admin;
  const c = a.projects.changelogs;
  const changelogs = useChangelogs(project.id);
  const packages = usePolicyPackages();
  const actions = useAdminActions();
  const can = useCan();
  const [editing, setEditing] = useState<Changelog | 'new' | null>(null);
  const [message, setMessage] = useState<string | null>(null);

  async function choose(changelog: Changelog, packageId: string) {
    setMessage(null);
    try {
      if (packageId === '') {
        if (changelog.policyPackageId) await actions.unassignPackage(changelog.policyPackageId, { changelogId: changelog.id });
      } else {
        await actions.assignPackage(packageId, { changelogId: changelog.id });
      }
      setMessage(c.packageChanged);
    } catch (err) {
      setMessage(messageOf(err, a.common.saveError));
    }
  }

  const columns: Column<Changelog>[] = [
    { key: 'name', header: c.columns.name, cell: (r) => <span className="font-semibold">{r.name}</span> },
    { key: 'path', header: c.columns.path, cell: (r) => <code className="font-mono text-[12px]">{r.path}</code> },
    { key: 'format', header: c.columns.format, cell: (r) => r.format },
    {
      key: 'package', header: c.columns.package,
      cell: (r) => (
        <select aria-label={`${c.columns.package}: ${r.name}`} className={fieldClass} value={r.policyPackageId ?? ''}
          disabled={!can('policy:edit')} onChange={(ev) => choose(r, ev.target.value)}>
          <option value="">{c.noPackage}</option>
          {(packages.data ?? []).map((item) => <option key={item.id} value={item.id}>{item.name}</option>)}
        </select>
      ),
    },
    { key: 'verified', header: c.columns.verified, cell: (r) => <StatusBadge tone={r.verified ? 'success' : 'neutral'}>{r.verified ? a.common.yes : a.common.no}</StatusBadge> },
    {
      key: 'actions', header: a.common.actions, align: 'right',
      cell: (r) => can('catalog:edit') ? <button type="button" className={linkClass} onClick={() => setEditing(r)}>{a.common.edit}</button> : null,
    },
  ];

  return (
    <section className="rounded-card border border-border bg-surface shadow-card">
      <div className="flex items-center gap-2 px-4 pt-3">
        <h2 className="text-[14px] font-semibold">{fill(c.title, { project: project.name })}</h2>
        {can('catalog:edit') && <button type="button" className={`${linkClass} ml-auto`} onClick={() => setEditing('new')}>{c.add}</button>}
      </div>
      {message && <p role="status" className="mx-4 mt-2 rounded-control bg-info-bg px-3 py-2 text-[12.5px] text-info">{message}</p>}
      {changelogs.isError ? (
        <ErrorCard message={a.common.loadError} onRetry={() => changelogs.refetch()} />
      ) : changelogs.data && changelogs.data.length === 0 ? (
        <p className="px-4 py-6 text-[12.5px] text-text-2">{c.empty}</p>
      ) : (
        <DataTable columns={columns} rows={changelogs.data ?? []} rowKey={(r) => r.id} loading={changelogs.isLoading} caption={c.title} />
      )}
      {editing && <ChangelogDialog project={project} changelog={editing === 'new' ? null : editing} onClose={() => setEditing(null)} />}
    </section>
  );
}

function ChangelogDialog({ project, changelog, onClose }: { project: Project; changelog: Changelog | null; onClose: () => void }) {
  const t = useMessages();
  const a = t.admin;
  const d = a.projects.changelogs.dialog;
  const actions = useAdminActions();
  const [name, setName] = useState(changelog?.name ?? '');
  const [path, setPath] = useState(changelog?.path ?? 'db/changelog/db.changelog-master.xml');
  const [format, setFormat] = useState(changelog?.format ?? 'XML');
  const [verified, setVerified] = useState(changelog?.verified ?? true);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function save() {
    setBusy(true);
    setError(null);
    const body = { name: name.trim(), path: path.trim(), format, verified, policyPackageId: changelog?.policyPackageId ?? null };
    try {
      await (changelog ? actions.updateChangelog(project.id, changelog.id, body) : actions.createChangelog(project.id, body));
      onClose();
    } catch (err) {
      setError(messageOf(err, a.common.saveError));
    } finally {
      setBusy(false);
    }
  }

  return (
    <FormDialog title={changelog ? d.titleEdit : d.titleNew} onClose={onClose} onSave={save} busy={busy} error={error}
      saveDisabled={name.trim() === '' || path.trim() === ''}>
      <Field label={d.name}><input className={fieldClass} value={name} onChange={(ev) => setName(ev.target.value)} /></Field>
      <Field label={d.path}><input className={`${fieldClass} font-mono`} value={path} onChange={(ev) => setPath(ev.target.value)} /></Field>
      <Field label={d.format}>
        <select className={fieldClass} value={format} onChange={(ev) => setFormat(ev.target.value)}>
          {['XML', 'YAML', 'JSON', 'SQL'].map((f) => <option key={f} value={f}>{f}</option>)}
        </select>
      </Field>
      <label className="flex items-center gap-2 text-[13px]">
        <input type="checkbox" checked={verified} onChange={(ev) => setVerified(ev.target.checked)} /> {d.verified}
      </label>
    </FormDialog>
  );
}
