import type { ReactNode } from 'react';

/** Page title block: h1 and subtitle on the left, an optional right-hand slot (filters, stats). */
export default function PageHeader({
  title, subtitle, right,
}: {
  title: string;
  subtitle?: string;
  right?: ReactNode;
}) {
  return (
    <div className="mt-1.5 mb-3.5 flex items-end gap-4">
      <div>
        <h1 className="text-[26px] font-bold tracking-[-0.02em]">{title}</h1>
        {subtitle && <p className="mt-1 text-text-2">{subtitle}</p>}
      </div>
      {right && <div className="ml-auto flex items-center gap-2">{right}</div>}
    </div>
  );
}
