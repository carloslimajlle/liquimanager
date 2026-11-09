import { useState } from 'react';
import { ArrowRight } from 'lucide-react';
import { Link, Navigate, useNavigate, useSearchParams } from 'react-router-dom';

import { useEnforcement } from '../../api/governance';
import type { EnforcementFinding, EnforcementGate } from '../../api/governance';
import { usePipelines } from '../../api/matrix';
import DataTable from '../../components/DataTable';
import type { Column } from '../../components/DataTable';
import EnvBadge from '../../components/EnvBadge';
import FilterSelect from '../../components/FilterSelect';
import KpiCard from '../../components/KpiCard';
import PageHeader from '../../components/PageHeader';
import StatusBadge from '../../components/StatusBadge';
import { EmptyState, ErrorCard } from '../../components/States';
import { useCan } from '../../auth/useCan';
import { fill } from '../../i18n';
import { useLocale, useMessages } from '../../i18n/useMessages';
import { formatNumber, formatPercent, formatShortDateTime } from '../../lib/format';
import Topbar from '../../shell/Topbar';
import { ExceptionsPanel, RequestExceptionDialog } from './ExceptionsPanel';
import GovernanceTabs from './GovernanceTabs';
import { gateLabel, gateTone, severityEntries, severityLabel, severityTone } from './rules';

const PERIODS = ['7d', '30d', '90d'] as const;

function GateCard({ gate }: { gate: EnforcementGate }) {
  const t = useMessages();
  const blocked = gate.result === 'BLOCKED';
  const entries = severityEntries(gate.bySeverity);
  return (
    <div
      className={`min-w-0 flex-1 rounded-card border px-4 py-3 shadow-card ${
        blocked ? 'border-danger/30 bg-row-danger' : 'border-border bg-surface'
      }`}
    >
      <div className="flex items-center gap-2">
        <span
          aria-hidden
          className="size-2 rounded-full"
          style={{ background: `var(--env-${gate.env.toLowerCase()}-fg, var(--color-text-3))` }}
        />
        <span className="text-[11px] font-semibold tracking-[0.08em] uppercase">{gate.envName}</span>
        <span className="ml-auto">
          <StatusBadge tone={gateTone(gate.result)}>{gateLabel(gate.result, t)}</StatusBadge>
        </span>
      </div>
      <div className="mt-2 text-[12px] text-text-2">
        {gate.findings === 0
          ? t.governance.enforcement.gateClean
          : `${fill(gate.findings === 1 ? t.governance.enforcement.gateSummaryOne : t.governance.enforcement.gateSummaryMany, { n: gate.findings })} · ${entries
              .map(([name, count]) => `${count} ${name}`)
              .join(', ')}`}
      </div>
    </div>
  );
}

