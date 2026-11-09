import { Link, useNavigate } from 'react-router-dom';

import { useServiceTokens } from '../../api/admin';
import type { Project, ServiceToken } from '../../api/admin';
import { useOperations } from '../../api/operations';
import type { OperationSummary } from '../../api/operations';
import CodeBlock from '../../components/CodeBlock';
import DataTable from '../../components/DataTable';
import type { Column } from '../../components/DataTable';
import EnvBadge from '../../components/EnvBadge';
import StatusBadge from '../../components/StatusBadge';
import { EmptyState } from '../../components/States';
import { useLocale, useMessages } from '../../i18n/useMessages';
import { formatDateTime } from '../../lib/format';
import { isFailure, statusTone } from '../operations/rules';

/** The application.yml of a service with the starter, and the variables of a GitLab pipeline with the CLI (docs/07 § 1 and § 3). */
export function collectorSnippets(slug: string): { starter: string; cli: string } {
  const origin = typeof window === 'undefined' ? 'https://lqm.<dominio>' : window.location.origin;
  return {
    starter: [
      'lqm:',
      `  url: ${origin}/api/v1`,
      '  token: ${LQM_TOKEN}          # token de serviço com escopo ingest, de um secret',
      `  project: ${slug}`,
      '  environment: ${LQM_ENV:${spring.profiles.active}}',
      '  enforce: false               # true: avalia os checks e aborta o startup numa violação bloqueante',
    ].join(NEWLINE),
    cli: [
      '.lqm:',
      '  variables:',
      `    LQM_URL: ${origin}/api/v1`,
      `    LQM_PROJECT: ${slug}`,
      '    # LQM_TOKEN: variável mascarada do GitLab, com um token de serviço de escopo ingest',
    ].join(NEWLINE),
  };
}

const NEWLINE = String.fromCharCode(10);

/** How a service reports to LiquiManager, ready to copy, and the tokens that exist for this project. */
export function CollectorTab({ project }: { project: Project }) {
  const t = useMessages();
  const c = t.admin.projects.detail.collector;
  const locale = useLocale();
  const tokens = useServiceTokens();
  const snippets = collectorSnippets(project.slug);
  const mine = (tokens.data ?? []).filter((token) => token.projectIds.length === 0 || token.projectIds.includes(project.id));
  const state = (token: ServiceToken) => (token.revokedAt ? c.revoked : token.expiresAt && new Date(token.expiresAt) < new Date() ? c.expired : c.active);

  const columns: Column<ServiceToken>[] = [
    { key: 'name', header: c.columns.name, cell: (r) => <span className="font-semibold">{r.name}</span> },
    { key: 'prefix', header: c.columns.prefix, cell: (r) => <code className="font-mono text-[12px]">lqm_{r.prefix}…</code> },
    { key: 'scopes', header: c.columns.scopes, cell: (r) => <code className="font-mono text-[12px]">{r.scopes.join(', ')}</code> },
    { key: 'expires', header: c.columns.expires, cell: (r) => (r.expiresAt ? formatDateTime(r.expiresAt, locale) : c.never) },
    { key: 'used', header: c.columns.lastUsed, cell: (r) => (r.lastUsedAt ? formatDateTime(r.lastUsedAt, locale) : c.never) },
    { key: 'state', header: c.columns.state, cell: (r) => <StatusBadge tone={state(r) === c.active ? 'success' : 'neutral'}>{state(r)}</StatusBadge> },
  ];

  return (
    <section className="flex flex-col gap-4">
      <p className="text-[12.5px] text-text-2">{c.intro}</p>
      <div>
        <h3 className="mb-1.5 text-[13px] font-bold">{c.starter}</h3>
        <CodeBlock copyable>{snippets.starter}</CodeBlock>
      </div>
      <div>
        <h3 className="mb-1.5 text-[13px] font-bold">{c.cli}</h3>
        <CodeBlock copyable>{snippets.cli}</CodeBlock>
      </div>
      <div>
        <div className="mb-1.5 flex items-center">
          <h3 className="text-[13px] font-bold">{c.tokens}</h3>
          <Link to="/admin/tokens" className="ml-auto text-[12px] font-semibold text-primary hover:underline">{c.createToken} ↗</Link>
        </div>
        {mine.length === 0 && !tokens.isLoading ? <EmptyState title={c.noTokens} /> : (
          <DataTable columns={columns} rows={mine} rowKey={(r) => r.id} loading={tokens.isLoading} caption={c.tokens} />
        )}
      </div>
    </section>
  );
}

/** The latest operations of the project; the full list is one click away, already filtered. */
export function ActivityTab({ project }: { project: Project }) {
  const t = useMessages();
  const a = t.admin.projects.detail.activity;
  const locale = useLocale();
  const navigate = useNavigate();
  const operations = useOperations({ project: project.slug, period: 'all' }, 0, 10, 'startedAt,desc');
  const rows = operations.data?.content ?? [];

  const columns: Column<OperationSummary>[] = [
    { key: 'command', header: a.columns.command, cell: (r) => <code className="font-mono text-[12px] text-primary">{r.command}</code> },
    { key: 'status', header: a.columns.status, cell: (r) => (
      <StatusBadge tone={statusTone(r.status)}>{`${isFailure(r.status) ? '✕ ' : ''}${t.operations.status[r.status] ?? r.status}`}</StatusBadge>
    ) },
    { key: 'date', header: a.columns.date, cell: (r) => <span className="whitespace-nowrap">{formatDateTime(r.startedAt, locale)}</span> },
    { key: 'connection', header: a.columns.connection, cell: (r) => (
      <span className="flex items-center gap-2"><code className="font-mono text-[12px]">{r.connection}</code><EnvBadge code={r.environment} /></span>
    ) },
    { key: 'by', header: a.columns.by, cell: (r) => r.executedBy },
  ];

  return (
    <section>
      {operations.data && rows.length === 0 ? <EmptyState title={a.empty} /> : (
        <DataTable columns={columns} rows={rows} rowKey={(r) => r.id} loading={operations.isLoading}
          onRowClick={(r) => navigate(`/operations/${r.id}`)} rowLabel={(r) => r.command} caption={a.title} />
      )}
      <Link to={`/operations?project=${project.slug}`} className="mt-2 inline-block text-[12px] font-semibold text-primary hover:underline">{a.viewAll} ↗</Link>
    </section>
  );
}
