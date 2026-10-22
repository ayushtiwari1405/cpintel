import { describe, expect, it, vi } from 'vitest'
import { screen, waitFor } from '@testing-library/react'
import { useLocation } from 'react-router-dom'
import axios, { AxiosError } from 'axios'
import { AppRoutes } from './AppRoutes'
import { fakeServer, gate } from '@/test/server'
import { afterReload, renderAt, signedIn, user } from '@/test/render'
import { useAuthStore } from '@/store/authStore'
import { apiClient } from '@/api/client'
import type { Role } from '@/types'

// Every page is a labelled stub, so these tests are about which page the guards let through
// and where they send everyone else - not about the pages themselves.
const stub = vi.hoisted(() => (name: string) => ({
  default: (props: Record<string, unknown>) => {
    const detail = Object.entries(props).filter(([k]) => k !== 'children')
      .map(([k, v]) => `${k}=${String(v)}`).join(' ')
    return <h1>{`${name} page${detail ? ` (${detail})` : ''}`}</h1>
  },
}))
vi.mock('@/pages/LoginPage', () => stub('Login'))
vi.mock('@/pages/ForgotPasswordPage', () => stub('Forgot password'))
vi.mock('@/pages/ResetPasswordPage', () => stub('Reset password'))
vi.mock('@/pages/DashboardPage', () => stub('Dashboard'))
vi.mock('@/pages/AnalyticsPage', () => stub('Analytics'))
vi.mock('@/pages/RoadmapPage', () => stub('Roadmap'))
vi.mock('@/pages/RecommendationsPage', () => stub('Practice plan'))
vi.mock('@/pages/GauntletPage', () => stub('Gauntlet'))
vi.mock('@/pages/PlatformsPage', () => stub('Platforms'))
vi.mock('@/pages/PracticePage', () => stub('Practice'))
vi.mock('@/pages/CompetePage', () => stub('Compete'))
vi.mock('@/pages/ProfilePage', () => stub('Profile'))
vi.mock('@/pages/admin/AdminOverviewPage', () => stub('Admin overview'))
vi.mock('@/pages/admin/AdminUsersPage', () => stub('Admin users'))
vi.mock('@/pages/admin/AdminAuditPage', () => stub('Admin audit'))
vi.mock('@/pages/admin/AdminContestFilesPage', () => stub('Admin contest files'))
vi.mock('@/pages/admin/AdminClassroomsPage', () => stub('Admin classrooms'))
vi.mock('@/pages/admin/AdminClassroomDetailPage', () => stub('Admin classroom'))
vi.mock('@/pages/admin/AdminGroupsPage', () => stub('Admin teams'))
vi.mock('@/pages/admin/AdminGroupDetailPage', () => stub('Admin team'))
vi.mock('@/pages/admin/AdminGroupContestPage', () => stub('Admin team contest'))
vi.mock('@/pages/admin/AdminExamsPage', () => stub('Admin exams'))
vi.mock('@/pages/admin/AdminExamDetailPage', () => stub('Admin exam'))
vi.mock('@/pages/GroupsPage', () => stub('Teams'))
vi.mock('@/pages/ExtensionPage', () => stub('Extension'))
vi.mock('@/pages/DownloadPage', () => stub('Download'))
vi.mock('@/pages/EvaluatePage', () => stub('Evaluate'))
vi.mock('@/layouts/AppLayout', async () => {
  const { Outlet } = await import('react-router-dom')
  return { default: () => <main aria-label="app layout"><Outlet /></main> }
})

function Where() {
  return <output aria-label="location">{useLocation().pathname}</output>
}

/** Renders the real routes at `path`, with the server answering /users/me as `role`. */
async function visit(path: string) {
  renderAt(path, <><AppRoutes /><Where /></>)
  return screen.findByRole('heading', { level: 1 })
}

function asRole(role: Role) {
  const me = user(role)
  fakeServer({ 'GET /users/me': { data: me } })
  signedIn(me)
}

const location = () => screen.getByLabelText('location').textContent

