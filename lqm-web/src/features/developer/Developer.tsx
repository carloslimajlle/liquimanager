import { useMemo, useState } from 'react';
import { useParams } from 'react-router-dom';
import { GitBranch } from 'lucide-react';

import { useChangelogDetail, useOperationLog, useRepoFile } from '../../api/developer';
import KpiCard from '../../components/KpiCard';
import PageHeader from '../../components/PageHeader';
import { EmptyState, ErrorCard } from '../../components/States';
import { fill } from '../../i18n';
import { useLocale, useMessages } from '../../i18n/useMessages';
import { formatDurationParts, formatNumber } from '../../lib/format';
import Topbar from '../../shell/Topbar';
import CodeEditor from './CodeEditor';
import CommandModal from './CommandModal';
import { commandFor, languageOf, locateChangesets, markersFor, problemsFor } from './rules';
import type { CommandKind } from './rules';

const FLOW_FILE = 'lqm.flow.yaml';
type PanelTab = 'problems' | 'output' | 'terminal';

function basename(path: string): string {
  return path.slice(path.lastIndexOf('/') + 1);
}

/** Screen 03: a changelog as it is in the repository, with what the platform knows about each changeset. */
export default function Developer() {
  const t = useMessages();
  const d = t.developer;
  const locale = useLocale();
  const { projectId, changelogId } = useParams();
  const detail = useChangelogDetail(projectId, changelogId);
  const data = detail.data;

  const [selected, setSelected] = useState<string | null>(null);
  const [tab, setTab] = useState<PanelTab>('terminal');
  const [focusLine, setFocusLine] = useState<number | null>(null);
  const [command, setCommand] = useState<CommandKind | null>(null);

  const path = selected ?? data?.path;
  const file = useRepoFile(projectId, changelogId, path, !!data);
  const log = useOperationLog(data?.kpis.lastFlow?.operationId);

  const language = languageOf(path ?? '');
  const located = useMemo(() => (file.data ? locateChangesets(file.data, language) : []), [file.data, language]);
  const markers = useMemo(() => (data && path && path !== FLOW_FILE ? markersFor(located, data.changesets, path) : []), [data, path, located]);
  const problems = useMemo(() => (data && path ? problemsFor(data.findings, path, located) : []), [data, path, located]);

  const trail = [d.breadcrumbRoot, d.breadcrumbList, data?.name ?? ''];
  const header = <PageHeader title={d.title} subtitle={d.subtitle} />;

  if (detail.isError) {
    return (
      <>
        <Topbar trail={trail.slice(0, 2)} />
        {header}
        <div className="rounded-card border border-border bg-surface shadow-card"><ErrorCard message={d.error} onRetry={() => detail.refetch()} /></div>
      </>
    );
  }

  const k = data?.kpis;
  const missing = k ? k.changesets - k.withRollback : 0;
  const flow = k?.lastFlow;
  const flowTime = flow?.durationMs != null ? formatDurationParts(flow.durationMs, locale) : null;
  const tabs = data ? [...data.files, FLOW_FILE] : [];

  return (
    <>
      <Topbar trail={trail} />
      {header}

      <div className="mb-3.5 grid grid-cols-2 gap-3 xl:grid-cols-4">
        <KpiCard
          hero
          label={d.kpi.changesets}
          value={k ? formatNumber(k.changesets, locale) : t.common.none}
          sub={k ? (k.release ? fill(d.kpi.changesetsSub, { release: k.release.replace(/^release-/, '') }) : d.kpi.changesetsNoRelease) : undefined}
        />
        <KpiCard
          label={d.kpi.rollback}
          tone={k && missing > 0 ? 'warning' : 'success'}
          value={k ? formatNumber(k.withRollback, locale) : t.common.none}
          sub={k ? (missing > 0 ? fill(d.kpi.rollbackMissing, { n: formatNumber(missing, locale) }) : d.kpi.rollbackAll) : undefined}
        />
        <KpiCard label={d.kpi.commands} value="4" sub={d.kpi.commandsSub} />
        <KpiCard
          label={d.kpi.flow}
          tone={flow && flow.status !== 'SUCCESS' ? 'danger' : 'default'}
          value={flowTime?.value ?? t.common.none}
          unit={flowTime?.unit}
          sub={flow
            ? fill(flow.status === 'SUCCESS' ? d.kpi.flowOk : d.kpi.flowFailed, { stages: String(flow.stages) })
            : (k ? d.kpi.flowNone : undefined)}
        />
      </div>

      <div className="mb-6 overflow-hidden rounded-card border border-border bg-surface shadow-card">
        <div className="flex items-center border-b border-border">
          <div role="tablist" aria-label={d.editor.files} className="flex min-w-0 flex-1 overflow-x-auto">
            {tabs.map((name) => (
              <button
                key={name}
                type="button"
                role="tab"
                aria-selected={name === path}
                title={name}
                onClick={() => { setSelected(name); setFocusLine(null); }}
                className={`shrink-0 border-r border-border px-3.5 py-2.5 font-mono text-[12px] ${name === path
                  ? 'bg-surface font-semibold text-text'
                  : 'bg-surface-alt text-text-2 hover:text-text'}`}
              >
                {basename(name)}
              </button>
            ))}
          </div>
          {data && k && (
            <div className="ml-auto flex shrink-0 items-center gap-1.5 px-3.5 text-[12px] text-text-2">
              <GitBranch size={12} aria-hidden />
              {fill(d.editor.summary, {
                branch: data.branch ?? '—',
                changesets: formatNumber(k.changesets, locale),
                rollback: formatNumber(k.withRollback, locale),
              })}
            </div>
          )}
        </div>

        {!data || file.isLoading ? (
          <div aria-hidden className="m-4 h-40 animate-pulse rounded bg-border" />
        ) : file.isError ? (
          <EmptyState
            title={path === FLOW_FILE ? d.editor.flowMissing : d.editor.error}
            hint={path === FLOW_FILE ? undefined : data.sourceConfigured ? (file.error instanceof Error ? file.error.message : undefined) : d.editor.notConfigured}
          />
        ) : (
          <CodeEditor
            text={file.data ?? ''}
            language={language}
            markers={markers}
            label={path ?? ''}
            focusLine={focusLine}
            onCommand={setCommand}
          />
        )}

        <div role="region" aria-label={d.panel.label} className="border-t border-border">
          <div role="tablist" className="flex gap-4 px-3.5 pt-2.5">
            {(['problems', 'output', 'terminal'] as const).map((name) => (
              <button
                key={name}
                type="button"
                role="tab"
                aria-selected={tab === name}
                onClick={() => setTab(name)}
                className={`border-b-2 pb-1.5 text-[11.5px] font-semibold tracking-[0.08em] uppercase ${tab === name
                  ? 'border-primary text-text'
                  : 'border-transparent text-text-3 hover:text-text-2'}`}
              >
                {d.panel[name]}{name === 'problems' && problems.length > 0 ? ` (${problems.length})` : ''}
              </button>
            ))}
          </div>
          <div className="max-h-[220px] min-h-[110px] overflow-auto px-3.5 py-3 font-mono text-[12px] leading-[19px]">
            {tab === 'problems' && (problems.length === 0 ? (
              <p className="text-text-2">{d.panel.noProblems}</p>
            ) : (
              <ul className="flex flex-col gap-1">
                {problems.map((problem, index) => (
                  <li key={`${problem.check}-${index}`}>
                    <button
                      type="button"
                      disabled={problem.line === null}
                      onClick={() => problem.line !== null && setFocusLine(problem.line)}
                      className="text-left enabled:hover:underline"
                    >
                      <span className="font-semibold">{problem.check}</span>{' '}
                      <span className="text-text-2">{problem.message}</span>{' '}
                      {problem.line !== null && <span className="text-text-3">({fill(d.panel.line, { line: String(problem.line) })})</span>}
                    </button>
                  </li>
                ))}
              </ul>
            ))}
            {tab === 'output' && (markers.length === 0 ? (
              <p className="text-text-2">{d.panel.noChangesets}</p>
            ) : (
              <ul className="flex flex-col gap-0.5">
                {markers.map((marker) => (
                  <li key={marker.line}>
                    <button type="button" onClick={() => setFocusLine(marker.line)} className="text-left hover:underline">
                      <span className="font-semibold">{marker.changeset}</span>{' '}
                      <span className="text-text-2">{d.marker[marker.status]}</span>{' '}
                      <span className="text-text-3">({fill(d.panel.line, { line: String(marker.line) })})</span>
                    </button>
                  </li>
                ))}
              </ul>
            ))}
            {tab === 'terminal' && (!flow ? (
              <p className="text-text-2">{d.panel.noFlow}</p>
            ) : log.isError ? (
              <p className="text-danger">{d.panel.logError}</p>
            ) : (
              <pre className="whitespace-pre-wrap">{log.data ?? ''}</pre>
            ))}
          </div>
        </div>
      </div>

      {command && data && (
        <CommandModal
          name={d.menu[command === 'rollback' ? 'rollbacks' : command === 'checks' ? 'checks' : command]}
          command={commandFor(command, data.project.slug, data.path)}
          onClose={() => setCommand(null)}
        />
      )}
    </>
  );
}
