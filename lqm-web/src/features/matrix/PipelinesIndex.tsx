import { Navigate } from 'react-router-dom';

import { usePipelines } from '../../api/matrix';
import PageHeader from '../../components/PageHeader';
import { EmptyState, ErrorCard } from '../../components/States';
import { useMessages } from '../../i18n/useMessages';
import Topbar from '../../shell/Topbar';
import { crumb } from '../../shell/crumbs';

/**
 * `/pipelines` is where the sidebar's "Automate" entry lands. There is no pipeline list
 * screen yet, so it opens the first pipeline's matrix — the thing a person came for.
 */
export default function PipelinesIndex() {
  const t = useMessages();
  const pipelines = usePipelines();

  if (pipelines.data && pipelines.data.length > 0) {
    return <Navigate to={`/pipelines/${pipelines.data[0].id}/matrix`} replace />;
  }

  return (
    <>
      <Topbar trail={[crumb(t.matrix.breadcrumbRoot, '/pipelines'), t.matrix.title]} />
      <PageHeader title={t.matrix.title} subtitle={t.matrix.subtitle} />
      <div className="rounded-card border border-border bg-surface shadow-card">
        {pipelines.isError ? (
          <ErrorCard message={t.matrix.error} onRetry={() => pipelines.refetch()} />
        ) : pipelines.isLoading ? (
          <p className="px-6 py-14 text-center text-text-2">{t.common.loading}</p>
        ) : (
          <EmptyState title={t.matrix.empty.noPipelines} hint={t.matrix.empty.noPipelinesHint} />
        )}
      </div>
    </>
  );
}
