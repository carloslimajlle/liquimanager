import { useEffect, useState } from 'react';
import type { ReactNode } from 'react';
import { useNavigate, useParams } from 'react-router-dom';

import { useAllChecks, useCheck, useLibraryMutations } from '../../api/governance';
import PageHeader from '../../components/PageHeader';
import { ErrorCard } from '../../components/States';
import StatusBadge from '../../components/StatusBadge';
import { useCan } from '../../auth/useCan';
import { useMessages } from '../../i18n/useMessages';
import Topbar from '../../shell/Topbar';
import {
  APPLIES_TO, CONSTRAINTS, EDITABLE_KINDS, OPERATORS, STATEMENT_TYPES,
  emptyForm, fromCheck, parametersOf, toBody, toggle, validate,
} from './checkForm';
import type { CheckForm, FormErrors } from './checkForm';
import GovernanceTabs from './GovernanceTabs';
import TestDialog from './TestDialog';
import { severityLabel } from './rules';

const input = 'w-full rounded-control border border-border bg-surface px-3 py-1.5 text-[13px] disabled:bg-surface-alt disabled:text-text-3';
const mono = `${input} font-mono`;

function Chip({ on, disabled, onClick, children }: { on: boolean; disabled: boolean; onClick: () => void; children: string }) {
  return (
    <button
      type="button"
      aria-pressed={on}
      disabled={disabled}
      onClick={onClick}
      className={`rounded-control border px-2.5 py-1 font-mono text-[11.5px] font-semibold ${
        on ? 'border-primary bg-primary-50 text-primary' : 'border-border text-text-2'
      } disabled:opacity-60`}
    >
      {children}
    </button>
  );
}

