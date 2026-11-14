import { useState } from 'react';
import { useMutation } from '@tanstack/react-query';

import { useConnectionList } from '../../api/admin';
import { checkChangesetCdc } from '../../api/changesets';
import type { CdcResult } from '../../api/changesets';
import CodeBlock from '../../components/CodeBlock';
import { Field, fieldClass, secondaryButton } from '../../components/FormDialog';
import StatusBadge from '../../components/StatusBadge';
import { fill } from '../../i18n';
import { useMessages } from '../../i18n/useMessages';
import type { SqlMode } from './sqlRules';

/**
 * What the changesets of the selection do to the tables that SQL Server Change Data Capture tracks in a database, BEFORE they are
 * deployed, and the two-step recapture to add to the same merge request: create the new capture instance, and remove the old one in
 * a later release. Lives under the generated SQL because it reads the same selection; SQL Server only.
 */
export default function CdcPanel({ projectId, mode, value }: { projectId: string; mode: SqlMode; value: string }) {
  const t = useMessages();
  const c = t.projects.sql.cdc;
  const connections = (useConnectionList().data ?? []).filter((item) => item.engine === 'SQLSERVER');
  const [chosen, setChosen] = useState('');
  const [step, setStep] = useState<'create' | 'remove'>('create');
  const [format, setFormat] = useState<'changeset' | 'sql'>('changeset');
  const connection = chosen || connections[0]?.id || '';

  const check = useMutation<CdcResult, Error>({ mutationFn: () => checkChangesetCdc(projectId, mode, value, connection) });
  const result = check.data;
  const recapture = result?.recapture;

  if (connections.length === 0) {
    return <p className="text-[12.5px] text-text-2">{c.noConnection}</p>;
  }

  return (
    <section aria-label={c.title} className="flex flex-col gap-2 rounded-card border border-border p-3">
      <h3 className="text-[15px] font-bold">{c.title}</h3>
      <p className="text-[12.5px] text-text-2">{c.intro}</p>
      <div className="flex items-end gap-2">
        <div className="flex-1">
          <Field label={c.connection}>
            <select value={connection} onChange={(event) => { setChosen(event.target.value); check.reset(); }} className={fieldClass}>
              {connections.map((item) => <option key={item.id} value={item.id}>{item.displayName || item.slug}</option>)}
            </select>
          </Field>
        </div>
        <button type="button" disabled={!connection || check.isPending} onClick={() => check.mutate()} className={secondaryButton}>
          {check.isPending ? c.checking : c.check}
        </button>
      </div>

      {check.isError && <p role="alert" className="text-[12.5px] text-danger">{c.error}</p>}

      {result && !result.cdcEnabled && <p className="text-[12.5px] text-text-2">{c.notEnabled}</p>}
      {result && result.cdcEnabled && (
        <>
          <p className="text-[12.5px] text-text-2">
            {result.tables.length === 0
              ? fill(c.none, { tracked: result.trackedTables })
              : fill(c.summary, { tables: result.tables.length, breaking: result.breaking, tracked: result.trackedTables })}
          </p>
          {result.unknown > 0 && <p className="text-[12.5px] text-warning">{fill(c.unknown, { n: result.unknown })}</p>}
          <ul className="flex flex-col gap-1.5 text-[12.5px]">
            {result.changesets.filter((item) => item.impacts.length > 0).map((item) => (
              <li key={item.id} className="flex flex-wrap items-center gap-2">
                <span className="font-mono font-semibold">{item.changesetId}</span>
                {item.impacts.map((impact) => (
                  <StatusBadge key={`${impact.table}-${impact.kind}-${impact.detail ?? ''}`} tone={impact.breaking ? 'danger' : 'warning'}>
                    {`${impact.table} · ${c.kinds[impact.kind as keyof typeof c.kinds] ?? impact.kind}${impact.detail ? ` (${impact.detail})` : ''}`}
                  </StatusBadge>
                ))}
              </li>
            ))}
          </ul>
          {result.notes.map((note) => <p key={note} className="text-[12.5px] text-warning">{note}</p>)}
        </>
      )}

      {recapture && (
        <div className="flex flex-col gap-2">
          <h4 className="text-[13px] font-bold">{c.recapture}</h4>
          <div className="flex flex-wrap gap-2">
            <button type="button" aria-pressed={step === 'create'} onClick={() => setStep('create')}
              className={`${secondaryButton} ${step === 'create' ? 'bg-surface-alt' : ''}`}>{c.step1}</button>
            <button type="button" aria-pressed={step === 'remove'} onClick={() => setStep('remove')}
              className={`${secondaryButton} ${step === 'remove' ? 'bg-surface-alt' : ''}`}>{c.step2}</button>
            <span className="ml-auto flex gap-2">
              <button type="button" aria-pressed={format === 'changeset'} onClick={() => setFormat('changeset')}
                className={`${secondaryButton} ${format === 'changeset' ? 'bg-surface-alt' : ''}`}>{c.asChangeset}</button>
              <button type="button" aria-pressed={format === 'sql'} onClick={() => setFormat('sql')}
                className={`${secondaryButton} ${format === 'sql' ? 'bg-surface-alt' : ''}`}>{c.asSql}</button>
            </span>
          </div>
          <p className="text-[12.5px] text-text-2">{step === 'create' ? c.step1Hint : c.step2Hint}</p>
          <CodeBlock copyable>
            {step === 'create'
              ? (format === 'changeset' ? recapture.createChangesets : recapture.createSql)
              : (format === 'changeset' ? recapture.removeChangesets : recapture.removeSql)}
          </CodeBlock>
          <ul className="list-disc pl-5 text-[12.5px] text-text-2">
            {recapture.notes.map((note) => <li key={note}>{note}</li>)}
          </ul>
        </div>
      )}
    </section>
  );
}
