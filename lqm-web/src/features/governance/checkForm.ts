/**
 * The Policy Library form as data. No React: what the form MEANS (which fields a kind has,
 * what makes it valid, what body it sends) is testable without a DOM, and the same rules the
 * API enforces are checked here first so the person sees the problem next to the field.
 */
import type { CheckDefinition, CheckDetail, CheckKind } from '../../api/governance';

export const EDITABLE_KINDS: CheckKind[] = ['REGEX', 'SQL_PATTERN', 'CHAINED', 'SPEL'];
export const APPLIES_TO = ['SQL', 'COMMENT', 'ID'] as const;
export const OPERATORS = ['STARTS_WITH', 'CONTAINS', 'ENDS_WITH', 'REGEXP', 'ALL'] as const;
export const STATEMENT_TYPES = [
  'ALL', 'CREATE_TABLE', 'ALTER_TABLE', 'DROP', 'GRANT', 'INSERT', 'UPDATE', 'DELETE',
  'CREATE_INDEX', 'CREATE_VIEW', 'TRUNCATE',
] as const;
export const CONSTRAINTS = ['NOT_NULL', 'UNIQUE', 'PRIMARYKEY', 'FOREIGNKEY'] as const;

const CODE = /^[A-Za-z][A-Za-z0-9]{2,79}$/;

export type CheckForm = {
  code: string;
  shortName: string;
  category: string;
  kind: CheckKind;
  severity: number;
  message: string;
  enabled: boolean;
  // REGEX
  pattern: string;
  appliesTo: string;
  mustMatch: boolean;
  // SQL_PATTERN
  statementType: string;
  operator: string;
  tables: string;
  columns: string;
  requiredConstraints: string[];
  // SPEL
  expression: string;
  // CHAINED
  members: string[];
};

export type FormErrors = Partial<Record<'code' | 'category' | 'pattern' | 'tables' | 'expression' | 'members', string>>;

export function emptyForm(): CheckForm {
  return {
    code: '', shortName: '', category: '', kind: 'REGEX', severity: 2, message: '', enabled: true,
    pattern: '', appliesTo: 'SQL', mustMatch: false,
    statementType: 'ALL', operator: 'ALL', tables: '', columns: '', requiredConstraints: [],
    expression: '', members: [],
  };
}

function parseParameters(text: string | undefined): Record<string, unknown> {
  try {
    const value = text ? JSON.parse(text) : {};
    return value && typeof value === 'object' && !Array.isArray(value) ? (value as Record<string, unknown>) : {};
  } catch {
    return {};
  }
}

function listOf(value: unknown): string[] {
  if (Array.isArray(value)) {
    return value.map(String);
  }
  return typeof value === 'string' && value.trim() ? value.split(',').map((s) => s.trim()).filter(Boolean) : [];
}

/** The form for an existing check; parameters arrive as JSON text from the detail endpoint. */
export function fromCheck(check: CheckDetail & { members?: string[] }): CheckForm {
  const p = parseParameters(check.parameters);
  return {
    ...emptyForm(),
    code: check.code,
    shortName: check.shortName ?? '',
    category: check.category,
    kind: check.kind as CheckKind,
    severity: check.defaultSeverity,
    message: check.messageTemplate ?? '',
    enabled: check.enabled,
    pattern: String(p.pattern ?? ''),
    appliesTo: String(p.appliesTo ?? 'SQL'),
    mustMatch: p.mustMatch === true || p.mustMatch === 'true',
    statementType: String(p.statementType ?? 'ALL'),
    operator: String(p.operator ?? 'ALL'),
    tables: listOf(p.tables).join(', '),
    columns: listOf(p.columns).join(', '),
    requiredConstraints: listOf(p.requiredConstraints),
    expression: String(p.expression ?? ''),
    members: check.members ?? [],
  };
}

/** The parameters a kind actually uses; the others are not sent, so an old value cannot linger. */
export function parametersOf(form: CheckForm): Record<string, unknown> {
  switch (form.kind) {
    case 'REGEX':
      return { pattern: form.pattern, appliesTo: form.appliesTo, mustMatch: form.mustMatch };
    case 'SQL_PATTERN':
      return {
        statementType: form.statementType, operator: form.operator,
        tables: form.tables.trim(), columns: form.columns.trim(), requiredConstraints: form.requiredConstraints,
      };
    case 'SPEL':
      return { expression: form.expression };
    default:
      return {};
  }
}

export function toBody(form: CheckForm, creating: boolean): CheckDefinition {
  return {
    ...(creating ? { code: form.code.trim() } : { code: form.code }),
    shortName: form.shortName.trim() || undefined,
    category: form.category.trim(),
    kind: form.kind,
    defaultSeverity: form.severity,
    parameters: parametersOf(form),
    messageTemplate: form.message,
    members: form.kind === 'CHAINED' ? form.members : undefined,
    enabled: form.enabled,
  };
}

export function validate(form: CheckForm, creating: boolean): FormErrors {
  const errors: FormErrors = {};
  if (creating && !CODE.test(form.code.trim())) {
    errors.code = 'code';
  }
  if (!form.category.trim()) {
    errors.category = 'category';
  }
  if (form.kind === 'REGEX') {
    if (!form.pattern.trim()) {
      errors.pattern = 'required';
    } else if (!isValidRegex(form.pattern)) {
      errors.pattern = 'invalid';
    }
  }
  if (form.kind === 'SQL_PATTERN' && form.operator === 'REGEXP' && form.tables.split(',').some((t) => t.trim() && !isValidRegex(t.trim()))) {
    errors.tables = 'invalid';
  }
  if (form.kind === 'SPEL' && !form.expression.trim()) {
    errors.expression = 'required';
  }
  if (form.kind === 'CHAINED' && form.members.length === 0) {
    errors.members = 'required';
  }
  return errors;
}

/** Java and JavaScript regexes differ at the edges; this catches the common typos, the API is the judge. */
export function isValidRegex(pattern: string): boolean {
  try {
    new RegExp(pattern);
    return true;
  } catch {
    return false;
  }
}

export function toggle(list: string[], value: string): string[] {
  return list.includes(value) ? list.filter((item) => item !== value) : [...list, value];
}
