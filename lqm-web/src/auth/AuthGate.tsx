import { useEffect, useMemo, useState, useSyncExternalStore } from 'react';
import type { ReactNode } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';

import { useMessages } from '../i18n/useMessages';
import { CALLBACK_PATH, authEnabled, completeSignIn, currentProfile, loadSession, signIn, subscribe } from './auth';
import type { Profile } from './auth';
import BreakGlassPage from './BreakGlassPage';
import { BREAK_GLASS_PATH, breakGlassSession, subscribeBreakGlass } from './breakGlass';

/** The signed-in person, or null when the app runs without an identity provider. Re-renders when the session changes. */
export function useProfile(): Profile | null {
  const oidc = useSyncExternalStore(subscribe, currentProfile, () => null);
  const emergency = useSyncExternalStore(subscribeBreakGlass, breakGlassSession, () => null);
  return useMemo(
    () => (emergency ? { name: emergency.username, username: emergency.username, initials: '!' } : oidc),
    [emergency, oidc],
  );
}

type State = 'checking' | 'ready' | 'failed';

/**
 * Holds the app back until there is a session: it finishes the sign-in on the callback route, or sends the browser to
 * the identity provider. Without an identity provider (local development) it lets everything through.
 */
export default function AuthGate({ children }: { children: ReactNode }) {
  const location = useLocation();
  const emergency = useSyncExternalStore(subscribeBreakGlass, breakGlassSession, () => null);
  // The emergency account is for when the identity provider is down: its page cannot wait for a sign-in with it.
  if (location.pathname === BREAK_GLASS_PATH) {
    return <BreakGlassPage />;
  }
  if (emergency) {
    return <>{children}</>;
  }
  return <ProviderGate>{children}</ProviderGate>;
}

function ProviderGate({ children }: { children: ReactNode }) {
  const t = useMessages();
  const location = useLocation();
  const navigate = useNavigate();
  const [state, setState] = useState<State>(authEnabled ? 'checking' : 'ready');

  useEffect(() => {
    if (!authEnabled) {
      return;
    }
    if (location.pathname === CALLBACK_PATH) {
      completeSignIn()
        .then((returnTo) => {
          navigate(returnTo, { replace: true });
          setState('ready');
        })
        .catch(() => setState('failed'));
      return;
    }
    loadSession()
      .then((signedIn) => (signedIn ? setState('ready') : signIn(location.pathname + location.search)))
      .catch(() => setState('failed'));
    // The gate decides once per page load: later navigation inside the app is not a new sign-in.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  if (state === 'ready') {
    return <>{children}</>;
  }
  return (
    <div role={state === 'failed' ? 'alert' : 'status'} className="grid min-h-full place-items-center p-10 text-center">
      {state === 'failed' ? (
        <div className="flex flex-col items-center gap-3">
          <p className="font-semibold">{t.auth.failed}</p>
          <button type="button" onClick={() => { window.location.assign('/'); }}
            className="rounded-control bg-primary px-3 py-1.5 text-[12px] font-semibold text-white">{t.auth.retry}</button>
        </div>
      ) : (
        <p className="text-text-2">{t.auth.signingIn}</p>
      )}
    </div>
  );
}
