import { useState } from 'react';
import { useMutation } from '@tanstack/react-query';

import { generateChangesetSql, useProjectEnvironments } from '../../api/changesets';
import type { ChangesetSqlResult } from '../../api/changesets';
import CodeBlock from '../../components/CodeBlock';
import { Field, fieldClass, primaryButton, secondaryButton } from '../../components/FormDialog';
import StatusBadge from '../../components/StatusBadge';
import CdcPanel from './CdcPanel';
import { fill } from '../../i18n';
import { useMessages } from '../../i18n/useMessages';
import { canGenerate, SQL_DIALECTS, SQL_MODES, sqlFileName } from './sqlRules';
import type { SqlDialect, SqlMode } from './sqlRules';

/**
 * Generates the SQL of a set of changesets from the repository, without running anything: what is not in the next environment yet, a
 * release, a Liquibase tag, or one changeset. The script is for whoever applies the change by hand (a DBA, a window, a database no
 * collector reaches), so it can be copied or downloaded. A changeset whose SQL could not be generated says why instead of vanishing.
 */
export default function GenerateSql({
  projectId, project, onClose, initial,
}: {
  projectId: string;
  project: string;
  onClose: () => void;
  /** Opens with a choice made, for example one changeset from its own page. */
  initial?: { mode: SqlMode; value: string };
}) {
  const t = useMessages();
  const s = t.projects.sql;
  const environments = useProjectEnvironments(projectId).data?.map((item) => item.environment) ?? [];
  const [mode, setMode] = useState<SqlMode>(initial?.mode ?? 'notIn');
  const [value, setValue] = useState(initial?.value ?? '');
  const [dialect, setDialect] = useState<SqlDialect>('');
  const fixed = initial?.mode === 'ids';
  // For "not yet in an environment" the value is the environment; it starts on the second one, the usual next step after the first.
  const environment = mode === 'notIn' ? (value || environments[1] || environments[0] || '') : value;
  const ready = canGenerate(mode, environment);

  const generate = useMutation<ChangesetSqlResult, Error>({
    mutationFn: () => generateChangesetSql(projectId, mode, environment, dialect),
  });
  const result = generate.data;

  function download() {
    if (!result) {
      return;
    }
    const url = URL.createObjectURL(new Blob([result.sql], { type: 'text/plain;charset=utf-8' }));
    const link = document.createElement('a');
    link.href = url;
    link.download = sqlFileName(project, mode, environment);
    document.body.append(link);
    link.click();
    link.remove();
    URL.revokeObjectURL(url);
  }

  function choose(next: SqlMode) {
    setMode(next);
    setValue('');
    generate.reset();
  }

  const warned = result?.changesets.filter((item) => item.warning) ?? [];

  return (
    <div role="dialog" aria-modal="true" aria-label={s.title} className="fixed inset-0 z-50 flex items-center justify-center bg-text/30 p-6">
      <div className="flex max-h-full w-[760px] max-w-full flex-col gap-3 overflow-auto rounded-card border border-border bg-surface p-5 shadow-overlay">
        <h2 className="text-[18px] font-bold">{s.title}</h2>
        <p className="text-[12.5px] text-text-2">{s.intro}</p>

        {!fixed && (
          <>
            <Field label={s.mode} hint={s.hints[mode]}>
              <select value={mode} onChange={(event) => choose(event.target.value as SqlMode)} className={fieldClass}>
                {SQL_MODES.filter((item) => item !== 'ids').map((item) => <option key={item} value={item}>{s.modes[item]}</option>)}
              </select>
            </Field>
            {mode === 'notIn' ? (
              <Field label={s.environment}>
                <select value={environment} onChange={(event) => { setValue(event.target.value); generate.reset(); }} className={fieldClass}>
                  {environments.map((env) => <option key={env} value={env}>{env}</option>)}
                </select>
              </Field>
            ) : (
              <Field label={mode === 'release' ? s.release : s.tag}>
                <input value={value} onChange={(event) => { setValue(event.target.value); generate.reset(); }}
                  placeholder={mode === 'release' ? 'release-2.1.0' : 'v1.0.0'} className={`${fieldClass} font-mono`} />
              </Field>
            )}
          </>
        )}
        {fixed && <p className="text-[12.5px] text-text-2">{s.modes.ids}</p>}

        <Field label={s.dialect}>
          <select value={dialect} onChange={(event) => { setDialect(event.target.value as SqlDialect); generate.reset(); }} className={fieldClass}>
            {SQL_DIALECTS.map((item) => <option key={item || 'auto'} value={item}>{s.dialects[item === '' ? 'auto' : item]}</option>)}
          </select>
        </Field>

        {generate.isError && <p role="alert" className="text-[12.5px] text-danger">{s.error}</p>}

        {result && (
          <section aria-label={s.title} className="flex flex-col gap-2">
            <p className="text-[12.5px] text-text-2">
              {result.count === 0
                ? s.nothing
                : fill(s.summary, { generated: result.generated, count: result.count, dialect: (s.dialects as Record<string, string>)[result.dialect] ?? result.dialect })}
            </p>
            {warned.length > 0 && (
              <div className="rounded-control border border-border bg-surface-alt p-2.5">
                <p className="mb-1 text-[12px] font-semibold">{s.warningsTitle}</p>
                <ul className="flex flex-col gap-1 text-[12.5px]">
                  {warned.map((item) => (
                    <li key={item.id} className="flex flex-wrap items-center gap-2">
                      <span className="font-mono font-semibold">{item.changesetId}</span>
                      <StatusBadge tone="warning">{s.warnings[item.warning as keyof typeof s.warnings] ?? item.warning}</StatusBadge>
                    </li>
                  ))}
                </ul>
              </div>
            )}
            {result.generated > 0 && <CodeBlock copyable>{result.sql}</CodeBlock>}
          </section>
        )}

        {result && result.dialect === 'mssql' && <CdcPanel projectId={projectId} mode={mode} value={environment} />}

        <div className="flex justify-end gap-2">
          <button type="button" onClick={onClose} className={secondaryButton}>{s.close}</button>
          {result && result.generated > 0 && <button type="button" onClick={download} className={secondaryButton}>{s.download}</button>}
          <button type="button" disabled={!ready || generate.isPending} onClick={() => generate.mutate()} className={primaryButton}>
            {generate.isPending ? s.generating : s.generate}
          </button>
        </div>
      </div>
    </div>
  );
}
