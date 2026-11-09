import { downloadFile } from '../../api/download';
import { useState } from 'react';

import { useAudit, useAuditVerification, useRotationAction, useSecrets } from '../../api/access';
import type { AuditEvent, SecretRow } from '../../api/access';
import EnvBadge from '../../components/EnvBadge';
import StatusBadge from '../../components/StatusBadge';
import type { Tone } from '../../components/StatusBadge';
import { fill } from '../../i18n';
import { useLocale, useMessages } from '../../i18n/useMessages';
import { formatShortDateTime } from '../../lib/format';

const LEVEL_TONE: Record<SecretRow['level'], Tone> = { ok: 'success', warning: 'warning', danger: 'danger' };

/** One audit event as a sentence a person can read; an action nobody wrote a sentence for shows its code. */
export function describeEvent(event: Pick<AuditEvent, 'actor' | 'action'>, actions: Record<string, string>, fallback: string): string {
  const template = actions[event.action];
  return fill(template ?? fallback, { actor: event.actor, action: event.action });
}

/** "Gestão de segredos": connection, provider and how long ago the secret was rotated. Never a value. */
export function SecretsPanel() {
  const t = useMessages();
  const s = t.access.side.secrets;
  const secrets = useSecrets();
  const rotate = useRotationAction();
  const [error, setError] = useState<string | null>(null);
  const data = secrets.data;

  async function record(connection: string) {
    setError(null);
    try {
      await rotate(connection);
    } catch (err) {
      setError(err instanceof Error && err.message ? err.message : s.error);
    }
  }

  const age = (row: SecretRow) =>
    row.ageDays == null ? s.never : row.ageDays === 0 ? s.today : fill(s.age, { n: row.ageDays });

  return (
    <section className="rounded-card border border-border bg-surface p-4 shadow-card">
      <div className="flex items-center gap-2">
        <h2 className="text-[14px] font-semibold">{s.title}</h2>
        {data && (
          <span className="ml-auto">
            <StatusBadge tone={data.secretsInChangelogs === 0 ? 'success' : 'danger'}>
              {fill(s.inChangelogs, { n: data.secretsInChangelogs })}
            </StatusBadge>
          </span>
        )}
      </div>
      {secrets.isError && <p role="alert" className="mt-2 text-[12px] text-danger">{s.error}</p>}
      {error && <p role="alert" className="mt-2 text-[12px] text-danger">{error}</p>}
      {data && data.connections.length === 0 && <p className="mt-2 text-[12.5px] text-text-2">{s.none}</p>}
      <ul className="mt-3 flex flex-col gap-2.5">
        {(data?.connections ?? []).map((row) => (
          <li key={row.id} className="text-[12.5px]">
            <div className="flex items-center gap-2">
              <code className="font-mono text-[12px]">{row.connection}</code>
              <EnvBadge code={row.environment} />
              <span className="ml-auto text-text-3">{row.provider}</span>
            </div>
            <div className="mt-0.5 flex items-center gap-2">
              <StatusBadge tone={LEVEL_TONE[row.level]}>{age(row)}</StatusBadge>
              <button type="button" onClick={() => record(row.connection)}
                className="text-[11.5px] font-semibold text-primary hover:underline">{s.rotated}</button>
            </div>
          </li>
        ))}
      </ul>
    </section>
  );
}

/** "Evidências para auditoria": the latest events in words, whether the chain still verifies, and the export. */
export function AuditPanel() {
  const t = useMessages();
  const a = t.access.side.audit;
  const locale = useLocale();
  const audit = useAudit(6);
  const verification = useAuditVerification();

  return (
    <section className="rounded-card border border-border bg-surface p-4 shadow-card">
      <div className="flex items-center gap-2">
        <h2 className="text-[14px] font-semibold">{a.title}</h2>
        <button type="button" onClick={() => void downloadFile('/audit/export?format=csv', 'audit.csv')}
          className="ml-auto text-[12px] font-semibold text-primary hover:underline">{a.export}</button>
      </div>
      {verification.data && (
        <p className="mt-2">
          <StatusBadge tone={verification.data.intact ? 'success' : 'danger'}>
            {verification.data.intact ? a.verified : fill(a.broken, { seq: verification.data.brokenAt ?? 0 })}
          </StatusBadge>
        </p>
      )}
      {audit.isError && <p role="alert" className="mt-2 text-[12px] text-danger">{a.error}</p>}
      {audit.data && audit.data.content.length === 0 && <p className="mt-2 text-[12.5px] text-text-2">{a.none}</p>}
      <ul className="mt-3 flex flex-col gap-2">
        {(audit.data?.content ?? []).map((event) => (
          <li key={event.seq} className="text-[12.5px]">
            {describeEvent(event, a.actions, a.fallback)}
            <div className="text-[11.5px] text-text-3">{formatShortDateTime(event.at, locale)}</div>
          </li>
        ))}
      </ul>
    </section>
  );
}
