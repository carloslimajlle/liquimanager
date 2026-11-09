/**
 * Translation layer.
 *
 * RNF-20 asks for externalised strings even while only pt-BR ships. So the
 * catalogues are keyed and typed from day one, but `AVAILABLE_LOCALES` lists only
 * pt-BR: the English catalogue exists as the structural counterpart and is not
 * offered in the interface yet.
 *
 * Why not react-i18next: the app needs none of it right now — no plurals, no
 * lazy-loaded namespaces, no runtime locale switching. A typed record gives
 * compile-time safety against a missing key, which a runtime library does not.
 * Swap it in when the second locale actually ships.
 *
 * Liquibase terms (update, rollback, changeset, drift, tag, context, label) stay
 * in English in every catalogue — they are the domain vocabulary (docs/10 § 4).
 */
import { pt } from './pt';
import type { Messages } from './pt';
import { en } from './en';

export type Locale = 'pt-BR' | 'en';

/** Locales offered in the interface. First release: pt-BR only (RNF-12). */
export const AVAILABLE_LOCALES: Locale[] = ['pt-BR'];

export const DEFAULT_LOCALE: Locale = 'pt-BR';

const CATALOGUES: Record<Locale, Messages> = {
  'pt-BR': pt,
  en,
};

/**
 * Catalogue for a locale. Falls back to the default rather than throwing: a
 * missing locale must never blank out the screen.
 */
export function messagesFor(locale: Locale = DEFAULT_LOCALE): Messages {
  return CATALOGUES[locale] ?? CATALOGUES[DEFAULT_LOCALE];
}

/** BCP 47 tag for Intl formatting. */
export function intlLocale(locale: Locale = DEFAULT_LOCALE): string {
  return locale === 'en' ? 'en-US' : 'pt-BR';
}

/**
 * Fills `{name}` placeholders. A placeholder with no value is left visible rather than
 * swallowed, so a forgotten variable shows up on screen instead of leaving a silent gap.
 */
export function fill(template: string, values: Record<string, string | number>): string {
  return template.replace(/\{(\w+)\}/g, (match, key: string) =>
    key in values ? String(values[key]) : match,
  );
}

export type { Messages };
