import { useEffect, useRef, useState } from 'react';

import { useMessages } from '../../i18n/useMessages';

const KEY = 'lqm.drift.views';

type View = { name: string; search: string };

/** Saved views are a convenience of this browser: they are read and written with every failure swallowed (private windows, blocked storage). */
function load(): View[] {
  try {
    const parsed = JSON.parse(window.localStorage.getItem(KEY) ?? '[]');
    return Array.isArray(parsed) ? parsed.filter((v) => typeof v?.name === 'string' && typeof v?.search === 'string') : [];
  } catch {
    return [];
  }
}

function store(views: View[]) {
  try {
    window.localStorage.setItem(KEY, JSON.stringify(views));
  } catch {
    // Nothing to do: the views simply do not survive a reload.
  }
}

/** "Copiar link" and "Visões" of the drift screen (docs/telas/09): the filters live in the URL, so a link is the filter. */
export default function DriftShare({ search, onOpen }: { search: string; onOpen: (search: string) => void }) {
  const t = useMessages();
  const d = t.drift;
  const [views, setViews] = useState<View[]>(load);
  const [open, setOpen] = useState(false);
  const [copied, setCopied] = useState(false);
  const root = useRef<HTMLDivElement>(null);

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

  async function copy() {
    try {
      await navigator.clipboard.writeText(window.location.href);
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    } catch {
      // Clipboard access can be denied; the address bar still has the link.
    }
  }

  function save() {
    const name = window.prompt(d.views.name)?.trim();
    if (!name) {
      return;
    }
    const next = [...views.filter((v) => v.name !== name), { name, search }];
    setViews(next);
    store(next);
  }

  function remove(name: string) {
    const next = views.filter((v) => v.name !== name);
    setViews(next);
    store(next);
  }

  const pill = 'rounded-control border border-border bg-surface px-2.5 py-1.5 text-[12px] font-semibold hover:bg-surface-alt';
  return (
    <div ref={root} className="relative flex items-center gap-2">
      <button type="button" className={pill} onClick={copy}>{copied ? d.link.copied : d.link.copy}</button>
      <button type="button" className={pill} aria-haspopup="menu" aria-expanded={open} onClick={() => setOpen((v) => !v)}>{d.views.label(views.length)}</button>
      {open && (
        <div role="menu" className="absolute right-0 top-9 z-20 w-[260px] rounded-lg border border-border bg-surface py-1 shadow-card">
          {views.length === 0 && <p className="px-3 py-2 text-[12.5px] text-text-2">{d.views.empty}</p>}
          {views.map((view) => (
            <div key={view.name} className="flex items-center hover:bg-surface-alt">
              <button type="button" role="menuitem" className="flex-1 px-3 py-1.5 text-left text-[12.5px]" onClick={() => { onOpen(view.search); setOpen(false); }}>{view.name}</button>
              <button type="button" aria-label={`${d.views.remove}: ${view.name}`} className="px-2 text-text-3 hover:text-danger" onClick={() => remove(view.name)}>×</button>
            </div>
          ))}
          <button type="button" role="menuitem" className="block w-full border-t border-border px-3 py-1.5 text-left text-[12.5px] font-semibold text-primary hover:bg-surface-alt" onClick={save}>{d.views.save}</button>
        </div>
      )}
    </div>
  );
}
