import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';

import {
  useAllChecks, useLibraryMutations, usePolicyPackage, usePolicyPackages,
} from '../../api/governance';
import type { PolicyPackageSummary } from '../../api/governance';
import DataTable from '../../components/DataTable';
import type { Column } from '../../components/DataTable';
import PageHeader from '../../components/PageHeader';
import { EmptyState, ErrorCard } from '../../components/States';
import StatusBadge from '../../components/StatusBadge';
import { useCan } from '../../auth/useCan';
import { fill } from '../../i18n';
import { useMessages } from '../../i18n/useMessages';
import Topbar from '../../shell/Topbar';
import GovernanceTabs from './GovernanceTabs';
import { severityLabel } from './rules';

const input = 'rounded-control border border-border bg-surface px-3 py-1.5 text-[13px] disabled:bg-surface-alt';

/** The "Pacotes" tab of the library: list. */
export function PackagesList() {
  const t = useMessages();
  const can = useCan();
  const p = t.governance.packages;
  const navigate = useNavigate();
  const packages = usePolicyPackages();

  const columns: Column<PolicyPackageSummary>[] = [
    {
      key: 'name', header: p.columns.name,
      cell: (r) => (
        <span className="flex items-center gap-2">
          <code className="font-mono text-[12.5px] font-semibold">{r.name}</code>
          {r.isDefault && <StatusBadge tone="info">{p.defaultBadge}</StatusBadge>}
        </span>
      ),
    },
    { key: 'checks', header: p.columns.checks, align: 'right', cell: (r) => r.checks },
    {
      key: 'thresholds', header: p.columns.thresholds,
      cell: (r) => fill(p.thresholds, { prod: r.blockThresholdProd, nonprod: r.blockThresholdNonprod }),
    },
    {
      key: 'assigned', header: p.columns.assigned,
      cell: (r) => (r.changelogs + r.stages === 0 ? p.unassigned : fill(p.assigned, { changelogs: r.changelogs, stages: r.stages })),
    },
  ];

  return (
    <>
      <Topbar trail={[t.governance.breadcrumbRoot, p.title]} />
      <PageHeader
        title={p.title}
        subtitle={p.subtitle}
        right={can('policy:edit') &&
          <button type="button" onClick={() => navigate('/govern/packages/new')}
            className="rounded-control bg-primary px-3 py-1.5 text-[12px] font-semibold text-white">
            {p.newPackage}
          </button>
        }
      />
      <GovernanceTabs />
      <section className="rounded-card border border-border bg-surface shadow-card">
        {packages.isError ? (
          <ErrorCard message={p.error} onRetry={() => packages.refetch()} />
        ) : packages.data && packages.data.length === 0 ? (
          <EmptyState title={p.empty.title} hint={p.empty.hint} />
        ) : (
          <DataTable
            columns={columns}
            rows={packages.data ?? []}
            rowKey={(r) => r.id}
            loading={packages.isLoading}
            onRowClick={(r) => navigate(`/govern/packages/${r.id}`)}
            rowLabel={(r) => r.name}
            caption={p.title}
          />
        )}
      </section>
    </>
  );
}

type Entry = { code: string; severityOverride: number | null };

