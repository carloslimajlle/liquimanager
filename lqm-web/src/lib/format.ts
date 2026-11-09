/**
 * Locale-aware formatting. Rules in docs/10-design-system.md § 4.
 *
 * Everything coming from the API is in UTC (RNF-12); display uses
 * America/Sao_Paulo. The locale is a parameter rather than a hard-coded 'pt-BR'
 * so these helpers keep working once a second locale ships (RNF-20) — the first
 * release still only offers pt-BR.
 */
import { DEFAULT_LOCALE, intlLocale } from '../i18n';
import type { Locale } from '../i18n';

const DISPLAY_TIMEZONE = 'America/Sao_Paulo';

function numberFormat(locale: Locale) {
  return new Intl.NumberFormat(intlLocale(locale));
}

function dateTimeFormat(locale: Locale) {
  return new Intl.DateTimeFormat(intlLocale(locale), {
    dateStyle: 'short',
    timeStyle: 'medium',
    timeZone: DISPLAY_TIMEZONE,
  });
}

function shortDateFormat(locale: Locale) {
  return new Intl.DateTimeFormat(intlLocale(locale), {
    day: '2-digit',
    month: 'short',
    timeZone: DISPLAY_TIMEZONE,
  });
}

function shortTimeFormat(locale: Locale) {
  return new Intl.DateTimeFormat(intlLocale(locale), {
    hour: '2-digit',
    minute: '2-digit',
    timeZone: DISPLAY_TIMEZONE,
  });
}

/** 1284 -> "1.284" in pt-BR */
export function formatNumber(value: number, locale: Locale = DEFAULT_LOCALE): string {
  return numberFormat(locale).format(value);
}

/** A fixed number of decimals: 0.5714 -> "0,57" in pt-BR. */
export function formatDecimal(value: number, decimals: number, locale: Locale = DEFAULT_LOCALE): string {
  return value.toLocaleString(intlLocale(locale), {
    minimumFractionDigits: decimals,
    maximumFractionDigits: decimals,
  });
}

/** 94.2 -> "94,2%" in pt-BR */
export function formatPercent(value: number, locale: Locale = DEFAULT_LOCALE, decimals = 1): string {
  const formatted = value.toLocaleString(intlLocale(locale), {
    minimumFractionDigits: decimals,
    maximumFractionDigits: decimals,
  });
  return `${formatted}%`;
}

/** "15/09/2026, 16:07:03" in pt-BR */
export function formatDateTime(iso: string, locale: Locale = DEFAULT_LOCALE): string {
  return dateTimeFormat(locale).format(new Date(iso));
}

/** Dense tables: "15 set · 16:07" in pt-BR */
export function formatShortDateTime(iso: string, locale: Locale = DEFAULT_LOCALE): string {
  const date = new Date(iso);
  return `${shortDateFormat(locale).format(date).replace('.', '')} · ${shortTimeFormat(locale).format(date)}`;
}

/**
 * The same duration split into a number and a unit, for cards that show the unit small
 * next to a large value ("1,1" and "s"). Rounding is identical to {@link formatDuration}.
 */
export function formatDurationParts(ms: number, locale: Locale = DEFAULT_LOCALE): { value: string; unit: string } {
  const text = formatDuration(ms, locale);
  const cut = text.lastIndexOf(' ');
  return { value: text.slice(0, cut), unit: text.slice(cut + 1) };
}

/**
 * Duration: milliseconds below 1 s, seconds below 1 h, hours above that.
 * 909 -> "909 ms" · 1100 -> "1,1 s" · 38880000 -> "10,8 h" (pt-BR)
 */
export function formatDuration(ms: number, locale: Locale = DEFAULT_LOCALE): string {
  if (ms < 1000) {
    return `${Math.round(ms)} ms`;
  }
  const seconds = ms / 1000;
  if (seconds < 3600) {
    return `${seconds.toLocaleString(intlLocale(locale), { maximumFractionDigits: 1 })} s`;
  }
  const hours = seconds / 3600;
  return `${hours.toLocaleString(intlLocale(locale), { maximumFractionDigits: 1 })} h`;
}

/** A day key ("2026-11-02", already in the display timezone) as a heading: "segunda-feira, 2 de novembro de 2026". */
export function formatDay(day: string, locale: Locale = DEFAULT_LOCALE): string {
  return new Intl.DateTimeFormat(intlLocale(locale), { dateStyle: 'full', timeZone: 'UTC' }).format(new Date(`${day}T12:00:00Z`));
}

/** Time of day only, for events already grouped under their day. */
export function formatTime(iso: string, locale: Locale = DEFAULT_LOCALE): string {
  return shortTimeFormat(locale).format(new Date(iso));
}
