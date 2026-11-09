import { useEffect, useMemo, useRef, useState } from 'react';
import { ChevronDown, ChevronRight, GitBranch, GitCommitHorizontal } from 'lucide-react';

import { useChangesetSource } from '../../api/changesets';
import { TOKEN_CLASS } from '../developer/CodeEditor';
import { highlight, languageOf } from '../developer/rules';
import { fill } from '../../i18n';
import { useMessages } from '../../i18n/useMessages';

/**
 * What a changeset says, read from the repository: its own lines (with the rollback it carries) by default, the whole file on demand.
 * When the file cannot be read it says why and what to fix: showing an error is no help to somebody who has to decide.
 */
export default function ChangesetSource({ changesetId }: { changesetId: string }) {
  const t = useMessages();
  const s = t.projects.source;
  const source = useChangesetSource(changesetId);
  const [whole, setWhole] = useState(false);

  if (source.isLoading) {
    return <div role="status" className="rounded-card border border-border bg-surface p-4 text-text-2 shadow-card">{s.loading}</div>;
  }
  const data = source.data;
  if (!data || !data.found || data.content == null) {
    return (
      <div className="rounded-card border border-border bg-surface p-4 shadow-card">
        <p className="font-semibold">{data ? s.reasons[data.reason ?? 'NOT_FOUND'] : t.projects.changeset.loadError}</p>
        {data?.detail && <p className="mt-1 text-text-2">{data.detail}</p>}
        {data?.tried && data.tried.length > 0 && <p className="mt-1 text-[12px] text-text-3">{fill(s.tried, { refs: data.tried.join(', ') })}</p>}
        {data?.path && <p className="mt-1 font-mono text-[12px] text-text-3">{data.path}</p>}
      </div>
    );
  }

  const range = data.startLine && data.endLine ? { from: data.startLine, to: data.endLine } : null;
  const showWhole = whole || !range;
  return (
    <div className="overflow-hidden rounded-card border border-border bg-surface shadow-card">
      <div className="flex flex-wrap items-center gap-2 border-b border-border px-3.5 py-2 text-[12px] text-text-2">
        <code className="font-mono text-text">{data.path}</code>
        <span className="inline-flex items-center gap-1">
          {data.refKind === 'COMMIT' ? <GitCommitHorizontal size={12} aria-hidden /> : <GitBranch size={12} aria-hidden />}
          {fill(data.refKind === 'COMMIT' ? s.commit : s.branch, { ref: data.refKind === 'COMMIT' ? (data.ref ?? '').slice(0, 8) : (data.ref ?? '') })}
        </span>
        {range && <span>{fill(s.lines, { from: range.from, to: range.to })}</span>}
        {range && (
          <span className="ml-auto flex gap-1" role="group">
            <button type="button" aria-pressed={!whole} onClick={() => setWhole(false)}
              className={`rounded-control px-2 py-0.5 font-semibold ${!whole ? 'bg-primary-50 text-primary' : 'hover:bg-surface-alt'}`}>{s.snippet}</button>
            <button type="button" aria-pressed={whole} onClick={() => setWhole(true)}
              className={`rounded-control px-2 py-0.5 font-semibold ${whole ? 'bg-primary-50 text-primary' : 'hover:bg-surface-alt'}`}>{s.file}</button>
          </span>
        )}
      </div>
      {!range && <p className="border-b border-border px-3.5 py-1.5 text-[12px] text-warning">{s.noBlock}</p>}
      <Lines text={data.content} path={data.path} range={range} whole={showWhole} />
    </div>
  );
}

function Lines({ text, path, range, whole }: { text: string; path: string; range: { from: number; to: number } | null; whole: boolean }) {
  const lines = useMemo(() => highlight(text, languageOf(path)), [text, path]);
  const box = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (whole && range) {
      box.current?.querySelector(`[data-line="${range.from}"]`)?.scrollIntoView({ block: 'center' });
    }
  }, [whole, range]);
  const from = whole || !range ? 1 : range.from;
  const to = whole || !range ? lines.length : range.to;

  return (
    <div ref={box} role="region" aria-label={path} tabIndex={0} className="max-h-[480px] overflow-auto py-2 font-mono text-[12.5px] leading-[21px]">
      {lines.slice(from - 1, to).map((tokens, index) => {
        const number = from + index;
        const inside = range != null && number >= range.from && number <= range.to;
        return (
          <div key={number} data-line={number} className={`grid grid-cols-[44px_1fr] ${whole && inside ? 'bg-primary-50' : ''}`}>
            <span aria-hidden className="pr-3 text-right text-text-3 select-none">{number}</span>
            <span className="pr-4 whitespace-pre">
              {tokens.map((token, i) => <span key={i} className={TOKEN_CLASS[token.kind]}>{token.text}</span>)}
              {tokens.length === 0 && ' '}
            </span>
          </div>
        );
      })}
    </div>
  );
}

/** A line with a changeset's name and one click to read what it does, in place: for the screens where somebody approves. */
export function ChangesetPeek({ id, label, detail }: { id: string; label: string; detail?: string }) {
  const t = useMessages();
  const [open, setOpen] = useState(false);
  return (
    <div>
      <button type="button" aria-expanded={open} onClick={() => setOpen((value) => !value)} className="inline-flex items-center gap-1.5 text-left">
        {open ? <ChevronDown size={13} aria-hidden /> : <ChevronRight size={13} aria-hidden />}
        <span className="font-mono font-semibold">{label}</span>
        {detail && <span className="text-text-2">{detail}</span>}
        <span className="text-[11.5px] font-semibold text-primary">{open ? t.projects.source.hide : t.projects.source.peek}</span>
      </button>
      {open && <div className="mt-1.5"><ChangesetSource changesetId={id} /></div>}
    </div>
  );
}
