import { Link } from 'react-router-dom';

import { useAllChangelogs } from '../../api/developer';
import PageHeader from '../../components/PageHeader';
import { EmptyState, ErrorCard } from '../../components/States';
import { useMessages } from '../../i18n/useMessages';
import Topbar from '../../shell/Topbar';

/** Where the sidebar entry lands: every changelog, to open one in Developer Mode. */
export default function DeveloperIndex() {
  const t = useMessages();
  const d = t.developer;
  const changelogs = useAllChangelogs();

  return (
    <>
      <Topbar trail={[d.breadcrumbRoot, d.breadcrumbList]} />
      <PageHeader title={d.title} subtitle={d.index.subtitle} />
      <div className="mb-6 rounded-card border border-border bg-surface shadow-card">
        {changelogs.isError ? (
          <ErrorCard message={d.index.error} onRetry={() => changelogs.refetch()} />
        ) : changelogs.isLoading ? (
          <div aria-hidden className="m-4 h-24 animate-pulse rounded bg-border" />
        ) : (changelogs.data ?? []).length === 0 ? (
          <EmptyState title={d.index.empty} hint={d.index.emptyHint} />
        ) : (
          <ul>
            {(changelogs.data ?? []).map((changelog) => (
              <li key={changelog.id} className="border-t border-border first:border-t-0">
                <Link to={`/projects/${changelog.projectId}/changelogs/${changelog.id}`}
                  className="flex items-baseline gap-3 px-4 py-3 hover:bg-surface-alt">
                  <span className="font-semibold">{changelog.name}</span>
                  <span className="text-[12px] text-text-2">{changelog.projectName}</span>
                  <span className="ml-auto font-mono text-[12px] text-text-3">{changelog.path}</span>
                </Link>
              </li>
            ))}
          </ul>
        )}
      </div>
    </>
  );
}
