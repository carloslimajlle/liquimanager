import { pageWindow } from '../features/operations/rules';
import { useMessages } from '../i18n/useMessages';

/** "‹ Anterior · 1 2 3 … 107 · Próxima ›". `page` is 0-based. */
export default function Pagination({
  page, totalPages, onPage,
}: {
  page: number;
  totalPages: number;
  onPage: (page: number) => void;
}) {
  const t = useMessages();
  if (totalPages <= 1) {
    return null;
  }

  return (
    <nav aria-label="Paginação" className="flex items-center gap-2 text-[12px] text-text-2">
      <button
        type="button"
        disabled={page === 0}
        onClick={() => onPage(page - 1)}
        className="enabled:hover:text-text disabled:opacity-40"
      >
        ‹ {t.common.previous}
      </button>
      <span aria-hidden>·</span>
      {pageWindow(page, totalPages).map((entry, index) =>
        entry === null ? (
          <span key={`gap-${index}`} aria-hidden>…</span>
        ) : (
          <button
            key={entry}
            type="button"
            aria-current={entry === page ? 'page' : undefined}
            onClick={() => onPage(entry)}
            className={entry === page ? 'font-bold text-text' : 'hover:text-text'}
          >
            {entry + 1}
          </button>
        ),
      )}
      <span aria-hidden>·</span>
      <button
        type="button"
        disabled={page >= totalPages - 1}
        onClick={() => onPage(page + 1)}
        className="enabled:hover:text-text disabled:opacity-40"
      >
        {t.common.next} ›
      </button>
    </nav>
  );
}
