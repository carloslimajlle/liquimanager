import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import { MutationCache, QueryCache, QueryClient, QueryClientProvider } from '@tanstack/react-query';

import { ApiError } from './api/client';
import { reportDenied } from './api/denied';
import App from './App';
import AuthGate from './auth/AuthGate';
import { DEFAULT_LOCALE, messagesFor } from './i18n';
import { LocaleContext } from './i18n/useMessages';
import './styles/theme.css';

// A 403 from anywhere is told to the person once, in one place (AccessDeniedBanner), instead of each screen staying silently empty.
const onError = (error: unknown) => {
  if (error instanceof ApiError && error.status === 403) {
    reportDenied();
  }
};

const queryClient = new QueryClient({
  queryCache: new QueryCache({ onError }),
  mutationCache: new MutationCache({ onError }),
  defaultOptions: {
    queries: {
      // The dashboard has its own SSE feed (RNF-19); the other screens do not need
      // to revalidate on every window focus.
      refetchOnWindowFocus: false,
      // A refusal (403) or a missing thing (404) will not change by asking again; only network trouble and 5xx are worth a retry.
      retry: (failures, error) => !(error instanceof ApiError && error.status < 500) && failures < 2,
      staleTime: 30_000,
    },
  },
});

// Only pt-BR is offered in the first release (RNF-12), so the locale is fixed
// here. The provider already exists so that adding a second one later is a change
// to this value, not a hunt for hard-coded strings across the screens.
const locale = DEFAULT_LOCALE;

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <LocaleContext value={{ locale, messages: messagesFor(locale) }}>
      <QueryClientProvider client={queryClient}>
        <BrowserRouter>
          <AuthGate>
            <App />
          </AuthGate>
        </BrowserRouter>
      </QueryClientProvider>
    </LocaleContext>
  </StrictMode>,
);