/** A package: name, thresholds and its checks, each with an optional severity of its own. */
export function PackageEditor() {
  const t = useMessages();
  const p = t.governance.packages;
  const e = p.editor;
  const navigate = useNavigate();
  const { id } = useParams();
  const creating = id === undefined;
  const detail = usePolicyPackage(id);
  const all = useAllChecks();
  const mutations = useLibraryMutations();

  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [prod, setProd] = useState(3);
  const [nonprod, setNonprod] = useState(4);
  const [entries, setEntries] = useState<Entry[]>([]);
  const [failure, setFailure] = useState<string | null>(null);

  useEffect(() => {
    const d = detail.data;
    if (d) {
      setName(d.name);
      setDescription(d.description ?? '');
      setProd(d.blockThresholdProd);
      setNonprod(d.blockThresholdNonprod);
      setEntries(d.checks.map((c) => ({ code: c.code, severityOverride: c.severityOverride ?? null })));
    }
  }, [detail.data]);

  const isDefault = name === 'default' && !creating;
  const available = (all.data ?? []).filter((c) => !entries.some((en) => en.code === c.code));
  const defaults = new Map((all.data ?? []).map((c) => [c.code, c.defaultSeverity]));

  async function save() {
    setFailure(null);
    if (!name.trim()) {
      setFailure(e.errors.name);
      return;
    }
    const body = {
      name: name.trim(), description, blockThresholdProd: prod, blockThresholdNonprod: nonprod,
      checks: entries.map((en) => ({ code: en.code, severityOverride: en.severityOverride })),
    };
    try {
      if (creating) {
        const created = await mutations.createPackage(body);
        navigate(`/govern/packages/${created.id}`, { replace: true });
      } else {
        await mutations.updatePackage(id!, body);
      }
    } catch (err) {
      setFailure(err instanceof Error && err.message ? err.message : e.errors.save);
    }
  }

  async function remove() {
    if (!window.confirm(e.confirmDelete)) {
      return;
    }
    try {
      await mutations.deletePackage(id!);
      navigate('/govern/packages');
    } catch (err) {
      setFailure(err instanceof Error && err.message ? err.message : e.errors.save);
    }
  }

  const thresholdSelect = (label: string, value: number, onChange: (n: number) => void) => (
    <label className="flex flex-col gap-1 text-[12px] font-semibold">
      {label}
      <select className={input} value={value} onChange={(ev) => onChange(Number(ev.target.value))}>
        {[1, 2, 3, 4].map((n) => <option key={n} value={n}>{n} {severityLabel(n, t)}</option>)}
      </select>
    </label>
  );

  return (
    <>
      <Topbar trail={[t.governance.breadcrumbRoot, p.title, creating ? e.titleNew : name]} />
      <PageHeader title={creating ? e.titleNew : name} subtitle={p.subtitle} />
      <GovernanceTabs />
      {isDefault && <p className="mb-3 rounded-control bg-info-bg px-3 py-2 text-[12.5px] text-info">{e.defaultNotice}</p>}

      <section className="flex flex-col gap-4 rounded-card border border-border bg-surface p-5 shadow-card">
        <div className="grid grid-cols-2 gap-4">
          <label className="flex flex-col gap-1 text-[12px] font-semibold">
            {e.name}
            <input className={input} value={name} disabled={isDefault} onChange={(ev) => setName(ev.target.value)} />
          </label>
          <label className="flex flex-col gap-1 text-[12px] font-semibold">
            {e.description}
            <input className={input} value={description} onChange={(ev) => setDescription(ev.target.value)} />
          </label>
          {thresholdSelect(e.thresholdProd, prod, setProd)}
          {thresholdSelect(e.thresholdNonprod, nonprod, setNonprod)}
        </div>

        <div>
          <h2 className="mb-2 text-[14px] font-semibold">{e.checks}</h2>
          <table className="w-full border-collapse text-[13px]">
            <tbody>
              {entries.map((en) => (
                <tr key={en.code} className="h-9 border-t border-border">
                  <td className="px-2"><code className="font-mono text-[12px]">{en.code}</code></td>
                  <td className="px-2">
                    <select
                      aria-label={`${e.override}: ${en.code}`}
                      className={input}
                      value={en.severityOverride ?? ''}
                      onChange={(ev) => setEntries((list) => list.map((x) => x.code === en.code
                        ? { ...x, severityOverride: ev.target.value === '' ? null : Number(ev.target.value) } : x))}
                    >
                      <option value="">
                        {e.inherit}{defaults.has(en.code) ? ` (${severityLabel(defaults.get(en.code)!, t)})` : ''}
                      </option>
                      {[0, 1, 2, 3, 4].map((s) => <option key={s} value={s}>{s} {severityLabel(s, t)}</option>)}
                    </select>
                  </td>
                  <td className="px-2 text-right">
                    <button type="button" onClick={() => setEntries((list) => list.filter((x) => x.code !== en.code))}
                      className="text-[12px] font-semibold text-danger hover:underline">
                      {e.remove}
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          <select
            aria-label={e.add}
            className={`${input} mt-2`}
            value=""
            onChange={(ev) => ev.target.value && setEntries((list) => [...list, { code: ev.target.value, severityOverride: null }])}
          >
            <option value="">{e.add}</option>
            {available.map((c) => <option key={c.code} value={c.code}>{c.code}</option>)}
          </select>
        </div>

        <div className="flex items-center gap-2">
          {failure && <span role="alert" className="text-[12.5px] text-danger">{failure}</span>}
          <div className="ml-auto flex gap-2">
            {!creating && !isDefault && (
              <button type="button" onClick={remove}
                className="rounded-control border border-danger/40 px-3 py-1.5 text-[12px] font-semibold text-danger hover:bg-danger-bg">
                {e.delete}
              </button>
            )}
            <button type="button" onClick={() => navigate('/govern/packages')}
              className="rounded-control border border-border px-3 py-1.5 text-[12px] font-semibold hover:bg-surface-alt">
              {e.cancel}
            </button>
            <button type="button" onClick={save} className="rounded-control bg-primary px-3 py-1.5 text-[12px] font-semibold text-white">
              {e.save}
            </button>
          </div>
        </div>
      </section>
    </>
  );
}
