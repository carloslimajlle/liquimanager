import { describe, expect, it } from 'vitest';

import type { CheckDetail } from '../../api/governance';
import { emptyForm, fromCheck, isValidRegex, parametersOf, toBody, toggle, validate } from './checkForm';

const detail = (over: Partial<CheckDetail> & { members?: string[] }): CheckDetail & { members?: string[] } => ({
  id: 'x', code: 'MyCheck', shortName: 'My', category: 'Padroes', scope: 'CHANGELOG', kind: 'REGEX',
  defaultSeverity: 3, enabled: true, prebuilt: false, packages: 0, implemented: true, ...over,
});

describe('fromCheck', () => {
  it('reads the parameters the detail endpoint sends as JSON text', () => {
    const form = fromCheck(detail({
      parameters: '{"pattern":"nolock","appliesTo":"COMMENT","mustMatch":true}',
      messageTemplate: 'x',
    }));
    expect(form.pattern).toBe('nolock');
    expect(form.appliesTo).toBe('COMMENT');
    expect(form.mustMatch).toBe(true);
    expect(form.message).toBe('x');
    expect(form.severity).toBe(3);
  });

  it('reads table and constraint lists whether they are text or arrays', () => {
    const form = fromCheck(detail({
      kind: 'SQL_PATTERN',
      parameters: '{"tables":"tb_a, tb_b","columns":["cpf"],"requiredConstraints":["FOREIGNKEY"],"operator":"STARTS_WITH"}',
    }));
    expect(form.tables).toBe('tb_a, tb_b');
    expect(form.columns).toBe('cpf');
    expect(form.requiredConstraints).toEqual(['FOREIGNKEY']);
  });

  it('survives parameters that are not JSON', () => {
    expect(fromCheck(detail({ parameters: 'not json' })).pattern).toBe('');
  });
});

describe('toBody', () => {
  it('sends only the parameters of the chosen kind', () => {
    const form = { ...emptyForm(), code: ' Mine ', category: ' X ', kind: 'REGEX' as const, pattern: 'a', expression: 'stale' };
    const body = toBody(form, true);
    expect(body.code).toBe('Mine');
    expect(body.category).toBe('X');
    expect(body.parameters).toEqual({ pattern: 'a', appliesTo: 'SQL', mustMatch: false });
  });

  it('sends members only for a chain', () => {
    expect(toBody({ ...emptyForm(), kind: 'CHAINED', members: ['A'] }, false).members).toEqual(['A']);
    expect(toBody({ ...emptyForm(), kind: 'REGEX', members: ['A'] }, false).members).toBeUndefined();
  });

  it('keeps the code as it is when editing', () => {
    expect(toBody({ ...emptyForm(), code: 'Fixed' }, false).code).toBe('Fixed');
  });

  it('builds SQL_PATTERN parameters', () => {
    expect(parametersOf({ ...emptyForm(), kind: 'SQL_PATTERN', tables: ' tb_ ', requiredConstraints: ['UNIQUE'] }))
      .toEqual({ statementType: 'ALL', operator: 'ALL', tables: 'tb_', columns: '', requiredConstraints: ['UNIQUE'] });
  });
});

describe('validate', () => {
  const ok = { ...emptyForm(), code: 'Valid1', category: 'X', pattern: 'a' };

  it('accepts a complete REGEX check', () => {
    expect(validate(ok, true)).toEqual({});
  });

  it('checks the code only when creating', () => {
    expect(validate({ ...ok, code: 'bad code' }, true).code).toBeDefined();
    expect(validate({ ...ok, code: 'bad code' }, false).code).toBeUndefined();
  });

  it('wants a category', () => {
    expect(validate({ ...ok, category: ' ' }, true).category).toBeDefined();
  });

  it('flags an empty or invalid pattern next to the field', () => {
    expect(validate({ ...ok, pattern: '' }, true).pattern).toBe('required');
    expect(validate({ ...ok, pattern: '([oops' }, true).pattern).toBe('invalid');
  });

  it('checks table regexes only for the REGEXP operator', () => {
    const sql = { ...ok, kind: 'SQL_PATTERN' as const, tables: '([bad' };
    expect(validate({ ...sql, operator: 'STARTS_WITH' }, true).tables).toBeUndefined();
    expect(validate({ ...sql, operator: 'REGEXP' }, true).tables).toBe('invalid');
  });

  it('wants an expression for SPEL and members for a chain', () => {
    expect(validate({ ...ok, kind: 'SPEL', expression: '' }, true).expression).toBeDefined();
    expect(validate({ ...ok, kind: 'CHAINED', members: [] }, true).members).toBeDefined();
    expect(validate({ ...ok, kind: 'CHAINED', members: ['A'] }, true).members).toBeUndefined();
  });
});

describe('helpers', () => {
  it('toggle adds and removes', () => {
    expect(toggle(['A'], 'B')).toEqual(['A', 'B']);
    expect(toggle(['A', 'B'], 'A')).toEqual(['B']);
  });

  it('isValidRegex', () => {
    expect(isValidRegex('^tb_\\d+$')).toBe(true);
    expect(isValidRegex('([x')).toBe(false);
  });
});
