/** Pure rules of Developer Mode (screen 03): no React, so they are testable as they are. */
import type { DevChangeset, DevFinding, ProductionState } from '../../api/developer';

export type Language = 'xml' | 'yaml' | 'sql' | 'text';

export function languageOf(path: string): Language {
  const lower = path.toLowerCase();
  if (lower.endsWith('.xml')) return 'xml';
  if (lower.endsWith('.yaml') || lower.endsWith('.yml')) return 'yaml';
  if (lower.endsWith('.sql')) return 'sql';
  return 'text';
}

export type TokenKind = 'plain' | 'tag' | 'attr' | 'string' | 'comment' | 'keyword' | 'key' | 'number';
export type Token = { text: string; kind: TokenKind };

const SQL_KEYWORDS = new Set((
  'add alter and as by case column constraint create default delete drop else end exec foreign from grant index insert into is join key '
  + 'not null on or order primary references revoke rollback select set table then top truncate unique update values view when where with'
).split(' '));

/** Splits `text` by a global regex, keeping what lies between the matches as plain. */
function scan(text: string, pattern: RegExp, classify: (match: RegExpMatchArray) => TokenKind): Token[] {
  const tokens: Token[] = [];
  let last = 0;
  for (const match of text.matchAll(pattern)) {
    const start = match.index ?? 0;
    if (start > last) tokens.push({ text: text.slice(last, start), kind: 'plain' });
    tokens.push({ text: match[0], kind: classify(match) });
    last = start + match[0].length;
  }
  if (last < text.length) tokens.push({ text: text.slice(last), kind: 'plain' });
  return tokens;
}

/** Highlights a whole file. A multi-line XML comment is tracked across lines, which a per-line pass cannot do. */
export function highlight(text: string, language: Language): Token[][] {
  let inComment = false;
  return text.split('\n').map((line): Token[] => {
    if (language === 'xml') {
      if (inComment) {
        const end = line.indexOf('-->');
        if (end < 0) return [{ text: line, kind: 'comment' }];
        inComment = false;
        return [{ text: line.slice(0, end + 3), kind: 'comment' }, ...highlightXml(line.slice(end + 3))];
      }
      const open = line.indexOf('<!--');
      if (open >= 0 && line.indexOf('-->', open) < 0) {
        inComment = true;
        return [...highlightXml(line.slice(0, open)), { text: line.slice(open), kind: 'comment' }];
      }
      return highlightXml(line);
    }
    if (language === 'yaml') return highlightYaml(line);
    if (language === 'sql') return highlightSql(line);
    return [{ text: line, kind: 'plain' }];
  });
}

function highlightXml(line: string): Token[] {
  return scan(line, /<!--.*?-->|<\?.*?\?>|<\/?[\w:.-]+|\/?>|[\w:.-]+(?==)|"[^"]*"|'[^']*'/g, (m) => {
    const text = m[0];
    if (text.startsWith('<!--') || text.startsWith('<?')) return 'comment';
    if (text.startsWith('"') || text.startsWith("'")) return 'string';
    if (text.startsWith('<') || text.endsWith('>')) return 'tag';
    return 'attr';
  });
}

function highlightYaml(line: string): Token[] {
  const comment = line.search(/(^|\s)#/);
  const body = comment < 0 ? line : line.slice(0, comment);
  const tokens = scan(body, /^\s*(?:-\s+)*[\w.-]+(?=:)|"[^"]*"|'[^']*'/g, (m) => (/^["']/.test(m[0].trim()) ? 'string' : 'key'));
  if (comment >= 0) tokens.push({ text: line.slice(comment), kind: 'comment' });
  return tokens;
}

function highlightSql(line: string): Token[] {
  const comment = line.indexOf('--');
  const body = comment < 0 ? line : line.slice(0, comment);
  const tokens = scan(body, /'(?:[^']|'')*'|\b\d+(?:\.\d+)?\b|\b[A-Za-z_]+\b/g, (m) => {
    const text = m[0];
    if (text.startsWith("'")) return 'string';
    if (/^\d/.test(text)) return 'number';
    return SQL_KEYWORDS.has(text.toLowerCase()) ? 'keyword' : 'plain';
  });
  if (comment >= 0) tokens.push({ text: line.slice(comment), kind: 'comment' });
  return tokens;
}

