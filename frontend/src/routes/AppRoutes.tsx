import { Routes, Route, Navigate, useLocation } from 'react-router-dom'
import { useAuthStore } from '@/store/authStore'
import { useAuth } from '@/contexts/AuthContext'
import { isAdmin } from '@/utils/roles'
import { lazy, Suspense } from 'react'
import { LoadingScreen } from '@/components/common/LoadingScreen'
import { ErrorBoundary } from '@/components/common/ErrorBoundary'

const LoginPage         = lazy(() => import('@/pages/LoginPage'))
const ForgotPasswordPage = lazy(() => import('@/pages/ForgotPasswordPage'))
const ResetPasswordPage  = lazy(() => import('@/pages/ResetPasswordPage'))
const DashboardPage     = lazy(() => import('@/pages/DashboardPage'))
const AnalyticsPage     = lazy(() => import('@/pages/AnalyticsPage'))
const RoadmapPage       = lazy(() => import('@/pages/RoadmapPage'))
const GauntletPage      = lazy(() => import('@/pages/GauntletPage'))
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
const AdminExams        = lazy(() => import('@/pages/admin/AdminExamsPage'))
const AdminExamDetail   = lazy(() => import('@/pages/admin/AdminExamDetailPage'))
const GroupsPage        = lazy(() => import('@/pages/GroupsPage'))

/**
 * Signed in — and in the right mode for where they are going.
 *
 * <p>An examination session (signed in with the password on a slip) lives at /exam and nowhere
 * else; an ordinary one never sees /exam. The server enforces the same split on every request,
 * so this only keeps the screens from offering what would be refused.
 */
function ProtectedRoute({ children }: { children: React.ReactNode }) {
  const { isAuthenticated } = useAuth()
  const mode = useAuthStore(s => s.mode)
  const { pathname } = useLocation()
  if (!isAuthenticated) return <Navigate to="/login" replace />
  if (mode === 'EXAM' && pathname !== '/exam') return <Navigate to="/exam" replace />
  if (mode !== 'EXAM' && pathname === '/exam') return <Navigate to="/compete" replace />
  return <>{children}</>
}

/** The paper an examination session was signed in for, and nothing else. */
function ExamModePage() {
  const examId = useAuthStore(s => s.examId)
  return examId == null ? <Navigate to="/login" replace /> : <CompetePage examOnly={examId} />
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

        {/*
          Both password screens are guest routes for the same reason the sign-in page is: the
          person opening them is, by definition, the one who cannot get in. Somebody already
          signed in who wants a new password has a better path — their profile, where the
          current password is asked for — so they are sent to the dashboard rather than shown
          a form that would sign them out of the session they are using.

          The reset link was dead until now: the sign-in page has offered "Forgot password?"
          since it was written, and there was no route behind it.
        */}
        <Route path="/forgot-password" element={
          <GuestRoute><ForgotPasswordPage /></GuestRoute>
        } />
        <Route path="/reset-password" element={
          <GuestRoute><ResetPasswordPage /></GuestRoute>
        } />

        <Route element={
          <ProtectedRoute><AppLayout /></ProtectedRoute>
        }>
          <Route path="/dashboard"       element={<DashboardPage />} />
          <Route path="/analytics"       element={<AnalyticsPage />} />
          {/* Hidden for now (see the sidebar); a bookmark lands on the roadmap instead. */}
          <Route path="/recommendations" element={<Navigate to="/roadmap" replace />} />
          <Route path="/roadmap"         element={<RoadmapPage />} />
          <Route path="/roadmap/gauntlet" element={<GauntletPage />} />
          <Route path="/practice"        element={
            <ErrorBoundary scope="the practice workspace"><PracticePage /></ErrorBoundary>} />
          {/* Keyed, so moving between the two starts a fresh page rather than carrying one
              half's open contest or paper across into the other. */}
          <Route path="/compete"         element={
            <ErrorBoundary scope="the contest workspace">
              <CompetePage key="contests" section="contests" />
            </ErrorBoundary>} />
          <Route path="/exams"           element={
            <ErrorBoundary scope="the examination workspace">
              <CompetePage key="exams" section="exams" />
            </ErrorBoundary>} />
          <Route path="/exam"            element={
            <ErrorBoundary scope="the examination"><ExamModePage /></ErrorBoundary>} />
          <Route path="/platforms"       element={<PlatformsPage />} />
          <Route path="/profile"         element={<ProfilePage />} />
          {/* A team is stored as a contest group; the name people use for it is "team",
              which is what the navigation and every screen say. The old path still works so
              a bookmark from before the rename does not dead-end. */}
          <Route path="/teams"           element={<GroupsPage />} />
          <Route path="/groups"          element={<Navigate to="/teams" replace />} />

          <Route path="/admin"               element={<AdminRoute><AdminOverview /></AdminRoute>} />
          <Route path="/admin/users"         element={<AdminRoute><AdminUsers /></AdminRoute>} />
          <Route path="/admin/audit"         element={<AdminRoute><AdminAudit /></AdminRoute>} />
          <Route path="/admin/contest-files" element={<AdminRoute><AdminFiles /></AdminRoute>} />
          <Route path="/admin/teams"         element={<AdminRoute><AdminGroups /></AdminRoute>} />
          <Route path="/admin/teams/:groupId"
            element={<AdminRoute><AdminGroupDetail /></AdminRoute>} />
          <Route path="/admin/groups"        element={<Navigate to="/admin/teams" replace />} />
          <Route path="/admin/groups/:groupId"
            element={<AdminRoute><AdminGroupDetail /></AdminRoute>} />
          <Route path="/admin/groups/contests/:contestId"
            element={<AdminRoute><AdminGroupContest /></AdminRoute>} />
          <Route path="/admin/exams"         element={<AdminRoute><AdminExams /></AdminRoute>} />
          <Route path="/admin/exams/:examId"
            element={<AdminRoute><AdminExamDetail /></AdminRoute>} />
        </Route>

        <Route path="*" element={<Navigate to="/dashboard" replace />} />
      </Routes>
      </Suspense>
    </ErrorBoundary>
  )
}
