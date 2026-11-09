/**
 * Presentation rules for the governance screens. No React, so they test without a DOM.
 */
import type { GateResult } from '../../api/governance';
import type { Messages } from '../../i18n';
import type { Tone } from '../../components/StatusBadge';

/** Severity 0..4 to a badge tone (docs/08 § 1). Text is always shown next to it (RNF-13). */
export function severityTone(severity: number): Tone {
  switch (severity) {
    case 0: return 'neutral';
    case 1: return 'minor';
    case 2: return 'warning';
    default: return 'danger';
  }
}

export function severityLabel(severity: number, t: Messages): string {
  const labels = t.governance.severity as Record<number, string>;
  return labels[severity] ?? String(severity);
}

export function gateTone(result: GateResult | null | undefined): Tone {
  switch (result) {
    case 'PASSED': return 'success';
    case 'WARNED': return 'warning';
    case 'BLOCKED': return 'danger';
    default: return 'neutral';
  }
}

export function gateLabel(result: GateResult | null | undefined, t: Messages): string {
  return result ? t.governance.gate[result] : t.governance.gate.none;
}

/** Highest severity first, so a gate card reads "2 CRITICAL, 1 MAJOR" and not the reverse. */
export function severityEntries(bySeverity: Record<string, number>): Array<[string, number]> {
  const order = ['BLOCKER', 'CRITICAL', 'MAJOR', 'MINOR', 'INFO'];
  return order.filter((name) => (bySeverity[name] ?? 0) > 0).map((name) => [name, bySeverity[name]]);
}
