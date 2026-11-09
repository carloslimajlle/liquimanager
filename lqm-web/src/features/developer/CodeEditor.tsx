import { useEffect, useMemo, useRef, useState } from 'react';
import type { MouseEvent } from 'react';
import { Check, ChevronRight, Circle, X } from 'lucide-react';

import { useMessages } from '../../i18n/useMessages';
import { highlight } from './rules';
import type { CommandKind, Language, Marker, TokenKind } from './rules';

export const TOKEN_CLASS: Record<TokenKind, string> = {
  plain: '',
  tag: 'text-primary',
  attr: 'text-text-2',
  string: 'text-success',
  comment: 'text-text-3 italic',
  keyword: 'text-primary font-semibold',
  key: 'text-primary',
  number: 'text-info',
};

/**
 * The read-only editor of screen 03. It is not Monaco: a viewer with line numbers, light syntax colours, the state of
 * each changeset in the gutter and the LiquiManager menu is all the screen asks for, and it ships without a
 * multi-megabyte dependency. A marker is a glyph with a text alternative, never only a colour (RNF-13).
 */
export default function CodeEditor({ text, language, markers, onCommand, label, focusLine = null }: {
  text: string;
  language: Language;
  markers: Marker[];
  onCommand: (kind: CommandKind) => void;
  label: string;
  /** A line to bring into view and mark, e.g. the one a finding points at. */
  focusLine?: number | null;
}) {
  const t = useMessages();
  const lines = useMemo(() => highlight(text, language), [text, language]);
  const byLine = useMemo(() => new Map(markers.map((m) => [m.line, m])), [markers]);
  const [menu, setMenu] = useState<{ x: number; y: number } | null>(null);
  const box = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (focusLine !== null) {
      box.current?.querySelector(`[data-line="${focusLine}"]`)?.scrollIntoView({ block: 'center' });
    }
  }, [focusLine]);

  function open(event: MouseEvent) {
    event.preventDefault();
    setMenu({ x: event.clientX, y: event.clientY });
  }

  return (
    <div className="relative">
      <div
        ref={box}
        role="region"
        aria-label={label}
        tabIndex={0}
        onContextMenu={open}
        className="max-h-[520px] overflow-auto bg-surface py-2 font-mono text-[12.5px] leading-[21px]"
      >
        {lines.map((tokens, index) => {
          const marker = byLine.get(index + 1);
          return (
            <div key={index} data-line={index + 1}
              className={`grid grid-cols-[22px_44px_1fr] ${focusLine === index + 1 ? 'bg-primary-50' : 'hover:bg-surface-alt'}`}>
              <span className="text-center">{marker && <Glyph marker={marker} />}</span>
              <span aria-hidden className="pr-3 text-right text-text-3 select-none">{index + 1}</span>
              <span className="pr-4 whitespace-pre">
                {tokens.map((token, i) => (
                  <span key={i} className={TOKEN_CLASS[token.kind]}>{token.text}</span>
                ))}
                {tokens.length === 0 && ' '}
              </span>
            </div>
          );
        })}
      </div>
      <button
        type="button"
        onClick={(event) => {
          const box = event.currentTarget.getBoundingClientRect();
          setMenu({ x: box.right - 4, y: box.bottom + 4 });
        }}
        className="absolute top-2 right-4 rounded-control border border-border bg-surface px-2 py-0.5 text-[11.5px] font-semibold text-primary shadow-card hover:bg-surface-alt"
      >
        {t.developer.editor.commands}
      </button>
      {menu && <EditorMenu at={menu} onClose={() => setMenu(null)} onCommand={(kind) => { setMenu(null); onCommand(kind); }} />}
    </div>
  );
}

