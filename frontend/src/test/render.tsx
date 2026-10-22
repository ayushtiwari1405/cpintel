import type { ReactElement, ReactNode } from 'react'
import { render } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { AuthProvider } from '@/contexts/AuthContext'
import { ThemeProvider } from '@/contexts/ThemeContext'
import { Toaster } from '@/components/common/Toaster'
import { useAuthStore } from '@/store/authStore'
import type { Role, User } from '@/types'

/** A user as GET /users/me returns one. */
export function user(role: Role = 'USER', overrides: Partial<User> = {}): User {
  return {
    userId: role === 'USER' ? 42 : 1,
    username: role === 'USER' ? 'alice' : 'admin',
    email: role === 'USER' ? 'alice@cpintel.test' : 'admin@cpintel.test',
    role,
    isVerified: true,
    createdAt: '2026-09-01T00:00:00Z',
    ...overrides,
  } as User
}

/**
 * What the persisted store holds after a reload: who was signed in, and no token - the token
 * lives in memory only, so the app has to restore it before it can ask the server anything.
 */
export function afterReload(who: User, mode: 'NORMAL' | 'EXAM' = 'NORMAL', examId: number | null = null) {
  useAuthStore.setState({ user: who, isAuthenticated: true, accessToken: null, mode, examId })
}

export function signedIn(who: User, token = 'token-1') {
  useAuthStore.setState({ user: who, isAuthenticated: true, accessToken: token, mode: 'NORMAL', examId: null })
}

/** The app's providers around `ui`, at `path`. Queries do not retry, so a failure shows at once. */
export function renderAt(path: string, ui: ReactElement, extra?: (children: ReactNode) => ReactNode) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0 }, mutations: { retry: false } },
  })
  const tree = (
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[path]}>
        <ThemeProvider>
          <AuthProvider>{extra ? extra(ui) : ui}</AuthProvider>
        </ThemeProvider>
      </MemoryRouter>
      <Toaster />
    </QueryClientProvider>
  )
  return { queryClient, ...render(tree) }
}
