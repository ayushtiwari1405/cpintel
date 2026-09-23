import { Outlet, useLocation } from 'react-router-dom'
import { Sidebar } from '@/components/common/Sidebar'
import { useState } from 'react'
import { clsx } from 'clsx'

const PAGE_TITLES: Record<string, string> = {
  '/dashboard':       'Dashboard',
  '/analytics':       'Analytics',
  '/recommendations': 'Recommendations',
  '/roadmap':         'Roadmap',
  '/practice':        'Practice',
  '/compete':         'Compete',
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

/**
 * Pages that manage their own height and want the whole width.
 *
 * The reading pages are documents and belong in a centred column. Practice and Compete are
 * workspaces: resizable panes that have to fill the window exactly, so a max width and a page
 * scrollbar would fight the split rather than help it.
 */
const WORKSPACE_ROUTES = new Set(['/practice', '/compete'])

export default function AppLayout() {
  const [sidebarOpen, setSidebarOpen] = useState(true)
  const location = useLocation()
  const title = PAGE_TITLES[location.pathname] ?? 'CPIntel'
  const workspace = WORKSPACE_ROUTES.has(location.pathname)

  return (
    <div className="flex h-screen bg-gray-950 overflow-hidden">
      <Sidebar open={sidebarOpen} onToggle={() => setSidebarOpen(v => !v)} />
      <main className={clsx(
        'flex-1 flex flex-col min-h-0 transition-all duration-200',
        workspace ? 'overflow-hidden' : 'overflow-auto',
        sidebarOpen ? 'ml-60' : 'ml-16'
      )}>
        {/* Top bar */}
        <div className="sticky top-0 z-20 flex h-14 flex-shrink-0 items-center border-b
          border-gray-800 bg-gray-950/80 px-6 backdrop-blur-sm">
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
