import { Navigate, Route, Routes } from 'react-router-dom';

import PageHeader from './components/PageHeader';
import Dashboard from './features/dashboard/Dashboard';
import Enforcement from './features/governance/Enforcement';
import Drift from './features/drift/Drift';
import Notifications from './features/notifications/Notifications';
import Access from './features/access/Access';
import Audit from './features/admin/Audit';
import Connections from './features/admin/Connections';
import Environments from './features/admin/Environments';
import Pipelines from './features/admin/Pipelines';
import ProjectDetail from './features/admin/ProjectDetail';
import Projects from './features/admin/Projects';
import Settings from './features/admin/Settings';
import Tokens from './features/admin/Tokens';
import CheckEditor from './features/governance/CheckEditor';
import { PackageEditor, PackagesList } from './features/governance/Packages';
import PolicyLibrary from './features/governance/PolicyLibrary';
import Developer from './features/developer/Developer';
import DeveloperIndex from './features/developer/DeveloperIndex';
import DeploymentMatrix from './features/matrix/DeploymentMatrix';
import PipelinesIndex from './features/matrix/PipelinesIndex';
import AllOperations from './features/operations/AllOperations';
import OperationDetail from './features/operations/OperationDetail';
import { useMessages } from './i18n/useMessages';
import AccessDeniedBanner from './shell/AccessDeniedBanner';
import NoAccess from './features/welcome/NoAccess';
import { useMe } from './api/admin';
import { hasNoAccess } from './auth/permissions';
import BreakGlassBanner from './shell/BreakGlassBanner';
import PendingBanner from './shell/PendingBanner';
import Sidebar from './shell/Sidebar';
import ChangesetDetail from './features/projects/ChangesetDetail';
import ProjectHub from './features/projects/ProjectHub';
import ProjectsIndex from './features/projects/ProjectsIndex';
import Topbar from './shell/Topbar';

/**
 * Application shell. Each page renders its own Topbar, because the breadcrumb belongs
 * to the page (the operation detail names the operation it shows).
 *
 * The routes below the implemented screens are placeholders until their phase.
 */
function Placeholder({ title, subtitle }: { title: string; subtitle?: string }) {
  const t = useMessages();
  return (
    <>
      <Topbar trail={[t.app.name, title]} />
      <PageHeader title={title} subtitle={subtitle} />
    </>
  );
}

export default function App() {
  const t = useMessages();
  const me = useMe();
  // Signed in, but nobody has granted anything yet: say so, instead of screens that stay empty.
  if (me.data && hasNoAccess(me.data)) {
    return <NoAccess me={me.data} />;
  }

  const placeholders: Array<[string, string, string]> = [
    ['/help', t.screens.help, t.screens.helpSub],
  ];

  return (
    <div className="grid min-h-full grid-cols-[auto_1fr]">
      <Sidebar />
      <main className="min-w-0 px-7 pb-7">
        <BreakGlassBanner />
        <AccessDeniedBanner />
        <PendingBanner />
        <Routes>
          <Route path="/" element={<Dashboard />} />
          <Route path="/operations" element={<AllOperations />} />
          <Route path="/projects" element={<ProjectsIndex />} />
          <Route path="/projects/:projectId" element={<ProjectHub />} />
          <Route path="/changesets/:id" element={<ChangesetDetail />} />
          <Route path="/operations/:id" element={<OperationDetail />} />
          <Route path="/govern" element={<Navigate to="/govern/enforcement" replace />} />
          <Route path="/govern/enforcement" element={<Enforcement />} />
          <Route path="/govern/checks" element={<PolicyLibrary />} />
          <Route path="/govern/checks/new" element={<CheckEditor />} />
          <Route path="/govern/checks/:id" element={<CheckEditor />} />
          <Route path="/govern/packages" element={<PackagesList />} />
          <Route path="/govern/packages/new" element={<PackageEditor />} />
          <Route path="/govern/packages/:id" element={<PackageEditor />} />
          <Route path="/admin" element={<Navigate to="/admin/access" replace />} />
          <Route path="/admin/access" element={<Access />} />
          <Route path="/admin/notifications" element={<Notifications />} />
          <Route path="/admin/projects" element={<Projects />} />
          <Route path="/admin/projects/:projectId" element={<ProjectDetail />} />
          <Route path="/admin/connections" element={<Connections />} />
          <Route path="/admin/environments" element={<Environments />} />
          <Route path="/admin/pipelines" element={<Pipelines />} />
          <Route path="/admin/tokens" element={<Tokens />} />
          <Route path="/admin/audit" element={<Audit />} />
          <Route path="/settings" element={<Settings />} />
          <Route path="/drift" element={<Drift />} />
          <Route path="/developer" element={<DeveloperIndex />} />
          <Route path="/projects/:projectId/changelogs/:changelogId" element={<Developer />} />
          <Route path="/pipelines" element={<PipelinesIndex />} />
          <Route path="/pipelines/:id/matrix" element={<DeploymentMatrix />} />
          <Route path="/pipelines/:id/timeline" element={<DeploymentMatrix mode="timeline" />} />
          {placeholders.map(([path, title, subtitle]) => (
            <Route key={path} path={path} element={<Placeholder title={title} subtitle={subtitle} />} />
          ))}
          <Route path="*" element={<Placeholder title={t.screens.notFound} />} />
        </Routes>
      </main>
    </div>
  );
}
