/**
 * Mirrors the server's separation-of-duties rules (docs/09 § 4) so the grant dialog can warn BEFORE
 * the request. The server is still the judge: this only saves a round trip, and must never be the
 * only check.
 */

function covers(held: string, required: string): boolean {
  const h = held.trim().toLowerCase();
  const r = required.trim().toLowerCase();
  if (h === r) {
    return true;
  }
  const [hr, ...hRest] = h.split(':');
  const [rr, ...rRest] = r.split(':');
  if (hr !== rr || hRest.length === 0 || rRest.length === 0) {
    return false;
  }
  const what = hRest.join(':');
  return what === '*' || what.split(',').includes(rRest.join(':'));
}

/** A binding's real permissions: template and extras, minus what it denies itself. */
export function effective(template: string[], extra: string[], denied: string[]): string[] {
  return Array.from(new Set([...template, ...extra])).filter((p) => !denied.some((d) => covers(d, p)));
}

/** SoD-3: deploy to production and approve exceptions in one binding. */
export function violatesSod3(permissions: string[]): boolean {
  return permissions.some((p) => covers(p, 'deploy:prd')) && permissions.some((p) => covers(p, 'exception:approve'));
}

/** SoD-4: does this grant need an approver and a deadline? */
export function needsApproval(scopeType: string, scopeIsProduction: boolean, template?: string): boolean {
  return template === 'BREAKGLASS' || (scopeType === 'ENVIRONMENT' && scopeIsProduction);
}

export function splitList(text: string): string[] {
  return text.split(',').map((s) => s.trim()).filter(Boolean);
}
