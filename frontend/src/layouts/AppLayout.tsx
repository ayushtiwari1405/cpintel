import { Outlet, useLocation, useNavigate } from 'react-router-dom'
import { ArrowLeft } from 'lucide-react'
import { Sidebar } from '@/components/common/Sidebar'
import { useExamMode } from '@/store/examModeStore'
import { useAuthStore } from '@/store/authStore'
import { useState } from 'react'
import { clsx } from 'clsx'

const PAGE_TITLES: Record<string, string> = {
  '/dashboard':       'Dashboard',
  '/analytics':       'Analytics',
  '/recommendations': 'Recommendations',
  '/roadmap':         'Roadmap',
  '/roadmap/gauntlet': 'Roadmap · Gauntlet',
  '/practice':        'Practice',
  '/compete':         'Compete',
  '/exam':            'Examination',
  '/platforms':       'Platforms',
  '/profile':         'Profile',
  '/teams':           'Teams',
  '/admin':               'Admin',
  '/admin/users':         'Admin · Users',
  '/admin/audit':         'Admin · Audit trail',
  '/admin/contest-files': 'Admin · Contest files',
  '/admin/teams':         'Admin · Teams',
  '/admin/exams':         'Admin · Examinations',
}

/** Detail pages, titled after the list they belong to. Longest prefix first. */
const PREFIX_TITLES: [string, string][] = [
  ['/admin/groups/contests/', 'Admin · Team contest'],
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
const WORKSPACE_ROUTES = new Set(['/practice', '/compete', '/exam'])

export default function AppLayout() {
  const [sidebarOpen, setSidebarOpen] = useState(true)
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

  return (
    <div className="flex h-screen bg-gray-950 overflow-hidden">
      <Sidebar open={sidebarOpen} onToggle={() => setSidebarOpen(v => !v)} locked={examLive}
        examMode={examMode} />
      <main className={clsx(
        'flex-1 flex flex-col min-h-0 transition-all duration-200',
        workspace ? 'overflow-hidden' : 'overflow-auto',
        sidebarOpen ? 'ml-60' : 'ml-16'
      )}>
        {/* Top bar */}
        <div className="sticky top-0 z-20 flex h-14 flex-shrink-0 items-center border-b
          border-gray-800 bg-gray-950/80 px-6 backdrop-blur-sm">
          {canGoBack && (
            <button
              onClick={() => navigate(-1)}
              title="Back to the previous page"
              aria-label="Back"
              className="-ml-2 mr-2 flex items-center gap-1 rounded-lg px-2 py-1.5 text-xs
                         text-gray-500 transition-colors hover:bg-gray-800 hover:text-gray-200"
            >
              <ArrowLeft size={15} /> Back
            </button>
          )}
          <span className="text-sm font-medium text-gray-200">{title}</span>
        </div>

        {workspace ? (
          <div className="flex flex-1 flex-col min-h-0">
            <Outlet />
          </div>
        ) : (
          <div className="p-6 max-w-7xl mx-auto w-full">
            <Outlet />
          </div>
        )}
      </main>
    </div>
  )
}
