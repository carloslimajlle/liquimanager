/**
 * Environment badge. The colour comes from the `color_token` stored on
 * `environment` (D-15: the environment list is configurable), resolved through the
 * --env-*-fg/bg variables defined in styles/theme.css.
 */
export default function EnvBadge({ code }: { code: string }) {
  const token = code.toLowerCase();
  return (
    <span
      className="rounded-env px-1.5 py-0.5 text-[11px] font-bold tracking-[0.02em]"
      style={{
        color: `var(--env-${token}-fg, var(--color-text-2))`,
        background: `var(--env-${token}-bg, #eef0f4)`,
      }}
    >
      {code}
    </span>
  );
}
