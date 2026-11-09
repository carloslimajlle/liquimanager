/**
 * Whether the API has just refused something with 403. The screens already hide what a person cannot do; this is for the person
 * who can do nothing at all (signed in, but with no binding yet), whose every request comes back 403 and who would otherwise
 * face screens that simply stay empty. `count` changes with every refusal, so a banner that was dismissed shows again.
 */
let count = 0;
const listeners = new Set<() => void>();

export function reportDenied(): void {
  count += 1;
  listeners.forEach((listener) => listener());
}

export function deniedCount(): number {
  return count;
}

export function subscribeDenied(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}