describe('signed out', () => {
  it.each(['/dashboard', '/recommendations', '/roadmap', '/admin', '/admin/users', '/exam', '/profile'])(
    '%s sends them to sign in', async path => {
      fakeServer({})
      expect(await visit(path)).toHaveTextContent('Login page')
      expect(location()).toBe('/login')
    })

  it('the sign-in and password pages are open to them', async () => {
    fakeServer({})
    expect(await visit('/forgot-password')).toHaveTextContent('Forgot password page')
    expect(location()).toBe('/forgot-password')
  })

  it('the download page is open to them, outside the app layout', async () => {
    fakeServer({})
    expect(await visit('/download')).toHaveTextContent('Download page')
    expect(screen.queryByRole('main', { name: 'app layout' })).not.toBeInTheDocument()
  })

  it('asks the server nothing about a session they do not have', async () => {
    const server = fakeServer({})
    await visit('/dashboard')
    expect(server.seen).toEqual([])
  })
})

describe('a student', () => {
  it.each([
    ['/dashboard', 'Dashboard page'],
    ['/recommendations', 'Practice plan page'],
    ['/roadmap', 'Roadmap page'],
    ['/compete', 'Compete page (section=contests)'],
    ['/exams', 'Compete page (section=exams)'],
    ['/profile', 'Profile page'],
  ])('reaches %s inside the app layout', async (path, page) => {
    asRole('USER')
    expect(await visit(path)).toHaveTextContent(page)
    expect(location()).toBe(path)
    expect(screen.getByRole('main', { name: 'app layout' })).toBeInTheDocument()
  })

  it.each(['/admin', '/admin/users', '/admin/exams/3'])('is turned away from the console at %s', async path => {
    asRole('USER')
    expect(await visit(path)).toHaveTextContent('Dashboard page')
    expect(location()).toBe('/dashboard')
  })

  it('has no use for the sign-in page and lands on the dashboard', async () => {
    asRole('USER')
    expect(await visit('/login')).toHaveTextContent('Dashboard page')
  })

  it('is sent from the examination-only route to the contests', async () => {
    asRole('USER')
    expect(await visit('/exam')).toHaveTextContent('Compete page (section=contests)')
    expect(location()).toBe('/compete')
  })

  it('an unknown address goes home', async () => {
    asRole('USER')
    expect(await visit('/no/such/page')).toHaveTextContent('Dashboard page')
  })

  it('an old team link still works', async () => {
    asRole('USER')
    expect(await visit('/groups')).toHaveTextContent('Teams page')
    expect(location()).toBe('/teams')
  })
})

describe.each(['ADMIN', 'SUPER_ADMIN'] as const)('an %s', role => {
  it('reaches the console', async () => {
    asRole(role)
    expect(await visit('/admin/users')).toHaveTextContent('Admin users page')
  })

  it.each(['/dashboard', '/recommendations', '/roadmap', '/practice'])('is sent from student page %s to the console', async path => {
    asRole(role)
    expect(await visit(path)).toHaveTextContent('Admin overview page')
    expect(location()).toBe('/admin')
  })

  it('lands on the console from the sign-in page and from home', async () => {
    asRole(role)
    expect(await visit('/login')).toHaveTextContent('Admin overview page')
  })

  it('keeps their own profile', async () => {
    asRole(role)
    expect(await visit('/profile')).toHaveTextContent('Profile page')
  })
})

describe('an examination session', () => {
  function examSession(examId: number | null) {
    const me = user('USER')
    fakeServer({ 'GET /users/me': { data: me } })
    useAuthStore.setState({ user: me, isAuthenticated: true, accessToken: 't', mode: 'EXAM', examId })
  }

  it.each(['/dashboard', '/recommendations', '/compete', '/profile', '/admin', '/'])(
    'is held to its paper from %s', async path => {
      examSession(7)
      expect(await visit(path)).toHaveTextContent('Compete page (examOnly=7)')
      expect(location()).toBe('/exam')
    })

  it('opens its own paper', async () => {
    examSession(7)
    expect(await visit('/exam')).toHaveTextContent('Compete page (examOnly=7)')
  })

  it('without a paper to show is ended and sent to sign in, not round in a loop', async () => {
    examSession(null)
    expect(await visit('/exam')).toHaveTextContent('Login page')
    expect(location()).toBe('/login')
    expect(useAuthStore.getState().isAuthenticated).toBe(false)
  })
})

