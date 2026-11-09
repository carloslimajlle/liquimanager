import { ArrowDownRight, ArrowUpRight } from 'lucide-react';

/**
 * KPI card. The first card of each main band is `hero` (blue, gradient); the rest
 * are white (docs/10-design-system.md § 2 and § 3).
 */
export type Trend = {
  value: string;
  direction: 'up' | 'down';
  /** Whether the direction is good news. A drop in failures is `down` + good = green. */
  good: boolean;
};

const VALUE_TONES = {
  default: '',
  success: 'text-success',
  warning: 'text-warning',
  danger: 'text-danger',
} as const;

export default function KpiCard({
  label, value, unit, sub, trend, hero = false, tone = 'default',
}: {
  label: string;
  value: string;
  unit?: string;
  sub?: string;
  trend?: Trend;
  hero?: boolean;
  /** Colours the value (not the hero card, which is always white on blue). */
  tone?: keyof typeof VALUE_TONES;
}) {
  const Arrow = trend?.direction === 'up' ? ArrowUpRight : ArrowDownRight;
  return (
    <div
      className={[
        'rounded-card px-4 py-3.5',
        hero
          ? 'border-0 bg-linear-[135deg,#3552e0,#2b47cf] text-white'
          : 'border border-border bg-surface shadow-card',
      ].join(' ')}
    >
      <div className="flex items-center">
        <div
          className={
            hero
              ? 'text-[11px] font-semibold tracking-[0.08em] text-white/85 uppercase'
              : 'text-[12px] font-medium text-text-2'
          }
        >
          {label}
        </div>
        {trend && (
          <span
            className={`ml-auto flex items-center gap-0.5 text-[12px] font-semibold ${
              hero ? 'text-white/90' : trend.good ? 'text-success' : 'text-danger'
            }`}
          >
            <Arrow size={13} aria-hidden />
            {trend.value}
          </span>
        )}
      </div>
      <div
        className={`mt-1 flex items-baseline gap-1.5 text-[26px] font-bold tracking-[-0.02em] ${
          hero ? '' : VALUE_TONES[tone]
        }`}
      >
        {value}
        {unit && (
          <small className={`text-[12px] font-medium ${hero ? 'text-white/80' : 'text-text-2'}`}>
            {unit}
          </small>
        )}
      </div>
      {sub && (
        <div className={`mt-0.5 text-[12px] ${hero ? 'text-white/80' : 'text-text-3'}`}>{sub}</div>
      )}
    </div>
  );
}
