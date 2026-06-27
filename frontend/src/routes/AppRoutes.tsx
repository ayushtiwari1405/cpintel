import { Routes, Route, Navigate } from 'react-router-dom'
import { useAuth } from '@/contexts/AuthContext'
import { lazy, Suspense } from 'react'
import { LoadingScreen } from '@/components/common/LoadingScreen'

const LoginPage         = lazy(() => import('@/pages/LoginPage'))
const RegisterPage      = lazy(() => import('@/pages/RegisterPage'))
const DashboardPage     = lazy(() => import('@/pages/DashboardPage'))
const AnalyticsPage     = lazy(() => import('@/pages/AnalyticsPage'))
const RecommendPage     = lazy(() => import('@/pages/RecommendationsPage'))
const RoadmapPage       = lazy(() => import('@/pages/RoadmapPage'))
const PlatformsPage     = lazy(() => import('@/pages/PlatformsPage'))
const ProfilePage       = lazy(() => import('@/pages/ProfilePage'))
const AppLayout         = lazy(() => import('@/layouts/AppLayout'))

function ProtectedRoute({ children }: { children: React.ReactNode }) {
  const { isAuthenticated } = useAuth()
  return isAuthenticated ? <>{children}</> : <Navigate to="/login" replace />
}

function GuestRoute({ children }: { children: React.ReactNode }) {
  const { isAuthenticated } = useAuth()
  return !isAuthenticated ? <>{children}</> : <Navigate to="/dashboard" replace />
}

export function AppRoutes() {
  return (
    <Suspense fallback={<LoadingScreen />}>
      <Routes>
        <Route path="/" element={<Navigate to="/dashboard" replace />} />

        <Route path="/login" element={
          <GuestRoute><LoginPage /></GuestRoute>
        } />
        <Route path="/register" element={
          <GuestRoute><RegisterPage /></GuestRoute>
        } />

        <Route element={
          <ProtectedRoute><AppLayout /></ProtectedRoute>
        }>
          <Route path="/dashboard"       element={<DashboardPage />} />
          <Route path="/analytics"       element={<AnalyticsPage />} />
          <Route path="/recommendations" element={<RecommendPage />} />
          <Route path="/roadmap"         element={<RoadmapPage />} />
          <Route path="/platforms"       element={<PlatformsPage />} />
          <Route path="/profile"         element={<ProfilePage />} />
        </Route>

        <Route path="*" element={<Navigate to="/dashboard" replace />} />
      </Routes>
    </Suspense>
  )
}