/** Screen 06: what the policy gates decided along one pipeline. */
export default function Enforcement() {
  const t = useMessages();
  const locale = useLocale();
  const canRequest = useCan()('exception:request');
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  const pipelines = usePipelines();
  const [period, setPeriod] = useState('7d');
  const [requesting, setRequesting] = useState(false);

  const pipelineId = params.get('pipeline') ?? pipelines.data?.[0]?.id;
  const enforcement = useEnforcement(pipelineId, period);
  const data = enforcement.data;
  const trail = [t.governance.breadcrumbRoot, t.governance.enforcement.title];

  if (!params.get('pipeline') && pipelines.data && pipelines.data.length > 0) {
    return <Navigate to={`/govern/enforcement?pipeline=${pipelines.data[0].id}`} replace />;
  }

  const pipelineOptions = (pipelines.data ?? []).map((p) => ({
    value: p.id, label: `${t.governance.enforcement.pipeline.toUpperCase()} ${p.name}`,
  }));
  const periodOptions = PERIODS.map((value) => ({ value, label: t.governance.enforcement.periods[value] }));

  const header = (
    <>
      <Topbar trail={trail} />
      <PageHeader
        title={t.governance.enforcement.title}
        subtitle={t.governance.enforcement.subtitle}
        right={
          <>
            {pipelineOptions.length > 0 && pipelineId && (
              <FilterSelect
                label={t.governance.enforcement.pipeline}
                value={pipelineId}
                options={pipelineOptions}
                onChange={(value) => setParams({ pipeline: value })}
              />
            )}
            <FilterSelect
              label={t.governance.enforcement.period}
              value={period}
              options={periodOptions}
              onChange={setPeriod}
            />
          </>
        }
      />
      <GovernanceTabs />
    </>
  );

  if (pipelines.isSuccess && pipelines.data.length === 0) {
    return (
      <>
        {header}
        <div className="rounded-card border border-border bg-surface shadow-card">
          <EmptyState
            title={t.governance.enforcement.empty.noPipelines}
            hint={t.governance.enforcement.empty.noPipelinesHint}
          />
        </div>
      </>
    );
  }

  if (enforcement.isError) {
    return (
      <>
        {header}
        <div className="rounded-card border border-border bg-surface shadow-card">
          <ErrorCard message={t.governance.enforcement.error} onRetry={() => enforcement.refetch()} />
        </div>
      </>
    );
  }

  const columns: Column<EnforcementFinding>[] = [
    {
      key: 'severity', header: t.governance.enforcement.findings.columns.severity,
      cell: (f) => <StatusBadge tone={severityTone(f.severity)}>{severityLabel(f.severity, t)}</StatusBadge>,
    },
    { key: 'check', header: t.governance.enforcement.findings.columns.check, cell: (f) => <code className="font-mono text-[12px]">{f.check}</code> },
    {
      key: 'changeset', header: t.governance.enforcement.findings.columns.changeset,
      cell: (f) => <code className="font-mono text-[12px]">{f.changeset ?? t.common.none}</code>,
    },
    { key: 'database', header: t.governance.enforcement.findings.columns.database, cell: (f) => f.connection },
    { key: 'env', header: t.governance.enforcement.findings.columns.environment, cell: (f) => <EnvBadge code={f.env} /> },
    {
      key: 'gate', header: t.governance.enforcement.findings.columns.gate,
      cell: (f) => <StatusBadge tone={gateTone(f.result)}>{gateLabel(f.result, t)}</StatusBadge>,
    },
    { key: 'time', header: t.governance.enforcement.findings.columns.time, cell: (f) => formatShortDateTime(f.at, locale) },
  ];

  const kpis = data?.kpis;
  const blocked = data?.latestBlocked;

  return (
    <>
      {header}

      <div className="mb-3.5 grid grid-cols-4 gap-3.5">
        <KpiCard
          label={t.governance.enforcement.stats.evaluations}
          value={kpis ? formatNumber(kpis.evaluations, locale) : t.common.none}
          hero
        />
        <KpiCard
          label={t.governance.enforcement.stats.violations}
          value={kpis ? formatNumber(kpis.violations, locale) : t.common.none}
          tone="warning"
        />
        <KpiCard
          label={t.governance.enforcement.stats.blocked}
          value={kpis ? formatNumber(kpis.blocked, locale) : t.common.none}
          tone="danger"
        />
        <KpiCard
          label={t.governance.enforcement.stats.passRate}
          value={kpis?.passRate == null ? t.common.none : formatPercent(kpis.passRate, locale)}
          tone="success"
        />
      </div>

      <section aria-label={t.governance.enforcement.gatesLabel} className="mb-3.5 flex items-stretch gap-2">
        {(data?.gates ?? []).map((gate, index) => (
          <div key={gate.stageId} className="flex min-w-0 flex-1 items-center gap-2">
            <GateCard gate={gate} />
            {index < (data?.gates.length ?? 0) - 1 && <ArrowRight size={16} aria-hidden className="shrink-0 text-text-3" />}
          </div>
        ))}
      </section>

      <div className="grid grid-cols-[1fr_300px] items-start gap-3.5">
        <section className="rounded-card border border-border bg-surface shadow-card">
          <div className="flex items-center px-4 py-3">
            <h2 className="text-[15px] font-semibold">{t.governance.enforcement.findings.title}</h2>
            {data && (
              <span className="ml-auto text-[12px] text-text-2">
                {fill(t.governance.enforcement.findings.meta, {
                  shown: formatNumber(data.findings.length, locale),
                  total: formatNumber(data.totalFindings, locale),
                })}
              </span>
            )}
          </div>
          {data && data.findings.length === 0 ? (
            <EmptyState
              title={t.governance.enforcement.findings.empty.title}
              hint={t.governance.enforcement.findings.empty.hint}
            />
          ) : (
            <DataTable
              columns={columns}
              rows={data?.findings ?? []}
              rowKey={(f) => `${f.operationId}-${f.check}-${f.changeset ?? ''}`}
              loading={enforcement.isLoading}
              onRowClick={(f) => navigate(`/operations/${f.operationId}`)}
              rowLabel={(f) => fill(t.governance.enforcement.findings.rowLabel, { check: f.check })}
              rowClass={(f) => (f.result === 'BLOCKED' ? 'bg-row-danger' : undefined)}
              caption={t.governance.enforcement.findings.title}
            />
          )}
        </section>

        <aside className="flex flex-col gap-3.5">
          <section
            className={`rounded-card border bg-surface p-4 shadow-card ${blocked ? 'border-danger/30' : 'border-border'}`}
          >
            <div className="flex items-center gap-2">
              <h2 className="text-[14px] font-semibold">{t.governance.enforcement.blocked.title}</h2>
              {blocked && <StatusBadge tone="danger">{t.governance.enforcement.blocked.badge}</StatusBadge>}
            </div>
            {blocked ? (
              <>
                <div className="mt-3 flex items-center gap-2">
                  <StatusBadge tone={severityTone(blocked.severity)}>{severityLabel(blocked.severity, t)}</StatusBadge>
                  <code className="font-mono text-[12px]">{blocked.check}</code>
                </div>
                <p className="mt-2 text-[12.5px] text-text-2">{blocked.message}</p>
                {blocked.changeset && (
                  <div className="mt-3">
                    <div className="text-[10.5px] font-semibold tracking-[0.08em] text-text-3 uppercase">
                      {t.governance.enforcement.blocked.offender}
                    </div>
                    <code className="font-mono text-[12px]">{blocked.changeset}</code>
                  </div>
                )}
                <Link
                  to={`/operations/${blocked.operationId}`}
                  className="mt-3 inline-block text-[12px] font-semibold text-primary hover:underline"
                >
                  {t.governance.enforcement.blocked.viewOperation}
                </Link>
                {canRequest && (
                  <button
                    type="button"
                    onClick={() => setRequesting(true)}
                    className="mt-3 ml-3 rounded-control border border-border px-2 py-0.5 text-[12px] font-semibold hover:bg-surface-alt"
                  >
                    {t.governance.exceptions.request}
                  </button>
                )}
              </>
            ) : (
              <p className="mt-2 text-[12.5px] text-text-2">{t.governance.enforcement.blocked.none}</p>
            )}
          </section>

          <ExceptionsPanel />

          <section className="rounded-card border border-border bg-surface p-4 shadow-card">
            <div className="flex items-center gap-2">
              <h2 className="text-[14px] font-semibold">{t.governance.breakFix.title}</h2>
              {(data?.breakFix?.length ?? 0) > 0 && (
                <span className="ml-auto">
                  <StatusBadge tone="warning">{fill(t.governance.breakFix.badge, { n: data?.breakFix?.length ?? 0 })}</StatusBadge>
                </span>
              )}
            </div>
            {(data?.breakFix?.length ?? 0) === 0 ? (
              <p className="mt-2 text-[12.5px] text-text-2">{t.governance.breakFix.none}</p>
            ) : (
              <ul className="mt-3 flex flex-col gap-2">
                {data?.breakFix?.map((b) => (
                  <li key={`${b.changeset}-${b.env}`} className="text-[12.5px]">
                    <code className="font-mono text-[12px]">{b.changeset}</code> <EnvBadge code={b.env} />
                    <div className="text-text-2">{b.author} · {formatShortDateTime(b.at, locale)}</div>
                  </li>
                ))}
              </ul>
            )}
            <p className="mt-3 text-[11.5px] text-text-3">{t.governance.breakFix.footnote}</p>
            <p className="mt-1 text-[11.5px] text-text-3">{t.governance.breakFix.driftSoon}</p>
          </section>
        </aside>
      </div>
      {requesting && blocked && (
        <RequestExceptionDialog finding={blocked} project={data?.pipeline.project ?? undefined} onClose={() => setRequesting(false)} />
      )}
    </>
  );
}
