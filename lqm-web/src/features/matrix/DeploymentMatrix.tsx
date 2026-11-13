import { useEffect, useRef, useState } from 'react';
import { MoreHorizontal } from 'lucide-react';
import { Link, useNavigate, useParams } from 'react-router-dom';

import { useMatrix, usePipelines, usePromote, useTimeline } from '../../api/matrix';
import { useCan } from '../../auth/useCan';
import type { MatrixCell, MatrixRow, MatrixStage } from '../../api/matrix';
import FilterSelect from '../../components/FilterSelect';
import KpiCard from '../../components/KpiCard';
import PageHeader from '../../components/PageHeader';
import StatusBadge from '../../components/StatusBadge';
import OriginBadge from '../../components/OriginBadge';
import { EmptyState, ErrorCard } from '../../components/States';
import { fill } from '../../i18n';
import { useLocale, useMessages } from '../../i18n/useMessages';
import { formatDateTime, formatNumber } from '../../lib/format';
import Topbar from '../../shell/Topbar';
import { crumb } from '../../shell/crumbs';
import PendingPromotions from './PendingPromotions';
import PromoteDialog from './PromoteDialog';
import Timeline from './Timeline';
import { cellTone, numberWord, releasesOf, rowHasFailure, timelineReleases } from './rules';

/** The environment's colour dot in a column header, from the same variables as the badges. */
function envDot(code: string) {
  return `var(--env-${code.toLowerCase()}-fg, var(--color-text-3))`;
}

/** "release-2.1.0" -> "2.1.0", as the print shows it in the tag. */
function releaseNumber(release: string): string {
  return release.replace(/^release-/, '');
}

