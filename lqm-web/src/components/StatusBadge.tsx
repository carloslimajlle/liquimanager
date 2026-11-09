/**
 * Status badge. RNF-13: status is NEVER colour alone — the text is always shown.
 * Mapping per docs/10-design-system.md § 3.
 */
export type Tone = 'success' | 'danger' | 'warning' | 'info' | 'neutral' | 'purple' | 'minor';

const TONES: Record<Tone, string> = {
  success: 'bg-success-bg text-success',
  danger: 'bg-danger-bg text-danger',
  warning: 'bg-warning-bg text-warning',
  info: 'bg-info-bg text-info',
  purple: 'bg-purple-bg text-purple',
  minor: 'bg-minor-bg text-minor',
  neutral: 'bg-[#eef0f4] text-text-2',
};

/**
 * Known outcomes mapped to a tone. The keys are the pt-BR labels shown in the
 * interface (RNF-12); anything unmapped falls back to `neutral`. Once a second
 * locale ships, callers should pass `tone` explicitly rather than relying on this.
 */
const BY_TEXT: Record<string, Tone> = {
  'sucesso': 'success', 'passou': 'success', 'limpo': 'success', 'executado': 'success',
  'falha': 'danger', 'bloqueado': 'danger', 'falhou': 'danger',
  'drift': 'warning', 'alertado': 'warning', 'break-fix': 'warning', 'filtrado': 'warning',
  'pendente': 'info', 'já executado': 'info',
  'não alvo': 'neutral', 'não executado': 'neutral',
};

export default function StatusBadge({ children, tone }: { children: string; tone?: Tone }) {
  const chosen = tone ?? BY_TEXT[children.toLowerCase()] ?? 'neutral';
  return (
    <span
      className={`inline-flex items-center gap-1 whitespace-nowrap rounded-badge px-2 py-0.5 text-[11.5px] font-semibold ${TONES[chosen]}`}
    >
      {children}
    </span>
  );
}
