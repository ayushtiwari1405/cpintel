import { Routes, Route, Navigate } from 'react-router-dom'
import { useAuth } from '@/contexts/AuthContext'
import { isAdmin } from '@/utils/roles'
import { lazy, Suspense } from 'react'
import { LoadingScreen } from '@/components/common/LoadingScreen'
import { ErrorBoundary } from '@/components/common/ErrorBoundary'

const LoginPage         = lazy(() => import('@/pages/LoginPage'))
const DashboardPage     = lazy(() => import('@/pages/DashboardPage'))
const AnalyticsPage     = lazy(() => import('@/pages/AnalyticsPage'))
const RecommendPage     = lazy(() => import('@/pages/RecommendationsPage'))
const RoadmapPage       = lazy(() => import('@/pages/RoadmapPage'))
const PlatformsPage     = lazy(() => import('@/pages/PlatformsPage'))
const PracticePage      = lazy(() => import('@/pages/PracticePage'))
const CompetePage       = lazy(() => import('@/pages/CompetePage'))
const ProfilePage       = lazy(() => import('@/pages/ProfilePage'))
const AppLayout         = lazy(() => import('@/layouts/AppLayout'))
const AdminOverview     = lazy(() => import('@/pages/admin/AdminOverviewPage'))
const AdminUsers        = lazy(() => import('@/pages/admin/AdminUsersPage'))
const AdminAudit        = lazy(() => import('@/pages/admin/AdminAuditPage'))
const AdminFiles        = lazy(() => import('@/pages/admin/AdminContestFilesPage'))
const AdminGroups       = lazy(() => import('@/pages/admin/AdminGroupsPage'))
const AdminGroupDetail  = lazy(() => import('@/pages/admin/AdminGroupDetailPage'))
const AdminGroupContest = lazy(() => import('@/pages/admin/AdminGroupContestPage'))
const GroupsPage        = lazy(() => import('@/pages/GroupsPage'))

function ProtectedRoute({ children }: { children: React.ReactNode }) {
  const { isAuthenticated } = useAuth()
  return isAuthenticated ? <>{children}</> : <Navigate to="/login" replace />
}

function GuestRoute({ children }: { children: React.ReactNode }) {
  const { isAuthenticated } = useAuth()
  return !isAuthenticated ? <>{children}</> : <Navigate to="/dashboard" replace />
}

/**
 * Keeps the console out of an ordinary user's way.
 *
 * This is presentation, not protection — every /api/admin route is checked against the role in
 * the token on the server, so a user who edits their stored profile to say ADMIN gets these
 * screens and nothing to put in them. Sending them to the dashboard rather than showing a
 * refusal keeps the console invisible to people who have no business knowing it is there.
 */
function AdminRoute({ children }: { children: React.ReactNode }) {
  const { user } = useAuth()
  return isAdmin(user) ? <>{children}</> : <Navigate to="/dashboard" replace />
}

export function AppRoutes() {
  return (
    <ErrorBoundary scope="this page">
      <Suspense fallback={<LoadingScreen />}>
      <Routes>
        <Route path="/" element={<Navigate to="/dashboard" replace />} />

        <Route path="/login" element={
          <GuestRoute><LoginPage /></GuestRoute>
        } />

        <Route element={
          <ProtectedRoute><AppLayout /></ProtectedRoute>
        }>
          <Route path="/dashboard"       element={<DashboardPage />} />
          <Route path="/analytics"       element={<AnalyticsPage />} />
          <Route path="/recommendations" element={<RecommendPage />} />
          <Route path="/roadmap"         element={<RoadmapPage />} />
          <Route path="/practice"        element={
            <ErrorBoundary scope="the practice workspace"><PracticePage /></ErrorBoundary>} />
          <Route path="/compete"         element={
            <ErrorBoundary scope="the contest workspace"><CompetePage /></ErrorBoundary>} />
          <Route path="/platforms"       element={<PlatformsPage />} />
          <Route path="/profile"         element={<ProfilePage />} />
          <Route path="/groups"          element={<GroupsPage />} />

          <Route path="/admin"               element={<AdminRoute><AdminOverview /></AdminRoute>} />
          <Route path="/admin/users"         element={<AdminRoute><AdminUsers /></AdminRoute>} />
          <Route path="/admin/audit"         element={<AdminRoute><AdminAudit /></AdminRoute>} />
          <Route path="/admin/contest-files" element={<AdminRoute><AdminFiles /></AdminRoute>} />
          <Route path="/admin/groups"        element={<AdminRoute><AdminGroups /></AdminRoute>} />
          <Route path="/admin/groups/:groupId"
            element={<AdminRoute><AdminGroupDetail /></AdminRoute>} />
          <Route path="/admin/groups/contests/:contestId"
            element={<AdminRoute><AdminGroupContest /></AdminRoute>} />
        </Route>

        <Route path="*" element={<Navigate to="/dashboard" replace />} />
      </Routes>
      </Suspense>
    </ErrorBoundary>
  )
}
