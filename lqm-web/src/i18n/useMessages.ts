import { createContext, useContext } from 'react';

import { DEFAULT_LOCALE, messagesFor } from './index';
import type { Locale, Messages } from './index';

type LocaleContextValue = {
  locale: Locale;
  messages: Messages;
};

/**
 * Locale context.
 *
 * It already exists even though only pt-BR is offered, so components read their
 * strings through a hook from the start. Shipping the second locale then means
 * adding it to AVAILABLE_LOCALES and providing a different value here — not
 * hunting for hard-coded text across the screens.
 */
export const LocaleContext = createContext<LocaleContextValue>({
  locale: DEFAULT_LOCALE,
  messages: messagesFor(DEFAULT_LOCALE),
});

/** Strings for the active locale. */
export function useMessages(): Messages {
  return useContext(LocaleContext).messages;
}

/** The active locale, for Intl formatting. */
export function useLocale(): Locale {
  return useContext(LocaleContext).locale;
}
