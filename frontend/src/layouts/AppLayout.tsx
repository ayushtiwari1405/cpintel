import { Outlet, useLocation } from 'react-router-dom'
import { Sidebar } from '@/components/common/Sidebar'
import { useState } from 'react'

const PAGE_TITLES: Record<string, string> = {
  '/dashboard':       'Dashboard',
  '/analytics':       'Analytics',
  '/recommendations': 'Recommendations',
  '/roadmap':         'Roadmap',
  '/platforms':       'Platforms',
  '/profile':         'Profile',
}

export default function AppLayout() {
  const [sidebarOpen, setSidebarOpen] = useState(true)
  const location = useLocation()
  const title = PAGE_TITLES[location.pathname] ?? 'CPIntel'

  return (
    <div className="flex h-screen bg-gray-950 overflow-hidden">
      <Sidebar open={sidebarOpen} onToggle={() => setSidebarOpen(v => !v)} />
      <main className={`flex-1 overflow-auto transition-all duration-200
        ${sidebarOpen ? 'ml-60' : 'ml-16'}`}>
        {/* Top bar */}
        <div className="sticky top-0 z-20 bg-gray-950/80 backdrop-blur-sm
          border-b border-gray-800 px-6 h-14 flex items-center">
          <span className="text-sm font-medium text-gray-200">{title}</span>
        </div>
        <div className="p-6 max-w-7xl mx-auto">
          <Outlet />
        </div>
      </main>
    </div>
  )
}
