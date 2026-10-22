import { Outlet, useLocation, useNavigate } from 'react-router-dom'
import { ArrowLeft, Menu } from 'lucide-react'
import { Sidebar } from '@/components/common/Sidebar'
import { BrandMark } from '@/components/common/BrandMark'
import { useExamMode } from '@/store/examModeStore'
import { useAuthStore } from '@/store/authStore'
import { useMediaQuery, WIDE } from '@/hooks/useMediaQuery'
import { useCallback, useEffect, useRef, useState } from 'react'
import { clsx } from 'clsx'

const PAGE_TITLES: Record<string, string> = {
  '/dashboard':       'Dashboard',
  '/analytics':       'Analytics',
  '/recommendations': 'Practice plan',
  '/roadmap':         'Roadmap',
  '/roadmap/gauntlet': 'Roadmap · Gauntlet',
  '/practice':        'Practice',
  '/compete':         'Compete',
  '/exams':           'Exams',
  '/exam':            'Examination',
  '/platforms':       'Platforms',
  '/extension':       'Codeforces Connector',
  '/profile':         'Profile',
  '/teams':           'Teams',
  '/evaluate':        'Evaluate',
  '/download':        'Desktop app',
  '/admin':               'Admin',
  '/admin/users':         'Admin · Users',
  '/admin/audit':         'Admin · Audit trail',
  '/admin/contest-files': 'Admin · Contest files',
  '/admin/classrooms':    'Admin · Classrooms',
  '/admin/teams':         'Admin · Teams',
  '/admin/exams':         'Admin · Examinations',
}

/** Detail pages, titled after the list they belong to. Longest prefix first. */
const PREFIX_TITLES: [string, string][] = [
  ['/admin/groups/contests/', 'Admin · Team contest'],
  ['/admin/classrooms/',      'Admin · Classroom'],
  ['/admin/teams/',           'Admin · Team'],
  ['/admin/groups/',          'Admin · Team'],
  ['/admin/exams/',           'Admin · Examination'],
]

function titleFor(path: string): string {
  return PAGE_TITLES[path]
    ?? PREFIX_TITLES.find(([prefix]) => path.startsWith(prefix))?.[1]
    ?? 'CPIntel'
}

/**
 * Pages that manage their own height and want the whole width.
 *
 * The reading pages are documents and belong in a centred column. Practice and Compete are
 * workspaces: resizable panes that have to fill the window exactly, so a max width and a page
 * scrollbar would fight the split rather than help it.
 */
const WORKSPACE_ROUTES = new Set(['/practice', '/compete', '/exams', '/exam'])

/** Whether the wide sidebar shows labels; remembered per browser, open unless closed before. */
const SIDEBAR_KEY = 'cpintel-sidebar'
function initialSidebarOpen(): boolean {
  try { return localStorage.getItem(SIDEBAR_KEY) !== 'collapsed' } catch { return true }
}

