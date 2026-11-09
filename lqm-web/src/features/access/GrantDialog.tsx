import { useEffect, useState } from 'react';

import { useAccessActions, usePrincipals, useRoleTemplates } from '../../api/access';
import type { Principal } from '../../api/access';
import { useEnvironments, useProjects } from '../../api/catalog';
import { useMessages } from '../../i18n/useMessages';
import { effective, needsApproval, splitList, violatesSod3 } from './rules';

const field = 'rounded-control border border-border bg-surface px-3 py-1.5 text-[13px] font-normal';
const label = 'flex flex-col gap-1 text-[12px] font-semibold';

/** "Conceder acesso" (screen 08): principal, role, scope. An SoD violation shows inline and blocks the button. */
export default function GrantDialog({ onClose, initialSearch = '' }: { onClose: () => void; initialSearch?: string }) {
  const t = useMessages();
  const g = t.access.grant;
  const actions = useAccessActions();
  const templates = useRoleTemplates();
  const environments = useEnvironments();
  const projects = useProjects();

  const [search, setSearch] = useState(initialSearch);
  const principals = usePrincipals(search);
  const [principal, setPrincipal] = useState<Principal | null>(null);
  // Opened from a request for access: the person who asked is already chosen, as soon as the list finds them.
  useEffect(() => {
    if (initialSearch && principal === null) {
      const found = (principals.data ?? []).find((item) => item.externalId.toLowerCase() === initialSearch.toLowerCase());
      if (found) {
        setPrincipal(found);
      }
    }
  }, [initialSearch, principal, principals.data]);
  const [registering, setRegistering] = useState(false);
  const [externalId, setExternalId] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [kind, setKind] = useState<'USER' | 'GROUP'>('USER');

  const [template, setTemplate] = useState('');
  const [extra, setExtra] = useState('');
  const [denied, setDenied] = useState('');
  const [scopeType, setScopeType] = useState('WORKSPACE');
  const [scopeId, setScopeId] = useState('');
  const [approver, setApprover] = useState('');
  const [expires, setExpires] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const chosenTemplate = (templates.data ?? []).find((x) => x.code === template);
  const permissions = effective(chosenTemplate?.permissions ?? [], splitList(extra), splitList(denied));
  const production = scopeType === 'ENVIRONMENT' && (environments.data ?? []).some((e) => e.id === scopeId && e.isProduction);
  const reachesProduction = production || scopeType !== 'ENVIRONMENT';
  const sod3 = reachesProduction && violatesSod3(permissions);
  const sod4 = needsApproval(scopeType, production, template) && (!approver.trim() || !expires);
  const hasContent = template !== '' || splitList(extra).length > 0 || splitList(denied).length > 0;
  const needsScopeId = scopeType !== 'WORKSPACE' && !scopeId;
  const disabled = busy || !principal || !hasContent || sod3 || sod4 || needsScopeId;

  async function register() {
    setError(null);
    try {
      setPrincipal(await actions.createPrincipal({ type: kind, externalId: externalId.trim(), displayName: displayName.trim() }));
      setRegistering(false);
    } catch (err) {
      setError(err instanceof Error && err.message ? err.message : g.error);
    }
  }

  async function submit() {
    setBusy(true);
    setError(null);
    try {
      await actions.grant({
        principalId: principal!.id,
        roleTemplate: template || undefined,
        permissions: splitList(extra),
        deniedPermissions: splitList(denied),
        scopeType,
        scopeId: scopeType === 'WORKSPACE' ? undefined : scopeId,
        approver: approver.trim() || undefined,
        expiresAt: expires ? new Date(expires).toISOString() : undefined,
      });
      onClose();
    } catch (err) {
      setError(err instanceof Error && err.message ? err.message : g.error);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div role="dialog" aria-modal="true" aria-label={g.title}
      className="fixed inset-0 z-50 flex items-center justify-center bg-text/30 p-6">
      <div className="flex max-h-full w-[640px] flex-col gap-3 overflow-auto rounded-card border border-border bg-surface p-5 shadow-overlay">
        <div>
          <h2 className="text-[18px] font-bold">{g.title}</h2>
          <p className="text-[12.5px] text-text-2">{g.subtitle}</p>
        </div>

        <fieldset className="flex flex-col gap-2">
          <legend className="text-[12px] font-semibold">{g.principal}</legend>
          {registering ? (
            <div className="grid grid-cols-2 gap-2">
              <label className={label}>{g.externalId}
                <input className={field} value={externalId} onChange={(e) => setExternalId(e.target.value)} />
              </label>
              <label className={label}>{g.displayName}
                <input className={field} value={displayName} onChange={(e) => setDisplayName(e.target.value)} />
              </label>
              <label className={label}>{g.kind}
                <select className={field} value={kind} onChange={(e) => setKind(e.target.value as 'USER' | 'GROUP')}>
                  <option value="USER">{t.access.table.user}</option>
                  <option value="GROUP">{t.access.table.group}</option>
                </select>
              </label>
              <div className="flex items-end">
                <button type="button" onClick={register} disabled={!externalId.trim() || !displayName.trim()}
                  className="rounded-control bg-primary px-3 py-1.5 text-[12px] font-semibold text-white disabled:opacity-50">
                  {g.newPrincipal}
                </button>
              </div>
            </div>
          ) : (
            <>
              <input aria-label={g.search} placeholder={g.search} className={field} value={search}
                onChange={(e) => setSearch(e.target.value)} />
              <div role="radiogroup" aria-label={g.principal} className="flex max-h-36 flex-col gap-1 overflow-auto">
                {(principals.data ?? []).map((p) => (
                  <label key={p.id}
                    className={`flex cursor-pointer items-center gap-2 rounded-control border px-3 py-1.5 text-[13px] ${
                      principal?.id === p.id ? 'border-primary bg-primary-50' : 'border-border'
                    }`}>
                    <input type="radio" name="principal" checked={principal?.id === p.id} onChange={() => setPrincipal(p)} />
                    <span className="font-semibold">{p.displayName}</span>
                    <span className="text-text-3">{p.type === 'GROUP' ? t.access.table.group : t.access.table.user} · {p.externalId}</span>
                  </label>
                ))}
              </div>
              <button type="button" onClick={() => setRegistering(true)}
                className="self-start text-[12px] font-semibold text-primary hover:underline">{g.newPrincipal}</button>
            </>
          )}
        </fieldset>

        <label className={label}>{g.template}
          <select className={field} value={template} onChange={(e) => setTemplate(e.target.value)}>
            <option value="">{g.templateNone}</option>
            {(templates.data ?? []).map((x) => <option key={x.code} value={x.code}>{x.name} ({x.code})</option>)}
          </select>
        </label>
        <div className="grid grid-cols-2 gap-3">
          <label className={label}>{g.extra}
            <input className={`${field} font-mono`} value={extra} onChange={(e) => setExtra(e.target.value)} />
            <span className="font-normal text-text-3">{g.extraHint}</span>
          </label>
          <label className={label}>{g.deniedField}
            <input className={`${field} font-mono`} value={denied} onChange={(e) => setDenied(e.target.value)} />
            <span className="font-normal text-text-3">{g.deniedHint}</span>
          </label>
        </div>

        <div className="grid grid-cols-2 gap-3">
          <label className={label}>{g.scope}
            <select className={field} value={scopeType} onChange={(e) => { setScopeType(e.target.value); setScopeId(''); }}>
              {['WORKSPACE', 'PROJECT', 'ENVIRONMENT'].map((s) => (
                <option key={s} value={s}>{t.access.scopes[s as keyof typeof t.access.scopes]}</option>
              ))}
            </select>
          </label>
          {scopeType !== 'WORKSPACE' && (
            <label className={label}>{g.scopeId}
              <select className={field} value={scopeId} onChange={(e) => setScopeId(e.target.value)}>
                <option value="" />
                {scopeType === 'PROJECT'
                  ? (projects.data ?? []).map((p) => <option key={p.id} value={p.id}>{p.name}</option>)
                  : (environments.data ?? []).map((env) => <option key={env.id} value={env.id}>{env.code}</option>)}
              </select>
            </label>
          )}
          <label className={label}>{g.approver}
            <input className={field} value={approver} onChange={(e) => setApprover(e.target.value)} />
          </label>
          <label className={label}>{g.expires}
            <input type="datetime-local" className={field} value={expires} onChange={(e) => setExpires(e.target.value)} />
            <span className="font-normal text-text-3">{g.expiresHint}</span>
          </label>
        </div>

        {sod3 && <p role="alert" className="rounded-control bg-danger-bg px-3 py-2 text-[12.5px] text-danger">{g.sod3}</p>}
        {sod4 && !sod3 && <p role="alert" className="rounded-control bg-warning-bg px-3 py-2 text-[12.5px] text-warning">{g.sod4}</p>}
        {error && <p role="alert" className="text-[12.5px] text-danger">{error}</p>}

        <div className="flex justify-end gap-2">
          <button type="button" onClick={onClose}
            className="rounded-control border border-border px-3 py-1.5 text-[12px] font-semibold hover:bg-surface-alt">{g.cancel}</button>
          <button type="button" onClick={submit} disabled={disabled}
            className="rounded-control bg-primary px-3 py-1.5 text-[12px] font-semibold text-white disabled:opacity-50">{g.submit}</button>
        </div>
      </div>
    </div>
  );
}