/** Screen 04: every changeset against every environment of one pipeline. */
export default function DeploymentMatrix({ mode = 'matrix' }: { mode?: 'matrix' | 'timeline' }) {
  const t = useMessages();
  const locale = useLocale();
  const navigate = useNavigate();
  const { id } = useParams();

  const pipelines = usePipelines();
  const [release, setRelease] = useState('');
  const [search, setSearch] = useState('');
  const [query, setQuery] = useState('');
  const matrix = useMatrix(id, release, query);
  const timeline = useTimeline(mode === 'timeline' ? id : undefined);
  const promote = usePromote(id);
  const [promoting, setPromoting] = useState<MatrixStage | null>(null);

  // Same pattern as the operations search: type freely, query after a pause.
  useEffect(() => {
    const timer = setTimeout(() => setQuery(search.trim()), 300);
    return () => clearTimeout(timer);
  }, [search]);

  const data = matrix.data;
  const current = pipelines.data?.find((p) => p.id === id);
  const trail = [crumb(t.matrix.breadcrumbRoot, '/pipelines'), crumb(t.matrix.title, '/pipelines')];

  const releaseOptions = [
    { value: '', label: t.matrix.releaseAll },
    ...(mode === 'timeline' ? timelineReleases(timeline.data ?? []) : releasesOf(data?.rows ?? []))
      .concat(release && !(mode === 'timeline' ? timelineReleases(timeline.data ?? []) : releasesOf(data?.rows ?? [])).includes(release) ? [release] : [])
      .map((value) => ({ value, label: releaseNumber(value) })),
  ];
  const pipelineOptions = (pipelines.data ?? []).map((p) => ({
    value: p.id, label: `${t.matrix.pipeline.toUpperCase()} ${p.name}`,
  }));

  const header = (
    <PageHeader
      title={t.matrix.title}
      subtitle={t.matrix.subtitle}
      right={
        <>
          {mode === 'matrix' && <input
            type="search"
            value={search}
            onChange={(event) => setSearch(event.target.value)}
            placeholder={t.matrix.searchPlaceholder}
            aria-label={t.matrix.searchLabel}
            className="w-[170px] rounded-control border border-border bg-surface px-3 py-1.5 text-[12px] shadow-card placeholder:text-text-3"
          />}
          <FilterSelect
            label={t.matrix.releaseAll}
            value={release}
            options={releaseOptions}
            onChange={setRelease}
          />
          {pipelineOptions.length > 0 && id && (
            <FilterSelect
              label={t.matrix.pipeline}
              value={id}
              options={pipelineOptions}
              onChange={(value) => navigate(`/pipelines/${value}/${mode}`)}
            />
          )}
          <div role="group" aria-label={t.matrix.modeLabel} className="inline-flex overflow-hidden rounded-control border border-border bg-surface shadow-card">
            {(['matrix', 'timeline'] as const).map((value) => (
              <button
                key={value}
                type="button"
                aria-pressed={mode === value}
                disabled={!id}
                onClick={() => navigate(`/pipelines/${id}/${value}`)}
                className={mode === value ? 'bg-primary px-3 py-1.5 text-[12px] font-semibold text-white' : 'px-3 py-1.5 text-[12px] font-semibold text-text-2 hover:bg-surface-alt'}
              >
                {t.matrix.modes[value]}
              </button>
            ))}
          </div>
        </>
      }
    />
  );

  if (mode === 'timeline') {
    return (
      <>
        <Topbar trail={[...trail, ...(current ? [current.name] : [])]} />
        {header}
        <Card><Timeline events={timeline.data} release={release} error={pipelines.isError || timeline.isError} onRetry={() => timeline.refetch()} /></Card>
      </>
    );
  }

  if (pipelines.isError || matrix.isError) {
    return (
      <>
        <Topbar trail={trail} />
        {header}
        <Card><ErrorCard message={t.matrix.error} onRetry={() => { pipelines.refetch(); matrix.refetch(); }} /></Card>
      </>
    );
  }

  if (data && data.stages.length === 0) {
    return (
      <>
        <Topbar trail={trail} />
        {header}
        <Card><EmptyState title={t.matrix.empty.noStages} hint={t.matrix.empty.noStagesHint} /></Card>
      </>
    );
  }

  const k = data?.kpis;
  const stageCodes = (data?.stages ?? []).map((s) => s.env).join(' · ');

  return (
    <>
      <Topbar trail={[...trail, ...(current ? [current.name] : [])]} />
      {header}

      <div className="mb-3.5 grid grid-cols-2 gap-3 xl:grid-cols-4">
        <KpiCard
          hero
          label={k ? fill(t.matrix.kpi.inSync, { n: numberWord(k.environments, t) }) : t.common.none}
          value={k ? formatNumber(k.inSyncAll, locale) : t.common.none}
          sub={k ? fill(t.matrix.kpi.inSyncSub, { total: formatNumber(k.total, locale) }) : undefined}
        />
        <KpiCard
          label={t.matrix.kpi.environments}
          value={k ? formatNumber(k.environments, locale) : t.common.none}
          sub={stageCodes}
        />
        <KpiCard
          label={t.matrix.kpi.awaiting}
          tone="default"
          value={k ? formatNumber(k.awaitingPromotion, locale) : t.common.none}
          sub={k
            ? (k.awaitingPromotion > 0 && k.awaitingPromotionEnvironment
                ? fill(t.matrix.kpi.awaitingSub, { env: k.awaitingPromotionEnvironment })
                : t.matrix.kpi.awaitingNone)
            : undefined}
        />
        <KpiCard
          label={t.matrix.kpi.failedFixed}
          tone="danger"
          value={k ? formatNumber(k.failedWithFix, locale) : t.common.none}
          sub={t.matrix.kpi.failedFixedSub}
        />
      </div>

      <PendingPromotions pipelineId={id} />

      <Card>
        {!pipelines.isLoading && (pipelines.data ?? []).length === 0 ? (
          <EmptyState title={t.matrix.empty.noPipelines} hint={t.matrix.empty.noPipelinesHint} />
        ) : data && data.rows.length === 0 ? (
          // The headers stay: the Promote button is on them, and the first deploy of a project is exactly when the grid is empty.
          <>
            <Grid stages={data.stages} rows={[]} loading={false} onPromote={setPromoting} />
            <EmptyState title={t.matrix.empty.noRows} hint={t.matrix.empty.noRowsHint} />
          </>
        ) : (
          <Grid stages={data?.stages ?? []} rows={data?.rows ?? []} loading={!data} onPromote={setPromoting} />
        )}
        {data && data.rows.length > 0 && (
          <div className="border-t border-border px-3.5 py-2.5 text-[12px] text-text-2">
            {fill(t.matrix.footer, {
              shown: formatNumber(data.rows.length, locale),
              total: formatNumber(data.totalElements, locale),
            })}
          </div>
        )}
      </Card>
      {promoting && (
        <PromoteDialog
          stage={promoting}
          pipelineId={id}
          releases={releasesOf(data?.rows ?? [])}
          defaultRelease={release}
          onPromote={promote}
          onClose={() => setPromoting(null)}
        />
      )}
    </>
  );
}