export default function AppLayout() {
  const [sidebarOpen, setSidebarOpen] = useState(initialSidebarOpen)
  const [drawerOpen, setDrawerOpen] = useState(false)
  const wide = useMediaQuery(WIDE)
  const location = useLocation()
  const navigate = useNavigate()
  const title = titleFor(location.pathname)
  // The router marks the first entry of this tab's history 'default'. Anything else means
  // there is a page of ours behind this one — going back from the first page would leave
  // the app altogether, so the button is not offered there.
  const examLive = useExamMode(s => s.live)
  // Signed in with an examination password: this paper is the whole app.
  const examMode = useAuthStore(s => s.mode) === 'EXAM'
  const canGoBack = location.key !== 'default' && !examLive && !examMode
  const workspace = WORKSPACE_ROUTES.has(location.pathname)
  const onBack = canGoBack ? () => navigate(-1) : undefined

  const sidebarRef = useRef<HTMLElement>(null)
  const menuButtonRef = useRef<HTMLButtonElement>(null)
  const drawer = !wide && drawerOpen

  const toggleSidebar = useCallback(() => {
    setSidebarOpen(v => {
      try { localStorage.setItem(SIDEBAR_KEY, v ? 'collapsed' : 'open') } catch { /* not kept */ }
      return !v
    })
  }, [])

  const closeDrawer = useCallback(() => {
    setDrawerOpen(false)
  }, [])

  // Following a link closes the drawer; so does growing the window past the breakpoint. The
  // focus restoration below waits for the commit that removes `inert` from the page; focusing the
  // menu button in this effect would otherwise be rejected while the old drawer state is active.
  useEffect(() => {
    if (!drawerOpen) return
    setDrawerOpen(false)
  // `drawerOpen` is deliberately read from the render that preceded a route/breakpoint change;
  // adding it as a dependency would close the drawer immediately after it opens.
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [location.pathname, wide])

  const wasDrawerOpen = useRef(false)
  useEffect(() => {
    if (wasDrawerOpen.current && !drawerOpen && !wide) menuButtonRef.current?.focus()
    wasDrawerOpen.current = drawerOpen
  }, [drawerOpen, wide])

  // While the drawer is out: Escape closes it, and focus starts on its first link.
  useEffect(() => {
    if (!drawer) return
    const first = sidebarRef.current?.querySelector<HTMLElement>('nav a')
      ?? sidebarRef.current?.querySelector<HTMLElement>('button')
    first?.focus()
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') closeDrawer() }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [drawer, closeDrawer])

  return (
    <div className="flex h-screen overflow-hidden bg-gray-950">
      <a href="#main-content"
        className="sr-only focus:not-sr-only focus:fixed focus:left-3 focus:top-3 focus:z-70
                   focus:rounded-md focus:bg-gray-900 focus:px-3 focus:py-2 focus:text-sm
                   focus:text-gray-50 focus:ring-2 focus:ring-accent-400">
        Skip to content
      </a>

      <Sidebar ref={sidebarRef} open={sidebarOpen} onToggle={toggleSidebar}
        wide={wide} drawerOpen={drawerOpen} onCloseDrawer={closeDrawer}
        locked={examLive} examMode={examMode}
        // The page's title and Back live in the sidebar on a wide screen: a bar across the top
        // is a row of the page given to one button, and on a workspace a row of the problem
        // pushed out of sight. A narrow screen has no room beside the page, so it gets a bar.
        page={{ title, onBack }} />

      {drawer && (
        <div aria-hidden="true" onClick={closeDrawer}
          className="fixed inset-0 z-30 bg-black/50 backdrop-blur-[1px] lg:hidden" />
      )}

      <div className={clsx('flex min-w-0 flex-1 flex-col transition-[margin] duration-200',
        wide && (sidebarOpen ? 'ml-60' : 'ml-16'))} inert={drawer}>
        {!wide && (
          <header className="flex h-12 shrink-0 items-center gap-1 border-b border-gray-800
                             bg-gray-900 px-2">
            <button
              ref={menuButtonRef}
              type="button"
              onClick={() => setDrawerOpen(true)}
              aria-label="Open navigation"
              aria-expanded={drawerOpen}
              aria-controls="app-sidebar"
              className="flex h-9 w-9 items-center justify-center rounded-lg text-gray-400
                         transition-colors hover:bg-gray-850 hover:text-gray-100"
            >
              <Menu size={19} />
            </button>
            {onBack ? (
              <button type="button" onClick={onBack} aria-label="Back"
                className="flex h-9 w-9 items-center justify-center rounded-lg text-gray-400
                           transition-colors hover:bg-gray-850 hover:text-gray-100">
                <ArrowLeft size={17} />
              </button>
            ) : (
              <BrandMark className="ml-1 h-6 w-6" />
            )}
            <span className="ml-1.5 truncate text-sm font-medium text-gray-100">{title}</span>
          </header>
        )}

        <main id="main-content" tabIndex={-1} className={clsx(
          'flex min-h-0 flex-1 flex-col focus:outline-hidden',
          workspace ? 'overflow-hidden' : 'overflow-auto',
        )}>
          {workspace ? (
            <div className="flex min-h-0 flex-1 flex-col">
              {/* Workspaces give every pixel to the panes and draw no title of their own; this
                  is the heading a screen reader lands on. */}
              <h1 className="sr-only">{title}</h1>
              <Outlet />
            </div>
          ) : (
            <div className="mx-auto w-full max-w-7xl px-4 py-5 sm:px-6 sm:py-6">
              <Outlet />
            </div>
          )}
        </main>
      </div>
    </div>
  )
}
