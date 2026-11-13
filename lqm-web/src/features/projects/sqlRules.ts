import type { Params } from '../../api/client';

/** What the SQL is generated for: the changesets themselves, a release, a Liquibase tag, or what has not reached an environment. */
export type SqlMode = 'ids' | 'release' | 'tag' | 'notIn';

export const SQL_MODES: SqlMode[] = ['notIn', 'release', 'tag', 'ids'];

export type SqlDialect = '' | 'mssql' | 'postgresql' | 'oracle' | 'mysql';

export const SQL_DIALECTS: SqlDialect[] = ['', 'mssql', 'postgresql', 'oracle', 'mysql'];

/** Warning codes the API gives for a changeset whose SQL was not generated. */
export const SQL_WARNINGS = ['NO_REPOSITORY', 'NOT_CONFIGURED', 'NOT_FOUND', 'UNREACHABLE', 'NOT_IN_FILE', 'NO_SQL', 'UNPARSEABLE'] as const;

/** The query of the API for a mode and its value; the dialect is left out when it is automatic. */
export function sqlParams(mode: SqlMode, value: string, dialect: SqlDialect): Params {
  const trimmed = value.trim();
  const params: Params = mode === 'ids' ? { ids: trimmed } : { [mode]: trimmed };
  if (dialect) {
    params.dialect = dialect;
  }
  return params;
}

/** Whether the value is enough to ask: every mode needs one. */
export function canGenerate(mode: SqlMode, value: string): boolean {
  return value.trim().length > 0 && (mode !== 'release' && mode !== 'tag' ? true : /^[0-9A-Za-z._-]+$/.test(value.trim()));
}

/** A file name for the script, safe on every system: "payments-release-2.1.0.sql". */
export function sqlFileName(project: string, mode: SqlMode, value: string): string {
  const clean = (text: string) => text.trim().replace(/[^0-9A-Za-z._-]+/g, '-').replace(/^-+|-+$/g, '');
  const parts = [project, mode === 'ids' ? 'changeset' : mode === 'notIn' ? `pendentes-${value}` : value].map(clean).filter(Boolean);
  return `${parts.join('-') || 'changeset'}.sql`;
}
