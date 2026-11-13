import { useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import DriftShare from './DriftShare';
import { Bar, CartesianGrid, ComposedChart, Legend, Line, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';

import { useDriftRuns, useDriftSummary } from '../../api/drift';
import type { DriftFilter, DriftPeriod, DriftRun } from '../../api/drift';
import DataTable from '../../components/DataTable';
import type { Column } from '../../components/DataTable';
import EnvBadge from '../../components/EnvBadge';
import FilterSelect from '../../components/FilterSelect';
import KpiCard from '../../components/KpiCard';
import PageHeader from '../../components/PageHeader';
import StatusBadge from '../../components/StatusBadge';
import { EmptyState, ErrorCard } from '../../components/States';
import { fill } from '../../i18n';
import { useLocale, useMessages } from '../../i18n/useMessages';
import { formatDecimal, formatNumber, formatPercent, formatShortDateTime } from '../../lib/format';
import Topbar from '../../shell/Topbar';
import { crumb } from '../../shell/crumbs';
import RunDrawer from './RunDrawer';
import { barWidths, driftTone, itemsLabel, rowTone, trend } from './rules';

const PERIODS: DriftPeriod[] = ['7d', '30d', '90d', 'all'];
const FILTERS: DriftFilter[] = ['all', 'drift', 'clean'];
const BAR_COLORS = ['var(--color-primary)', 'var(--color-warning)', 'var(--color-success)', 'var(--color-purple)', 'var(--color-teal)', 'var(--color-danger)'];

/** Screen 09: schema drift over time, what diverged, and every run. */
export default function Drift() {
  const t = useMessages();
  const d = t.drift;
  const locale = useLocale();
  const [params, setParams] = useSearchParams();
  const [open, setOpen] = useState<string | null>(null);

  const period = (PERIODS.includes(params.get('period') as DriftPeriod) ? params.get('period') : '30d') as DriftPeriod;
  const filter = (FILTERS.includes(params.get('filter') as DriftFilter) ? params.get('filter') : 'all') as DriftFilter;
  const connection = params.get('connection') ?? '';
  const query = { period, filter, connection };

  const summary = useDriftSummary(query);
  const runs = useDriftRuns(query);
  const s = summary.data;

  const update = (key: string, value: string) => {
    const next = new URLSearchParams(params);
    if (value) {
      next.set(key, value);
    } else {
      next.delete(key);
    }
    setParams(next, { replace: true });
  };

  const columns: Column<DriftRun>[] = [
    { key: 'command', header: d.runs.columns.command, cell: () => <code className="font-mono text-[12px]">diff</code> },
    {
      key: 'status', header: d.runs.columns.status,
      cell: (r) => <StatusBadge tone={r.status === 'SUCCESS' ? 'success' : 'danger'}>{r.status === 'SUCCESS' ? d.runs.success : d.runs.failed}</StatusBadge>,
    },
    {
      key: 'drift', header: d.runs.columns.drift,
      cell: (r) => (
        <StatusBadge tone={driftTone(r)}>{r.hasDrift == null ? d.runs.unknown : r.hasDrift ? d.runs.withDrift : d.runs.clean}</StatusBadge>
      ),
    },
    { key: 'items', header: d.runs.columns.items, cell: (r) => <code className="font-mono text-[12px]">{itemsLabel(r)}</code> },
    { key: 'database', header: d.runs.columns.database, cell: (r) => r.connection },
    { key: 'env', header: d.runs.columns.environment, cell: (r) => <EnvBadge code={r.environment} /> },
    { key: 'date', header: d.runs.columns.date, align: 'right', cell: (r) => formatShortDateTime(r.startedAt, locale) },
  ];

  const periodOptions = PERIODS.map((p) => ({ value: p, label: d.periods[p] }));
  const filterOptions = FILTERS.map((f) => ({ value: f, label: d.filters[f] }));
  const weekly = (s?.weekly ?? []).map((w) => ({ ...w, label: fill(d.chart.week, { n: w.week }), rate: w.rate ?? undefined }));
  const direction = s ? trend(s.weekly) : null;
  const bars = barWidths(s?.diverged.byType ?? []);
  const objects = bars.reduce((sum, b) => sum + b.count, 0);

  return (
    <>
      <Topbar trail={[crumb(d.breadcrumbRoot, '/operations'), d.title]} />
      <PageHeader
        title={d.title}
        subtitle={d.subtitle}
        right={
          <>
            <DriftShare search={params.toString()} onOpen={(search) => setParams(new URLSearchParams(search), { replace: true })} />
            <FilterSelect label={d.periods[period]} value={period} options={periodOptions} onChange={(v) => update('period', v === '30d' ? '' : v)} />
            <FilterSelect label={d.filters[filter]} value={filter} options={filterOptions} onChange={(v) => update('filter', v === 'all' ? '' : v)} />
          </>
        }
      />

      <div className="mb-3.5 grid grid-cols-6 gap-3.5">
        <KpiCard hero label={d.kpi.rate}
          value={s?.driftRate == null ? t.common.none : formatPercent(s.driftRate, locale)}
          sub={s ? fill(d.kpi.rateSub, { part: s.driftDetected, whole: s.driftDetected + s.cleanChecks }) : undefined} />
        <KpiCard label={d.kpi.detected} value={s ? formatNumber(s.driftDetected, locale) : t.common.none} tone="warning" />
        <KpiCard label={d.kpi.clean} value={s ? formatNumber(s.cleanChecks, locale) : t.common.none} tone="success" />
        <KpiCard label={d.kpi.items} value={s ? formatNumber(s.items.total, locale) : t.common.none} tone="warning"
          sub={s ? fill(d.kpi.itemsSub, { added: s.items.added, removed: s.items.removed, modified: s.items.modified }) : undefined} />
        <KpiCard label={d.kpi.average} value={s?.averageItems == null ? t.common.none : formatDecimal(s.averageItems, 1, locale)} sub={d.kpi.averageSub} />
        <KpiCard label={d.kpi.coverage} value={s ? formatNumber(s.coverage.monitored, locale) : t.common.none}
          sub={s ? fill(d.kpi.coverageSub, { total: s.coverage.total }) : undefined} />
      </div>

      <div className="mb-3.5 grid grid-cols-[1fr_360px] gap-3.5">
        <section className="rounded-card border border-border bg-surface p-4 shadow-card">
          <div className="flex items-center">
            <h2 className="text-[15px] font-semibold">{d.chart.title}</h2>
            {direction && (
              <span className="ml-auto text-[12px] font-semibold text-text-2">
                {direction.direction === 'down' ? '↘' : direction.direction === 'up' ? '↗' : '→'} {direction.percent}%
              </span>
            )}
          </div>
          {weekly.every((w) => w.rate === undefined && w.items === 0) ? (
            <p className="py-10 text-center text-[12.5px] text-text-2">{d.chart.empty}</p>
          ) : (
            <div className="mt-2 h-[220px]" role="img" aria-label={d.chart.title}>
              <ResponsiveContainer width="100%" height="100%">
                <ComposedChart data={weekly}>
                  <CartesianGrid stroke="var(--color-border)" vertical={false} />
                  <XAxis dataKey="label" tick={{ fontSize: 11 }} />
                  <YAxis yAxisId="items" allowDecimals={false} tick={{ fontSize: 11 }} />
                  <YAxis yAxisId="rate" orientation="right" domain={[0, 100]} tick={{ fontSize: 11 }} unit="%" />
                  <Tooltip />
                  <Legend />
                  <Bar yAxisId="items" dataKey="items" name={d.chart.items} fill="var(--color-primary)" fillOpacity={0.45} />
                  <Line yAxisId="rate" dataKey="rate" name={d.chart.rate} stroke="var(--color-danger)" strokeWidth={2} dot connectNulls />
                </ComposedChart>
              </ResponsiveContainer>
            </div>
          )}
        </section>

        <section className="rounded-card border border-border bg-surface p-4 shadow-card">
          <div className="flex items-center">
            <h2 className="text-[15px] font-semibold">{d.diverged.title}</h2>
            {s?.diverged.connection && (
              <span className="ml-auto text-[12px] text-text-2">{fill(d.diverged.meta, { n: objects, connection: s.diverged.connection })}</span>
            )}
          </div>
          {bars.length === 0 ? (
            <p className="py-8 text-center text-[12.5px] text-text-2">{d.diverged.empty}</p>
          ) : (
            <ul className="mt-3 flex flex-col gap-2.5">
              {bars.map((bar, index) => (
                <li key={bar.type} className="text-[12.5px]">
                  <div className="flex items-center">
                    <span>{d.objectTypes[bar.type as keyof typeof d.objectTypes] ?? bar.type}</span>
                    <span className="ml-auto font-mono">{bar.count}</span>
                  </div>
                  <div className="mt-1 h-2 rounded bg-surface-alt">
                    <div className="h-2 rounded" style={{ width: `${bar.width}%`, background: BAR_COLORS[index % BAR_COLORS.length] }} />
                  </div>
                </li>
              ))}
            </ul>
          )}
          <p className="mt-3 text-[11.5px] text-text-3">{fill(d.diverged.compared, { reference: d.diverged.reference })}</p>
        </section>
      </div>

      <section className="rounded-card border border-border bg-surface shadow-card">
        <div className="flex items-center px-4 py-3">
          <h2 className="text-[15px] font-semibold">{d.runs.title}</h2>
          {runs.data && (
            <span className="ml-auto text-[12px] text-text-2">
              {fill(d.runs.meta, { shown: formatNumber(runs.data.content.length, locale), total: formatNumber(runs.data.totalElements, locale) })}
            </span>
          )}
        </div>
        {runs.isError ? (
          <ErrorCard message={d.runs.error} onRetry={() => runs.refetch()} />
        ) : runs.data && runs.data.content.length === 0 ? (
          <EmptyState title={d.runs.empty.title} hint={d.runs.empty.hint} />
        ) : (
          <DataTable
            columns={columns}
            rows={runs.data?.content ?? []}
            rowKey={(r) => r.id}
            loading={runs.isLoading}
            onRowClick={(r) => setOpen(r.id)}
            rowLabel={(r) => fill(d.runs.rowLabel, { connection: r.connection })}
            rowClass={(r) => (rowTone(r) === 'danger' ? 'bg-row-danger' : rowTone(r) === 'warning' ? 'bg-row-warn' : undefined)}
            caption={d.runs.title}
          />
        )}
      </section>

      {open && <RunDrawer runId={open} onClose={() => setOpen(null)} />}
    </>
  );
}