function Glyph({ marker }: { marker: Marker }) {
  const t = useMessages();
  const label = t.developer.marker[marker.status];
  const Icon = marker.status === 'EXECUTED' ? Check : marker.status === 'FAILED' ? X : Circle;
  const tone = marker.status === 'EXECUTED' ? 'text-success' : marker.status === 'FAILED' ? 'text-danger' : 'text-info';
  return (
    <span title={`${marker.changeset}: ${label}`} className={`inline-flex h-[21px] items-center ${tone}`}>
      <Icon size={marker.status === 'PENDING' ? 9 : 13} strokeWidth={3} fill={marker.status === 'PENDING' ? 'currentColor' : 'none'} aria-hidden />
      <span className="sr-only">{label}</span>
    </span>
  );
}

const ACTIONS: Array<{ kind: CommandKind; key: 'update' | 'rollbacks' | 'checks' | 'flow' }> = [
  { kind: 'update', key: 'update' },
  { kind: 'rollback', key: 'rollbacks' },
  { kind: 'checks', key: 'checks' },
  { kind: 'flow', key: 'flow' },
];

/**
 * The context menu of the print: the editor's own entries, then "LiquiManager" with its submenu. The four that run
 * something open the CLI line to copy; the rest belong to the IDE extension and are shown disabled, with the reason.
 */
function EditorMenu({ at, onClose, onCommand }: { at: { x: number; y: number }; onClose: () => void; onCommand: (kind: CommandKind) => void }) {
  const t = useMessages();
  const m = t.developer.menu;
  const [sub, setSub] = useState(true);
  const root = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const outside = (event: Event) => {
      if (!root.current?.contains(event.target as Node)) onClose();
    };
    const escape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose();
    };
    document.addEventListener('mousedown', outside);
    document.addEventListener('keydown', escape);
    return () => {
      document.removeEventListener('mousedown', outside);
      document.removeEventListener('keydown', escape);
    };
  }, [onClose]);

  const item = 'flex w-full items-center justify-between gap-6 px-3 py-1.5 text-left text-[12.5px]';
  const disabled = `${item} cursor-not-allowed text-text-3`;
  const left = Math.max(8, Math.min(at.x, window.innerWidth - 420));
  // The submenu is the taller one (about 290px): keep it inside the window instead of letting it run off the bottom.
  const top = Math.max(8, Math.min(at.y, window.innerHeight - 300));

  return (
    <div ref={root} style={{ position: 'fixed', top, left }} className="z-30 flex items-start gap-1">
      <div role="menu" aria-label={t.developer.editor.commands} className="w-[200px] rounded-card border border-border bg-surface py-1 shadow-card">
        <button type="button" role="menuitem" aria-disabled title={m.ideOnly} className={disabled}>{m.goToDefinition}</button>
        <button type="button" role="menuitem" aria-disabled title={m.ideOnly} className={disabled}>{m.findReferences}</button>
        <hr className="my-1 border-border" />
        <button
          type="button"
          role="menuitem"
          aria-haspopup="menu"
          aria-expanded={sub}
          onClick={() => setSub((value) => !value)}
          onMouseEnter={() => setSub(true)}
          className={`${item} ${sub ? 'bg-primary text-white' : 'hover:bg-surface-alt'}`}
        >
          {m.liquimanager}<ChevronRight size={13} aria-hidden />
        </button>
        <button type="button" role="menuitem" aria-disabled title={m.ideOnly} className={disabled}>{m.palette}</button>
      </div>
      {sub && (
        <div role="menu" aria-label={m.liquimanager} className="w-[210px] rounded-card border border-border bg-surface py-1 shadow-card">
          <button type="button" role="menuitem" aria-disabled title={m.ideOnly} className={disabled}>{m.create}</button>
          {ACTIONS.map(({ kind, key }) => (
            <button key={kind} type="button" role="menuitem" onClick={() => onCommand(kind)} className={`${item} font-bold hover:bg-surface-alt`}>
              {m[key]}
            </button>
          ))}
          <button type="button" role="menuitem" aria-disabled title={m.ideOnly} className={disabled}>{m.more}</button>
          <button type="button" role="menuitem" aria-disabled title={m.ideOnly} className={disabled}>{m.observability}</button>
          <hr className="my-1 border-border" />
          <button type="button" role="menuitem" aria-disabled title={m.ideOnly} className={disabled}>{m.settings}</button>
        </div>
      )}
    </div>
  );
}