// ------------------------------------------------------------------ changesets

export type Located = { line: number; id: string; author: string | null };

/** Where each changeset starts in a file (1-based line), for the gutter markers and for the PROBLEMS tab. */
export function locateChangesets(text: string, language: Language): Located[] {
  const lines = text.split('\n');
  const found: Located[] = [];
  lines.forEach((line, index) => {
    if (language === 'xml') {
      if (!/<changeSet\b/i.test(line)) return;
      // The attributes may continue on the next lines; look a few lines ahead.
      const head = lines.slice(index, index + 4).join(' ');
      const id = /\bid\s*=\s*"([^"]*)"/.exec(head)?.[1];
      if (id) found.push({ line: index + 1, id, author: /\bauthor\s*=\s*"([^"]*)"/.exec(head)?.[1] ?? null });
    } else if (language === 'sql') {
      const m = /^\s*--\s*changeset\s+([^:\s]+):(\S+)/i.exec(line);
      if (m) found.push({ line: index + 1, id: m[2], author: m[1] });
    } else if (language === 'yaml') {
      if (!/^\s*-\s*changeSet\s*:/i.test(line)) return;
      const head = lines.slice(index, index + 6).join('\n');
      const id = /\bid\s*:\s*["']?([^\s"']+)/.exec(head)?.[1];
      if (id) found.push({ line: index + 1, id, author: /\bauthor\s*:\s*["']?([^\s"']+)/.exec(head)?.[1] ?? null });
    }
  });
  return found;
}

export type Marker = { line: number; status: ProductionState; changeset: string };

/** Paths as the repository and as Liquibase see them differ by a prefix: they match when one ends with the other. */
export function samePath(a: string, b: string): boolean {
  const left = a.replace(/\\/g, '/').replace(/^\/+/, '');
  const right = b.replace(/\\/g, '/').replace(/^\/+/, '');
  return left === right || left.endsWith('/' + right) || right.endsWith('/' + left);
}

export function markersFor(located: Located[], changesets: DevChangeset[], filePath: string): Marker[] {
  const markers: Marker[] = [];
  for (const spot of located) {
    const sameChangeset = (cs: DevChangeset) => cs.id === spot.id && (spot.author === null || cs.author === spot.author);
    const match = changesets.find((cs) => sameChangeset(cs) && samePath(cs.filePath, filePath)) ?? changesets.find(sameChangeset);
    if (match) markers.push({ line: spot.line, status: match.production, changeset: match.id });
  }
  return markers;
}

export type Problem = { check: string; severity: number; message: string; line: number | null; changeset: string | null };

/** The findings that belong to this file; the line comes from the finding or, failing that, from its changeset. */
export function problemsFor(findings: DevFinding[], filePath: string, located: Located[]): Problem[] {
  return findings
    .filter((f) => f.result !== 'PASSED' && (!f.filePath || samePath(f.filePath, filePath)))
    .map((f) => ({
      check: f.check,
      severity: f.severity,
      message: f.message,
      line: f.line ?? located.find((spot) => spot.id === f.changeset)?.line ?? null,
      changeset: f.changeset ?? null,
    }));
}

// -------------------------------------------------------------------- commands

export type CommandKind = 'update' | 'rollback' | 'checks' | 'flow';

/** The CLI line a menu item stands for. The JDBC URL is a placeholder: no credential or address is guessed here. */
export function commandFor(kind: CommandKind, project: string, changelogPath: string, environment = 'DEV'): string {
  const common = `--project=${project} --env=${environment} --changelog-file=${changelogPath} --url=<jdbc-url>`;
  switch (kind) {
    case 'update': return `lqm update ${common}`;
    case 'rollback': return `lqm rollback-one-changeset ${common} --changeset-id=<id-do-changeset>`;
    case 'checks': return `lqm checks run ${common}`;
    case 'flow': return `lqm flow --flow-file=lqm.flow.yaml ${common}`;
  }
}
