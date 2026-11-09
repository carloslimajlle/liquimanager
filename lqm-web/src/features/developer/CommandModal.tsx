import CodeBlock from '../../components/CodeBlock';
import { fill } from '../../i18n';
import { useMessages } from '../../i18n/useMessages';

/** What a menu item that runs something shows on the web: the CLI line to copy, since running is not done from here. */
export default function CommandModal({ name, command, onClose }: { name: string; command: string; onClose: () => void }) {
  const t = useMessages();
  const c = t.developer.command;

  return (
    <div className="fixed inset-0 z-40 flex items-center justify-center bg-black/30" role="presentation">
      <div role="dialog" aria-modal="true" aria-label={fill(c.title, { name })}
        className="flex w-[640px] max-w-[92vw] flex-col gap-3 rounded-card border border-border bg-surface p-4 shadow-card">
        <h2 className="text-[15px] font-bold">{fill(c.title, { name })}</h2>
        <p className="text-[12.5px] text-text-2">{c.hint}</p>
        <CodeBlock copyable>{command}</CodeBlock>
        {command.includes('<jdbc-url>') && <p className="text-[12px] text-text-3">{c.jdbc}</p>}
        <div className="flex justify-end">
          <button type="button" onClick={onClose} autoFocus
            className="rounded-control border border-border px-3 py-1.5 text-[12px] font-semibold hover:bg-surface-alt">{c.close}</button>
        </div>
      </div>
    </div>
  );
}
