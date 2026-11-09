import { useState } from 'react';

import type { TestResult } from '../../api/governance';
import StatusBadge from '../../components/StatusBadge';
import { fill } from '../../i18n';
import { useMessages } from '../../i18n/useMessages';
import { gateLabel, gateTone, severityLabel, severityTone } from './rules';

const FORMATS = ['XML', 'YAML', 'JSON', 'SQL'] as const;
const DIALECTS = ['mssql', 'postgresql', 'oracle', 'mysql'] as const;

export type TestRun = (request: { changelogContent: string; format: (typeof FORMATS)[number]; dialect: string }) => Promise<TestResult>;

/** The dry-run of a check (docs/telas/07): paste a changelog, read the findings. Nothing touches a database. */
export default function TestDialog({ onRun, onClose }: { onRun: TestRun; onClose: () => void }) {
  const t = useMessages();
  const [content, setContent] = useState('');
  const [format, setFormat] = useState<(typeof FORMATS)[number]>('XML');
  const [dialect, setDialect] = useState('mssql');
  const [running, setRunning] = useState(false);
  const [result, setResult] = useState<TestResult | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function run() {
    setRunning(true);
    setError(null);
    try {
      setResult(await onRun({ changelogContent: content, format, dialect }));
    } catch (e) {
      setResult(null);
      setError(e instanceof Error && e.message ? e.message : t.governance.testDialog.error);
    } finally {
      setRunning(false);
    }
  }

  const select = 'rounded-control border border-border bg-surface px-2 py-1.5 text-[12px]';

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-label={t.governance.testDialog.title}
      className="fixed inset-0 z-50 flex items-center justify-center bg-text/30 p-6"
    >
      <div className="flex max-h-full w-[760px] flex-col gap-3 overflow-auto rounded-card border border-border bg-surface p-5 shadow-overlay">
        <div>
          <h2 className="text-[18px] font-bold">{t.governance.testDialog.title}</h2>
          <p className="text-[12.5px] text-text-2">{t.governance.testDialog.subtitle}</p>
          <p className="text-[12px] text-text-3">{t.governance.testDialog.usesUnsaved} {t.governance.testDialog.sameEngine}</p>
        </div>
        <div className="flex gap-3">
          <label className="flex items-center gap-2 text-[12px] font-semibold">
            {t.governance.testDialog.format}
            <select className={select} value={format} onChange={(e) => setFormat(e.target.value as typeof format)}>
              {FORMATS.map((f) => <option key={f}>{f}</option>)}
            </select>
          </label>
          <label className="flex items-center gap-2 text-[12px] font-semibold">
            {t.governance.testDialog.dialect}
            <select className={select} value={dialect} onChange={(e) => setDialect(e.target.value)}>
              {DIALECTS.map((d) => <option key={d}>{d}</option>)}
            </select>
          </label>
        </div>
        <textarea
          aria-label={t.governance.testDialog.content}
          placeholder={t.governance.testDialog.placeholder}
          value={content}
          onChange={(e) => setContent(e.target.value)}
          rows={12}
          className="w-full rounded-control border border-border bg-surface-alt p-3 font-mono text-[12px]"
        />
        {error && <div role="alert" className="rounded-control bg-danger-bg px-3 py-2 text-[12.5px] text-danger">{error}</div>}
        {result && (
          <div aria-live="polite" className="rounded-control border border-border p-3">
            <div className="mb-2 flex items-center gap-2 text-[12.5px]">
              <StatusBadge tone={gateTone(result.gate)}>{gateLabel(result.gate, t)}</StatusBadge>
              {fill(t.governance.testDialog.summary, { changesets: result.changesets, findings: result.findings.length })}
            </div>
            {result.findings.length === 0 ? (
              <p className="text-[12.5px] text-text-2">{t.governance.testDialog.none}</p>
            ) : (
              <ul className="flex flex-col gap-1.5">
                {result.findings.map((f, i) => (
                  <li key={i} className="flex items-start gap-2 text-[12.5px]">
                    <StatusBadge tone={severityTone(f.severity)}>{severityLabel(f.severity, t)}</StatusBadge>
                    <span>
                      <code className="font-mono text-[11.5px]">{f.changeset ?? ''}</code> {f.message}
                    </span>
                  </li>
                ))}
              </ul>
            )}
          </div>
        )}
        <div className="flex justify-end gap-2">
          <button type="button" onClick={onClose} className="rounded-control border border-border px-3 py-1.5 text-[12px] font-semibold hover:bg-surface-alt">
            {t.governance.testDialog.close}
          </button>
          <button
            type="button"
            onClick={run}
            disabled={running || !content.trim()}
            className="rounded-control bg-primary px-3 py-1.5 text-[12px] font-semibold text-white disabled:opacity-50"
          >
            {running ? t.governance.testDialog.running : t.governance.testDialog.run}
          </button>
        </div>
      </div>
    </div>
  );
}
