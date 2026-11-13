import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';

import { usePolicyChecks, usePolicyChecksSummary } from '../../api/governance';
import type { PolicyCheck } from '../../api/governance';
import DataTable from '../../components/DataTable';
import type { Column } from '../../components/DataTable';
import FilterSelect from '../../components/FilterSelect';
import KpiCard from '../../components/KpiCard';
import PageHeader from '../../components/PageHeader';
import StatusBadge from '../../components/StatusBadge';
import { EmptyState, ErrorCard } from '../../components/States';
import { useCan } from '../../auth/useCan';
import { fill } from '../../i18n';
import { useLocale, useMessages } from '../../i18n/useMessages';
import { formatNumber } from '../../lib/format';
import Topbar from '../../shell/Topbar';
import { crumb } from '../../shell/crumbs';
import GovernanceTabs from './GovernanceTabs';
import { severityLabel, severityTone } from './rules';

const KINDS = ['BUILTIN', 'REGEX', 'SQL_PATTERN', 'SPEL', 'CHAINED'];

/**
 * Screen 07, list view. A row opens the editor (CheckEditor). This also shows, importantly,
 * which checks the engine really implements: a check that is listed but not implemented
 * protects nothing, and the screen must not suggest otherwise.
 */
export default function PolicyLibrary() {
  const t = useMessages();
  const can = useCan();
  const locale = useLocale();
  const navigate = useNavigate();
  const [category, setCategory] = useState('');
  const [kind, setKind] = useState('');
  const [enabled, setEnabled] = useState('');
  const [search, setSearch] = useState('');
  const [q, setQ] = useState('');

  useEffect(() => {
    const timer = setTimeout(() => setQ(search.trim()), 300);
    return () => clearTimeout(timer);
  }, [search]);

  const checks = usePolicyChecks({ category, kind, enabled, q });
  const summary = usePolicyChecksSummary();
  // Categories come from an unfiltered request so choosing one does not make the others vanish.
  const all = usePolicyChecks({ category: '', kind: '', enabled: '', q: '' });
  const categories = Array.from(new Set((all.data ?? []).map((c) => c.category))).sort();

  const columns: Column<PolicyCheck>[] = [
    {
      key: 'code', header: t.governance.library.columns.code,
      cell: (c) => (
        <div>
          <code className="font-mono text-[12px] font-semibold">{c.code}</code>
          {c.description && <div className="max-w-[560px] truncate text-[11.5px] text-text-3">{c.description}</div>}
        </div>
      ),
    },
    { key: 'category', header: t.governance.library.columns.category, cell: (c) => c.category },
    { key: 'kind', header: t.governance.library.columns.kind, cell: (c) => <code className="font-mono text-[11.5px]">{c.kind}</code> },
    {
      key: 'severity', header: t.governance.library.columns.severity,
      cell: (c) => (
        <StatusBadge tone={severityTone(c.defaultSeverity)}>
          {`${severityLabel(c.defaultSeverity, t)} (${c.defaultSeverity})`}
        </StatusBadge>
      ),
    },
    {
      key: 'enabled', header: t.governance.library.columns.enabled,
      cell: (c) => (
        <StatusBadge tone={c.enabled ? 'success' : 'neutral'}>
          {c.enabled ? t.governance.library.enabled : t.governance.library.disabled}
        </StatusBadge>
      ),
    },
    { key: 'packages', header: t.governance.library.columns.packages, align: 'right', cell: (c) => formatNumber(c.packages, locale) },
    {
      key: 'engine', header: t.governance.library.columns.engine,
      cell: (c) =>
        c.kind === 'BUILTIN' ? (
          <StatusBadge tone={c.implemented ? 'success' : 'warning'}>
            {c.implemented ? t.governance.library.implemented : t.governance.library.notImplemented}
          </StatusBadge>
        ) : (
          t.common.none
        ),
    },
  ];

  const s = summary.data;

  return (
    <>
      <Topbar trail={[crumb(t.governance.breadcrumbRoot, '/govern'), t.governance.library.title]} />
      <PageHeader
        title={t.governance.library.title}
        subtitle={t.governance.library.subtitle}
        right={
          <>
            <input
              type="search"
              value={search}
              onChange={(event) => setSearch(event.target.value)}
              placeholder={t.governance.library.searchPlaceholder}
              aria-label={t.governance.library.searchLabel}
              className="w-[170px] rounded-control border border-border bg-surface px-3 py-1.5 text-[12px] shadow-card placeholder:text-text-3"
            />
            <FilterSelect
              label={t.governance.library.filters.category}
              value={category}
              options={[{ value: '', label: t.governance.library.filters.category }, ...categories.map((c) => ({ value: c, label: c }))]}
              onChange={setCategory}
            />
            <FilterSelect
              label={t.governance.library.filters.kind}
              value={kind}
              options={[{ value: '', label: t.governance.library.filters.kind }, ...KINDS.map((k) => ({ value: k, label: k }))]}
              onChange={setKind}
            />
            {can('policy:edit') && (
              <button type="button" onClick={() => navigate('/govern/checks/new')}
                className="rounded-control bg-primary px-3 py-1.5 text-[12px] font-semibold text-white">
                {t.governance.library.newCheck}
              </button>
            )}
            <FilterSelect
              label={t.governance.library.filters.enabled}
              value={enabled}
              options={[
                { value: '', label: t.governance.library.filters.enabled },
                { value: 'true', label: t.governance.library.filters.enabledOn },
                { value: 'false', label: t.governance.library.filters.enabledOff },
              ]}
              onChange={setEnabled}
            />
          </>
        }
      />
      <GovernanceTabs />
      <p className="mb-3 text-[12px] text-text-2">{t.governance.library.readOnly}</p>

      <section className="rounded-card border border-border bg-surface shadow-card">
        {checks.isError ? (
          <ErrorCard message={t.governance.library.error} onRetry={() => checks.refetch()} />
        ) : checks.data && checks.data.length === 0 ? (
          <EmptyState title={t.governance.library.empty.title} hint={t.governance.library.empty.hint} />
        ) : (
          <>
            {checks.data && (
              <div className="px-4 py-3 text-right text-[12px] text-text-2">
                {fill(t.governance.library.meta, { n: formatNumber(checks.data.length, locale) })}
              </div>
            )}
            <DataTable
              columns={columns}
              rows={checks.data ?? []}
              rowKey={(c) => c.id}
              loading={checks.isLoading}
              skeletonRows={10}
              onRowClick={(c) => navigate(`/govern/checks/${c.id}`)}
              rowLabel={(c) => c.code}
              caption={t.governance.library.title}
            />
          </>
        )}
      </section>

      <div className="mt-3.5 grid grid-cols-4 gap-3.5">
        <KpiCard
          label={t.governance.library.summary.prebuilt}
          value={s ? formatNumber(s.prebuilt, locale) : t.common.none}
          sub={s ? fill(t.governance.library.summary.prebuiltSub, { n: s.prebuiltCategories, implemented: s.implemented }) : undefined}
          hero
        />
        <KpiCard
          label={t.governance.library.summary.custom}
          value={s ? formatNumber(s.custom, locale) : t.common.none}
          sub={t.governance.library.summary.customSub}
        />
        <KpiCard
          label={t.governance.library.summary.packages}
          value={s ? formatNumber(s.packages, locale) : t.common.none}
          sub={t.governance.library.summary.packagesSub}
        />
        <KpiCard
          label={t.governance.library.summary.central}
          value={s ? formatNumber(s.pipelines, locale) : t.common.none}
          sub={s ? fill(t.governance.library.summary.centralSub, { pipelines: s.pipelines, connections: s.connections }) : undefined}
        />
      </div>
    </>
  );
}
