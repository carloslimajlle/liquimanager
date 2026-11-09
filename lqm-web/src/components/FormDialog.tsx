import type { ReactNode } from 'react';

import { useMessages } from '../i18n/useMessages';

export const fieldClass = 'rounded-control border border-border bg-surface px-3 py-1.5 text-[13px] font-normal';
export const labelClass = 'flex flex-col gap-1 text-[12px] font-semibold';
export const hintClass = 'font-normal text-text-3';
export const linkClass = 'text-[12px] font-semibold text-primary hover:underline';
export const dangerLinkClass = 'text-[12px] font-semibold text-danger hover:underline';
export const primaryButton = 'rounded-control bg-primary px-3 py-1.5 text-[12px] font-semibold text-white disabled:opacity-50';
export const secondaryButton = 'rounded-control border border-border px-3 py-1.5 text-[12px] font-semibold hover:bg-surface-alt';

/** A text label above a control, with an optional hint under it. */
export function Field({ label, hint, children }: { label: string; hint?: string; children: ReactNode }) {
  return (
    <label className={labelClass}>
      {label}
      {children}
      {hint && <span className={hintClass}>{hint}</span>}
    </label>
  );
}

/**
 * The modal every cadastro form uses: title, body, the error of the last attempt and Cancel/Save.
 * Saving is the caller's job; this only keeps the markup, the roles and the buttons the same everywhere.
 */
export default function FormDialog({
  title, onClose, onSave, saveDisabled = false, busy = false, error, width = 560, children,
}: {
  title: string;
  onClose: () => void;
  onSave: () => void;
  saveDisabled?: boolean;
  busy?: boolean;
  error?: string | null;
  width?: number;
  children: ReactNode;
}) {
  const t = useMessages();
  return (
    <div role="dialog" aria-modal="true" aria-label={title}
      className="fixed inset-0 z-50 flex items-center justify-center bg-text/30 p-6">
      <div style={{ width }} className="flex max-h-full flex-col gap-3 overflow-auto rounded-card border border-border bg-surface p-5 shadow-overlay">
        <h2 className="text-[18px] font-bold">{title}</h2>
        {children}
        {error && <p role="alert" className="text-[12.5px] text-danger">{error}</p>}
        <div className="flex justify-end gap-2">
          <button type="button" onClick={onClose} className={secondaryButton}>{t.admin.common.cancel}</button>
          <button type="button" disabled={saveDisabled || busy} onClick={onSave} className={primaryButton}>{t.admin.common.save}</button>
        </div>
      </div>
    </div>
  );
}

/** Turns a failed call into the sentence shown in the dialog: the API's own message when it has one. */
export function messageOf(err: unknown, fallback: string): string {
  return err instanceof Error && err.message ? err.message : fallback;
}