/** Screen 07, edit view: the parameter table of the target print, built from the kind of the check. */
export default function CheckEditor() {
  const t = useMessages();
  const canEdit = useCan()('policy:edit');
  const e = t.governance.editor;
  const navigate = useNavigate();
  const { id } = useParams();
  const creating = id === undefined;
  const detail = useCheck(id);
  const all = useAllChecks();
  const mutations = useLibraryMutations();

  const [form, setForm] = useState<CheckForm>(emptyForm());
  const [errors, setErrors] = useState<FormErrors>({});
  const [failure, setFailure] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);
  const [testing, setTesting] = useState(false);

  useEffect(() => {
    if (detail.data) {
      setForm(fromCheck(detail.data as Parameters<typeof fromCheck>[0]));
    }
  }, [detail.data]);

  const builtin = !creating && (detail.data?.prebuilt ?? true);
  const readOnly = builtin;
  const set = <K extends keyof CheckForm>(key: K, value: CheckForm[K]) => {
    setForm((f) => ({ ...f, [key]: value }));
    setSaved(false);
  };

  const categories = Array.from(new Set((all.data ?? []).map((c) => c.category))).sort();
  const memberChoices = (all.data ?? []).filter((c) => c.kind !== 'CHAINED' && c.code !== form.code);
  const trail = [t.governance.breadcrumbRoot, t.governance.library.title, creating ? e.breadcrumbNew : form.code];
  const title = creating ? e.titleNew : t.governance.library.title;

  async function save() {
    const found = validate(form, creating);
    setErrors(found);
    setFailure(null);
    if (Object.keys(found).length > 0) {
      return;
    }
    try {
      if (creating) {
        const created = await mutations.createCheck(toBody(form, true));
        navigate(`/govern/checks/${created.id}`, { replace: true });
      } else {
        await mutations.updateCheck(id!, toBody(form, false));
        setSaved(true);
      }
    } catch (err) {
      setFailure(err instanceof Error && err.message ? err.message : e.errors.save);
    }
  }

  async function remove() {
    if (!window.confirm(e.actions.confirmDelete)) {
      return;
    }
    try {
      await mutations.deleteCheck(id!);
      navigate('/govern/checks');
    } catch (err) {
      setFailure(err instanceof Error && err.message ? err.message : e.errors.save);
    }
  }

  async function copy() {
    try {
      const created = await mutations.copyCheck(id!);
      navigate(`/govern/checks/${created.id}`);
    } catch (err) {
      setFailure(err instanceof Error && err.message ? err.message : e.errors.save);
    }
  }

  const header = (
    <>
      <Topbar trail={trail} />
      <PageHeader
        title={title}
        subtitle={e.subtitle}
        right={
          !creating && (
            <StatusBadge tone={readOnly ? 'neutral' : 'info'}>{readOnly ? e.mode.view : e.mode.edit}</StatusBadge>
          )
        }
      />
      <GovernanceTabs />
    </>
  );

  if (!creating && detail.isError) {
    return (
      <>
        {header}
        <div className="rounded-card border border-border bg-surface shadow-card">
          <ErrorCard message={e.notFound} onRetry={() => detail.refetch()} />
        </div>
      </>
    );
  }

  const errorText = (key: keyof FormErrors): ReactNode =>
    errors[key] ? (
      <p role="alert" className="mt-1 text-[12px] text-danger">
        {key === 'code' ? e.errors.code : key === 'category' ? e.errors.category : key === 'members' ? e.errors.members
          : errors[key] === 'invalid' ? e.errors.invalid : e.errors.required}
      </p>
    ) : null;

  const row = (label: string, hint: string, control: ReactNode) => (
    <tr className="border-t border-border align-top">
      <th scope="row" className="w-[220px] px-4 py-3 text-left text-[11px] font-semibold tracking-[0.06em] text-text-2 uppercase">
        {label}
      </th>
      <td className="w-[300px] px-4 py-3 text-[12px] text-text-3">{hint}</td>
      <td className="px-4 py-3">{control}</td>
    </tr>
  );

  const k = e.fields;
  const h = e.hints;

  return (
    <>
      {header}
      {readOnly && <p className="mb-3 rounded-control bg-info-bg px-3 py-2 text-[12.5px] text-info">{e.builtinNotice}</p>}

      <section className="rounded-card border border-border bg-surface shadow-card">
        <table className="w-full border-collapse text-[13px]">
          <thead>
            <tr>
              <th className="px-4 py-2.5 text-left text-[11px] font-semibold tracking-[0.06em] text-text-2 uppercase">{e.columns.parameter}</th>
              <th className="px-4 py-2.5 text-left text-[11px] font-semibold tracking-[0.06em] text-text-2 uppercase">{e.columns.options}</th>
              <th className="px-4 py-2.5 text-left text-[11px] font-semibold tracking-[0.06em] text-text-2 uppercase">{e.columns.value}</th>
            </tr>
          </thead>
          <tbody>
            {row(k.code, h.code, (
              <>
                <input aria-label={k.code} className={mono} value={form.code} disabled={!creating}
                  onChange={(ev) => set('code', ev.target.value)} />
                {errorText('code')}
              </>
            ))}
            {row(k.shortName, h.shortName, (
              <input aria-label={k.shortName} className={input} value={form.shortName} disabled={readOnly}
                onChange={(ev) => set('shortName', ev.target.value)} />
            ))}
            {row(k.category, h.category, (
              <>
                <input aria-label={k.category} className={input} list="check-categories" value={form.category}
                  disabled={readOnly} onChange={(ev) => set('category', ev.target.value)} />
                <datalist id="check-categories">{categories.map((c) => <option key={c} value={c} />)}</datalist>
                {errorText('category')}
              </>
            ))}
            {row(k.severity, h.severity, (
              <div role="radiogroup" aria-label={k.severity} className="flex gap-2">
                {[0, 1, 2, 3, 4].map((s) => (
                  <button
                    key={s}
                    type="button"
                    role="radio"
                    aria-checked={form.severity === s}
                    disabled={readOnly}
                    onClick={() => set('severity', s)}
                    className={`flex items-center gap-1.5 rounded-full border px-2.5 py-1 text-[12px] font-semibold ${
                      form.severity === s ? 'border-text bg-text text-white' : 'border-border text-text-2'
                    } disabled:opacity-60`}
                  >
                    {s} {severityLabel(s, t)}
                  </button>
                ))}
              </div>
            ))}
            {row(k.kind, h.kind, (
              creating ? (
                <select aria-label={k.kind} className={mono} value={form.kind}
                  onChange={(ev) => set('kind', ev.target.value as CheckForm['kind'])}>
                  {EDITABLE_KINDS.map((kind) => <option key={kind} value={kind}>{e.kinds[kind]}</option>)}
                </select>
              ) : <code className="font-mono text-[12.5px] text-text-3">{form.kind}</code>
            ))}
            {row(k.scope, h.scope, <code className="font-mono text-[12.5px] text-text-3">{detail.data?.scope ?? 'CHANGELOG'}</code>)}

            {form.kind === 'REGEX' && (
              <>
                {row(k.pattern, h.pattern, (
                  <>
                    <input aria-label={k.pattern} className={mono} value={form.pattern} disabled={readOnly}
                      onChange={(ev) => set('pattern', ev.target.value)} />
                    {errorText('pattern')}
                  </>
                ))}
                {row(k.appliesTo, h.appliesTo, (
                  <select aria-label={k.appliesTo} className={mono} value={form.appliesTo} disabled={readOnly}
                    onChange={(ev) => set('appliesTo', ev.target.value)}>
                    {APPLIES_TO.map((a) => <option key={a} value={a}>{e.appliesTo[a]}</option>)}
                  </select>
                ))}
                {row(k.mustMatch, h.mustMatch, (
                  <div className="flex gap-2">
                    {[true, false].map((v) => (
                      <Chip key={String(v)} on={form.mustMatch === v} disabled={readOnly} onClick={() => set('mustMatch', v)}>
                        {e.booleans[String(v) as 'true' | 'false']}
                      </Chip>
                    ))}
                  </div>
                ))}
              </>
            )}

            {form.kind === 'SQL_PATTERN' && (
              <>
                {row(k.statementType, h.statementType, (
                  <select aria-label={k.statementType} className={mono} value={form.statementType} disabled={readOnly}
                    onChange={(ev) => set('statementType', ev.target.value)}>
                    {STATEMENT_TYPES.map((s) => <option key={s}>{s}</option>)}
                  </select>
                ))}
                {row(k.operator, h.operator, (
                  <select aria-label={k.operator} className={mono} value={form.operator} disabled={readOnly}
                    onChange={(ev) => set('operator', ev.target.value)}>
                    {OPERATORS.map((o) => <option key={o}>{o}</option>)}
                  </select>
                ))}
                {row(k.tables, h.tables, (
                  <>
                    <input aria-label={k.tables} className={mono} value={form.tables} disabled={readOnly}
                      onChange={(ev) => set('tables', ev.target.value)} />
                    {errorText('tables')}
                  </>
                ))}
                {row(k.columns, h.columns, (
                  <input aria-label={k.columns} className={mono} value={form.columns} disabled={readOnly}
                    onChange={(ev) => set('columns', ev.target.value)} />
                ))}
                {row(k.requiredConstraints, h.requiredConstraints, (
                  <div className="flex flex-wrap gap-2">
                    {CONSTRAINTS.map((c) => (
                      <Chip key={c} on={form.requiredConstraints.includes(c)} disabled={readOnly}
                        onClick={() => set('requiredConstraints', toggle(form.requiredConstraints, c))}>
                        {c}
                      </Chip>
                    ))}
                  </div>
                ))}
              </>
            )}

            {form.kind === 'SPEL' && row(k.expression, h.expression, (
              <>
                <textarea aria-label={k.expression} className={`${mono} min-h-20`} value={form.expression}
                  disabled={readOnly} onChange={(ev) => set('expression', ev.target.value)} />
                <p className="mt-1 text-[12px] text-warning">{e.spelWarning}</p>
                {errorText('expression')}
              </>
            ))}

            {form.kind === 'CHAINED' && row(k.members, h.members, (
              <>
                <div className="flex max-h-48 flex-wrap gap-2 overflow-auto">
                  {memberChoices.map((c) => (
                    <Chip key={c.code} on={form.members.includes(c.code)} disabled={readOnly}
                      onClick={() => set('members', toggle(form.members, c.code))}>
                      {c.code}
                    </Chip>
                  ))}
                </div>
                {errorText('members')}
              </>
            ))}

            {form.kind !== 'BUILTIN' && row(k.message, h.message, (
              <textarea aria-label={k.message} className={`${input} min-h-20`} value={form.message} disabled={readOnly}
                onChange={(ev) => set('message', ev.target.value)} />
            ))}
            {row(k.enabled, h.enabled, (
              <Chip on={form.enabled} disabled={readOnly} onClick={() => set('enabled', !form.enabled)}>
                {form.enabled ? t.governance.library.enabled : t.governance.library.disabled}
              </Chip>
            ))}
          </tbody>
        </table>

        <div className="flex items-center gap-2 border-t border-border px-4 py-3">
          {failure && <span role="alert" className="text-[12.5px] text-danger">{failure}</span>}
          {saved && <span aria-live="polite" className="text-[12.5px] text-success">{e.saved}</span>}
          <div className="ml-auto flex gap-2">
            {!creating && (
              <button type="button" onClick={() => setTesting(true)}
                className="rounded-control border border-border px-3 py-1.5 text-[12px] font-semibold hover:bg-surface-alt">
                {e.actions.test}
              </button>
            )}
            {readOnly && canEdit && (
              <button type="button" onClick={copy} className="rounded-control bg-primary px-3 py-1.5 text-[12px] font-semibold text-white">
                {e.actions.copy}
              </button>
            )}
            {!creating && !readOnly && canEdit && (
              <button type="button" onClick={remove}
                className="rounded-control border border-danger/40 px-3 py-1.5 text-[12px] font-semibold text-danger hover:bg-danger-bg">
                {e.actions.delete}
              </button>
            )}
            <button type="button" onClick={() => navigate('/govern/checks')}
              className="rounded-control border border-border px-3 py-1.5 text-[12px] font-semibold hover:bg-surface-alt">
              {e.actions.cancel}
            </button>
            {!readOnly && canEdit && (
              <button type="button" onClick={save} className="rounded-control bg-primary px-3 py-1.5 text-[12px] font-semibold text-white">
                {e.actions.save}
              </button>
            )}
          </div>
        </div>
      </section>

      {testing && id && (
        <TestDialog
          onClose={() => setTesting(false)}
          onRun={(request) => mutations.testCheck(id, {
            ...request,
            severity: form.severity,
            parameters: form.kind === 'BUILTIN' ? undefined : parametersOf(form),
          })}
        />
      )}
    </>
  );
}