function Card({ children }: { children: React.ReactNode }) {
  return <div className="mb-6 overflow-x-auto rounded-card border border-border bg-surface shadow-card">{children}</div>;
}

function Grid({ stages, rows, loading, onPromote }: {
  stages: MatrixStage[];
  rows: MatrixRow[];
  loading: boolean;
  onPromote: (stage: MatrixStage) => void;
}) {
  const t = useMessages();
  const can = useCan();
  const locale = useLocale();

  return (
    <table className="w-full border-collapse text-[13px]">
      <thead>
        <tr>
          <th scope="col" className="px-3.5 py-2.5 text-left text-[11px] font-semibold tracking-[0.06em] text-text-2 uppercase">
            {t.matrix.columns.changeset}
          </th>
          {stages.map((stage, stageIndex) => (
            <th key={stage.stageId} scope="col" className="px-3.5 py-2 text-center">
              <div className="flex items-center justify-center gap-1.5 text-[11px] font-semibold tracking-[0.06em] text-text-2 uppercase">
                <span aria-hidden className="inline-block h-1.5 w-1.5 rounded-full" style={{ background: envDot(stage.env) }} />
                {stage.envName}
              </div>
              <div className="text-[11px] font-normal tracking-normal text-text-3 normal-case">{stage.connection}</div>
              {stageIndex > 0 && can(`promote:${stage.env.toLowerCase()}`) && (
                <button
                  type="button"
                  onClick={() => onPromote(stage)}
                  className="mt-1 rounded-control border border-border px-2 py-0.5 text-[11px] font-semibold tracking-normal text-primary normal-case hover:bg-surface-alt"
                >
                  {fill(t.matrix.promote.title, { env: stage.env })}
                </button>
              )}
            </th>
          ))}
        </tr>
      </thead>
      <tbody>
        {loading
          ? Array.from({ length: 6 }, (_, i) => (
              <tr key={i} aria-hidden className="h-12 border-t border-border">
                <td colSpan={stages.length + 1} className="px-3.5"><span className="block h-3 animate-pulse rounded bg-border" /></td>
              </tr>
            ))
          : rows.map((row) => (
              <tr key={row.changeset + row.filePath} className={`border-t border-border ${rowHasFailure(row) ? 'bg-row-danger' : ''} ${row.removed ? 'opacity-70' : ''}`}>
                <td className="px-3.5 py-2">
                  <div className={`font-mono font-bold ${row.removed ? 'line-through' : ''}`}>{row.changeset}</div>
                  <div className="mt-0.5 flex flex-wrap items-center gap-1.5 text-[12px] text-text-2">
                    <span>{row.author}</span>
                    <span className="font-mono text-text-3">{row.filePath}</span>
                    {row.release && (
                      <span className="rounded-badge bg-info-bg px-1.5 font-mono text-[11px] text-info">{releaseNumber(row.release)}</span>
                    )}
                    {row.onlyInBranch && <OriginBadge origin={row.origin} />}
                    {row.removed && <span title={t.matrix.removedHint}><StatusBadge tone="warning">{t.matrix.removed}</StatusBadge></span>}
                    {row.isBreakFix && <StatusBadge tone="warning">{t.matrix.breakFix}</StatusBadge>}
                    {row.isBreakFix && row.contexts && (
                      <span className="text-text-3">{fill(t.matrix.contexts, { value: row.contexts.split(',').join(', ') })}</span>
                    )}
                  </div>
                </td>
                {row.cells.map((cell, index) => (
                  <td key={cell.stageId} className="px-3.5 py-2 text-center">
                    <Cell cell={cell} stage={stages[index]} stageIndex={index} changeset={row.changeset} locale={locale} onPromote={onPromote} />
                  </td>
                ))}
              </tr>
            ))}
      </tbody>
    </table>
  );
}