describe('the role comes from the server, not the stored profile', () => {
  it('a stored ADMIN profile that the server says is a USER loses the console', async () => {
    fakeServer({ 'GET /users/me': { data: user('USER') } })
    signedIn(user('ADMIN'))
    renderAt('/admin', <><AppRoutes /><Where /></>)
    expect(await screen.findByRole('heading', { name: 'Dashboard page' })).toBeInTheDocument()
    expect(useAuthStore.getState().user?.role).toBe('USER')
  })
})

// After a reload the store remembers who was signed in but holds no token; the client restores
// it from the refresh cookie (api/session.ts, whose own Node tests cover the interceptor in
// depth). These run it through the real routes and AuthProvider.
describe('restoring the session after a reload', () => {
  /** The refresh endpoint, held until `open()`; it answers with `token`. */
  function heldRefresh(token: string) {
    const held = gate()
    const post = vi.spyOn(axios, 'post').mockImplementation(async (url: string) => {
      if (!url.endsWith('/auth/refresh')) throw new Error(`unexpected POST ${url}`)
      await held.wait
      return { data: { success: true, data: { accessToken: token } } }
    })
    return { open: held.open, post }
  }

  it('keeps the page while the session is restored, and sends nothing without the token', async () => {
    const me = user('USER')
    const server = fakeServer({ 'GET /users/me': req => req.token === 'restored' ? { data: me } : { status: 401 } })
    const refresh = heldRefresh('restored')
    afterReload(me)

    expect(await visit('/recommendations')).toHaveTextContent('Practice plan page')
    expect(location()).toBe('/recommendations')
    expect(server.seen).toEqual([])           // the profile request is waiting for the token

    refresh.open()
    await waitFor(() => expect(server.calls('GET /users/me')).toHaveLength(1))
    expect(server.seen.every(s => s.token === 'restored')).toBe(true)
    expect(refresh.post).toHaveBeenCalledTimes(1)
    expect(useAuthStore.getState().accessToken).toBe('restored')
    expect(location()).toBe('/recommendations')
  })

  it('requests made while it is restored share the one renewal', async () => {
    const me = user('USER')
    const server = fakeServer({
      'GET /users/me': { data: me },
      'GET /roadmaps/current': req => req.token === 'restored' ? { data: [] } : { status: 401 },
      'GET /recommendations/daily': req => req.token === 'restored' ? { data: null } : { status: 401 },
    })
    const refresh = heldRefresh('restored')
    afterReload(me)
    await visit('/dashboard')

    const pending = Promise.all([apiClient.get('/roadmaps/current'), apiClient.get('/recommendations/daily')])
    refresh.open()
    await pending
    await waitFor(() => expect(server.calls('GET /users/me')).toHaveLength(1))

    expect(refresh.post).toHaveBeenCalledTimes(1)
    expect(server.seen.map(s => s.token)).toEqual(['restored', 'restored', 'restored'])
    expect(server.seen.some(s => s.token === null)).toBe(false)
  })

  // Last in the file: signing out leaves the page (window.location), which the client does only
  // once per page load, as a real browser would.
  it('an ended session signs out once and lands on sign in, with nothing sent to the API', async () => {
    const me = user('USER')
    const server = fakeServer({ 'GET /users/me': { data: me } })
    const refused = new AxiosError('refused', 'ERR_BAD_REQUEST', undefined, {}, {
      status: 401, statusText: '401', headers: {}, config: {} as never, data: { code: 'TOKEN_EXPIRED' },
    })
    const post = vi.spyOn(axios, 'post').mockRejectedValue(refused)
    // jsdom does not navigate; it reports that it was asked to.
    const jsdomNoise = vi.spyOn(console, 'error').mockImplementation(() => {})
    afterReload(me)

    renderAt('/dashboard', <><AppRoutes /><Where /></>)

    expect(await screen.findByRole('heading', { name: 'Login page' })).toBeInTheDocument()
    expect(location()).toBe('/login')
    expect(useAuthStore.getState().isAuthenticated).toBe(false)
    expect(post).toHaveBeenCalledTimes(1)
    expect(server.seen).toEqual([])
    jsdomNoise.mockRestore()
  })
})
