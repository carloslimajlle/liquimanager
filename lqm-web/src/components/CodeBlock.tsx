import { useState } from 'react';

import { useMessages } from '../i18n/useMessages';

/**
 * Monospace block (docs/10 § 3). The `error` variant is the red one used for a failed
 * statement's message. A copy button is optional and copies exactly the text shown.
 */
export default function CodeBlock({
  children, variant = 'default', copyable = false,
}: {
  children: string;
  variant?: 'default' | 'error';
  copyable?: boolean;
}) {
  const t = useMessages();
  const [copied, setCopied] = useState(false);

  async function copy() {
    try {
      await navigator.clipboard.writeText(children);
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    } catch {
      // Clipboard access can be denied (insecure context, permissions). The text is
      // still selectable on screen, so failing quietly beats an error nobody can act on.
    }
  }

  return (
    <div className="relative">
      {copyable && (
        <button
          type="button"
          onClick={copy}
          className="absolute top-2 right-2 text-[12px] font-semibold text-primary hover:text-primary-600"
        >
          {copied ? t.common.copied : t.common.copy}
        </button>
      )}
      <pre
        className={`overflow-x-auto rounded-control border px-3 py-2.5 font-mono text-[12px] leading-[1.55] whitespace-pre-wrap ${
          variant === 'error'
            ? 'border-[#f6c9c9] bg-[#fff5f5] text-[#b42318]'
            : 'border-border bg-surface-alt text-text'
        }`}
      >
        {children}
      </pre>
    </div>
  );
}