function Cell({ cell, stage, stageIndex, changeset, locale, onPromote }: {
  cell: MatrixCell; stage: MatrixStage; stageIndex: number; changeset: string; locale: ReturnType<typeof useLocale>;
  onPromote: (stage: MatrixStage) => void;
}) {
  const t = useMessages();
  const can = useCan();
  const tone = cellTone(cell.status);
  const label = t.matrix.cell[cell.status];

  if (tone === null) {
    return <span className="text-text-3" aria-label={label}>{label}</span>;
  }

  const title = (cell.deployedAt
    ? fill(t.matrix.cellTitle, { status: label, env: stage?.env ?? '', when: formatDateTime(cell.deployedAt, locale) })
    : fill(t.matrix.cellNever, { status: label, env: stage?.env ?? '' }))
    + (cell.executedBy ? fill(t.matrix.cellBy, { who: cell.executedBy }) : '');
  const badge = <StatusBadge tone={tone}>{cell.status === 'FAILED' ? `✕ ${label}` : label}</StatusBadge>;

  // What can be done from the cell: open the operation that made it, ask for its rollback, or promote what is waiting here.
  const canPromote = cell.status === 'PENDING' && stageIndex > 0 && !!stage && can(`promote:${stage.env.toLowerCase()}`);
  const hasMenu = !!cell.operationId || canPromote;

  // A cell that ran links to the operation that produced it; one that is only waiting has nothing to open.
  const main = cell.operationId ? (
    <Link to={`/operations/${cell.operationId}`} title={title} className="inline-block">{badge}</Link>
  ) : (
    <span title={title}>{badge}</span>
  );
  return hasMenu ? (
    <span className="group inline-flex items-center gap-1">
      {main}
      <CellMenu label={fill(t.matrix.cellMenu.label, { changeset, env: stage?.env ?? '' })}>
        {cell.operationId && <MenuLink to={`/operations/${cell.operationId}`}>{t.matrix.cellMenu.viewOperation}</MenuLink>}
        {cell.operationId && cell.status === 'EXECUTED' && (
          <MenuLink to={`/operations/${cell.operationId}`}>{t.matrix.cellMenu.rollback}</MenuLink>
        )}
        {canPromote && (
          <button type="button" role="menuitem" onClick={() => onPromote(stage)} className={MENU_ITEM}>
            {fill(t.matrix.cellMenu.promote, { env: stage.env })}
          </button>
        )}
      </CellMenu>
    </span>
  ) : main;
}

const MENU_ITEM = 'block w-full px-3 py-1.5 text-left text-[12.5px] hover:bg-surface-alt';

function MenuLink({ to, children }: { to: string; children: React.ReactNode }) {
  return <Link to={to} role="menuitem" className={MENU_ITEM}>{children}</Link>;
}

/** The ⋯ of a cell: a small menu that closes on a click outside or Escape. */
function CellMenu({ label, children }: { label: string; children: React.ReactNode }) {
  const [open, setOpen] = useState(false);
  const root = useRef<HTMLSpanElement>(null);
  useEffect(() => {
    if (!open) {
      return undefined;
    }
    const away = (ev: MouseEvent) => {
      if (root.current && !root.current.contains(ev.target as Node)) setOpen(false);
    };
    const esc = (ev: KeyboardEvent) => {
      if (ev.key === 'Escape') setOpen(false);
    };
    document.addEventListener('mousedown', away);
    document.addEventListener('keydown', esc);
    return () => {
      document.removeEventListener('mousedown', away);
      document.removeEventListener('keydown', esc);
    };
  }, [open]);
  return (
    <span ref={root} className="relative inline-block text-left">
      <button type="button" aria-label={label} aria-haspopup="menu" aria-expanded={open} onClick={() => setOpen((v) => !v)}
        className="rounded px-1 text-text-3 opacity-0 hover:bg-surface-alt hover:text-text focus-visible:opacity-100 aria-expanded:opacity-100 group-hover:opacity-100">
        <MoreHorizontal size={14} aria-hidden />
      </button>
      {open && (
        <span role="menu" aria-label={label} onClick={() => setOpen(false)}
          className="absolute right-0 top-5 z-20 flex min-w-[170px] flex-col rounded-lg border border-border bg-surface py-1 shadow-card">
          {children}
        </span>
      )}
    </span>
  );
}
