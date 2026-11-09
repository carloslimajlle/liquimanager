import { useState } from 'react';
import type { FormEvent } from 'react';

import { useMessages } from '../i18n/useMessages';
import { BreakGlassError, signInBreakGlass } from './breakGlass';

const field = 'rounded-control border border-border bg-surface px-3 py-2 text-[13px] font-normal';

/**
 * /break-glass: the sign-in of the emergency account, outside the identity provider's gate (it exists for when the provider is down).
 * Name, password and the code of the authenticator app, all at once; the refusal never says which one was wrong.
 */
export default function BreakGlassPage() {
  const t = useMessages();
  const b = t.breakGlass;
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [code, setCode] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await signInBreakGlass(username.trim(), password, code.trim());
      window.location.assign('/');
    } catch (err) {
      setError(b.errors[err instanceof BreakGlassError ? err.reason : 'error']);
      setPassword('');
      setCode('');
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="grid min-h-full place-items-center p-6">
      <form onSubmit={submit} aria-labelledby="bg-title" className="flex w-[380px] flex-col gap-3 rounded-card border border-border bg-surface p-5 shadow-card">
        <h1 id="bg-title" className="text-[16px] font-bold">{b.title}</h1>
        <p className="text-[12.5px] text-text-2">{b.hint}</p>
        <label className="flex flex-col gap-1 text-[12px] font-semibold">
          {b.username}
          <input className={field} value={username} autoComplete="off" autoFocus onChange={(ev) => setUsername(ev.target.value)} />
        </label>
        <label className="flex flex-col gap-1 text-[12px] font-semibold">
          {b.password}
          <input className={field} type="password" value={password} autoComplete="off" onChange={(ev) => setPassword(ev.target.value)} />
        </label>
        <label className="flex flex-col gap-1 text-[12px] font-semibold">
          {b.code}
          <input className={`${field} font-mono tracking-widest`} inputMode="numeric" pattern="[0-9]{6}" maxLength={6} value={code}
            autoComplete="one-time-code" onChange={(ev) => setCode(ev.target.value.replace(/\D/g, ''))} />
        </label>
        {error && <p role="alert" className="text-[12.5px] text-danger">{error}</p>}
        <button type="submit" disabled={busy || username.trim() === '' || password === '' || code.length !== 6}
          className="rounded-control bg-primary px-3 py-2 text-[13px] font-semibold text-white disabled:opacity-50">{b.submit}</button>
        <a href="/" className="text-center text-[12px] font-semibold text-primary hover:underline">{b.back}</a>
      </form>
    </main>
  );
}
