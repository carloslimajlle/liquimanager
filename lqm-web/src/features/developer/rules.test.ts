import { describe, expect, it } from 'vitest';

import type { DevChangeset } from '../../api/developer';
import { commandFor, highlight, languageOf, locateChangesets, markersFor, problemsFor, samePath } from './rules';

const cs = (id: string, production: DevChangeset['production'], filePath = 'db/changelog/a.xml', author = 'ana'): DevChangeset =>
  ({ id, author, filePath, hasRollback: true, production });

describe('languageOf', () => {
  it('reads the language from the extension', () => {
    expect(languageOf('db/a.xml')).toBe('xml');
    expect(languageOf('lqm.flow.yaml')).toBe('yaml');
    expect(languageOf('x.YML')).toBe('yaml');
    expect(languageOf('V1__a.sql')).toBe('sql');
    expect(languageOf('README')).toBe('text');
  });
});

describe('locateChangesets', () => {
  it('finds an XML changeset, even when its attributes continue on the next line', () => {
    const text = '<databaseChangeLog>\n  <changeSet id="SUP-1" author="ana">\n  </changeSet>\n  <changeSet\n      author="bia"\n      id="SUP-2">\n</databaseChangeLog>';

    expect(locateChangesets(text, 'xml')).toEqual([
      { line: 2, id: 'SUP-1', author: 'ana' },
      { line: 4, id: 'SUP-2', author: 'bia' },
    ]);
  });

  it('finds formatted SQL and YAML changesets', () => {
    expect(locateChangesets('--liquibase formatted sql\n--changeset ana:SUP-1\nselect 1;', 'sql'))
      .toEqual([{ line: 2, id: 'SUP-1', author: 'ana' }]);
    expect(locateChangesets('databaseChangeLog:\n  - changeSet:\n      id: SUP-3\n      author: bia', 'yaml'))
      .toEqual([{ line: 2, id: 'SUP-3', author: 'bia' }]);
  });

  it('is not fooled by lines that only mention a changeset', () => {
    expect(locateChangesets('-- a changeset is mentioned here', 'sql')).toEqual([]);
    expect(locateChangesets('<rollback/>', 'xml')).toEqual([]);
  });
});

describe('markersFor', () => {
  it('puts the production state of each changeset on its line', () => {
    const located = [{ line: 3, id: 'SUP-1', author: 'ana' }, { line: 9, id: 'SUP-2', author: 'ana' }];

    expect(markersFor(located, [cs('SUP-1', 'EXECUTED'), cs('SUP-2', 'FAILED')], 'db/changelog/a.xml')).toEqual([
      { line: 3, status: 'EXECUTED', changeset: 'SUP-1' },
      { line: 9, status: 'FAILED', changeset: 'SUP-2' },
    ]);
  });

  it('matches a repository path to the shorter path Liquibase reported', () => {
    const located = [{ line: 1, id: 'SUP-1', author: null }];

    expect(markersFor(located, [cs('SUP-1', 'PENDING', 'db/changelog/a.xml')], 'src/main/resources/db/changelog/a.xml'))
      .toHaveLength(1);
  });

  it('leaves out a changeset the platform has never seen', () => {
    expect(markersFor([{ line: 1, id: 'NEW', author: 'ana' }], [cs('SUP-1', 'EXECUTED')], 'a.xml')).toEqual([]);
  });
});

describe('samePath', () => {
  it('compares by suffix, never by substring', () => {
    expect(samePath('a/b/c.xml', 'b/c.xml')).toBe(true);
    expect(samePath('a/xb/c.xml', 'b/c.xml')).toBe(false);
    expect(samePath('a\\b.xml', 'a/b.xml')).toBe(true);
  });
});

describe('problemsFor', () => {
  const located = [{ line: 12, id: 'SUP-1', author: 'ana' }];
  const finding = (over: object) => ({ check: 'DropTableWarn', severity: 3, result: 'BLOCKED', message: 'm', ...over });

  it('takes the line from the finding, or from its changeset', () => {
    const problems = problemsFor([finding({ changeset: 'SUP-1', filePath: 'a.xml' }), finding({ line: 5, filePath: 'a.xml' })], 'a.xml', located);

    expect(problems.map((p) => p.line)).toEqual([12, 5]);
  });

  it('keeps only this file, and only what is not a pass', () => {
    const problems = problemsFor(
      [finding({ filePath: 'b.xml' }), finding({ result: 'PASSED', filePath: 'a.xml' }), finding({ filePath: 'a.xml' })],
      'a.xml', located);

    expect(problems).toHaveLength(1);
  });
});

describe('highlight', () => {
  it('colours XML tags, attributes and strings', () => {
    const [line] = highlight('<changeSet id="x">', 'xml');

    expect(line.filter((t) => t.kind !== 'plain').map((t) => [t.text, t.kind])).toEqual([
      ['<changeSet', 'tag'], ['id', 'attr'], ['"x"', 'string'], ['>', 'tag'],
    ]);
  });

  it('follows a comment across lines', () => {
    const lines = highlight('<a>\n<!-- one\ntwo -->\n<b/>', 'xml');

    expect(lines[1][0].kind).toBe('comment');
    expect(lines[2][0]).toEqual({ text: 'two -->', kind: 'comment' });
    expect(lines[3][0].kind).toBe('tag');
  });

  it('marks SQL keywords but not words that only contain one', () => {
    const [line] = highlight('create table tablet', 'sql');

    expect(line.filter((t) => t.kind === 'keyword').map((t) => t.text)).toEqual(['create', 'table']);
  });

  it('keeps every character: joining the tokens gives the line back', () => {
    const samples = [['  <column name="id" type="BIGINT"/>', 'xml'], ['    id: SUP-1 # x', 'yaml'], ["select 'a''b', 1 -- c", 'sql']] as const;
    for (const [text, language] of samples) {
      expect(highlight(text, language)[0].map((t) => t.text).join('')).toBe(text);
    }
  });
});

describe('commandFor', () => {
  it('writes the CLI line with a placeholder where only the user knows the value', () => {
    expect(commandFor('update', 'gate', 'db/master.xml')).toBe('lqm update --project=gate --env=DEV --changelog-file=db/master.xml --url=<jdbc-url>');
    expect(commandFor('rollback', 'gate', 'db/master.xml')).toContain('--changeset-id=<id-do-changeset>');
    expect(commandFor('flow', 'gate', 'db/master.xml', 'HML')).toContain('lqm flow --flow-file=lqm.flow.yaml');
  });
});
