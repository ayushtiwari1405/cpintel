import { NavLink } from 'react-router-dom'
import {
  LayoutDashboard, BarChart2, Lightbulb, Map,
  Link2, User, Zap, ChevronLeft, ChevronRight, LogOut
} from 'lucide-react'
import { useLogout } from '@/hooks/useAuth'
import { useAuth } from '@/contexts/AuthContext'
import { clsx } from 'clsx'

const navItems = [
  { to: '/dashboard',       icon: LayoutDashboard, label: 'Dashboard' },
  { to: '/analytics',       icon: BarChart2,        label: 'Analytics' },
  { to: '/recommendations', icon: Lightbulb,        label: 'Recommend' },
  { to: '/roadmap',         icon: Map,              label: 'Roadmap' },
  { to: '/platforms',       icon: Link2,            label: 'Platforms' },
  { to: '/profile',         icon: User,             label: 'Profile' },
]

interface Props {
  open: boolean
  onToggle: () => void
}

export function Sidebar({ open, onToggle }: Props) {
  const logout = useLogout()
  const { user } = useAuth()

  return (
    <aside className={clsx(
      'fixed left-0 top-0 h-full bg-gray-900 border-r border-gray-800',
      'flex flex-col transition-all duration-200 z-30',
      open ? 'w-60' : 'w-16'
    )}>
      {/* Logo */}
      <div className="flex items-center gap-3 p-4 h-16 border-b border-gray-800">
        <div className="w-8 h-8 rounded-lg bg-indigo-600 flex items-center justify-center flex-shrink-0">
          <Zap size={16} className="text-white" />
        </div>
        {open && <span className="font-semibold text-white text-sm">CPIntel</span>}
      </div>

      {/* Nav */}
      <nav className="flex-1 p-2 space-y-0.5 overflow-y-auto">
        {navItems.map(({ to, icon: Icon, label }) => (
          <NavLink
            key={to}
            to={to}
            className={({ isActive }) => clsx(
              'flex items-center gap-3 px-3 py-2.5 rounded-lg text-sm transition-colors',
              isActive
                ? 'bg-indigo-600/20 text-indigo-400 font-medium'
                : 'text-gray-400 hover:text-gray-200 hover:bg-gray-800'
            )}
          >
            <Icon size={18} className="flex-shrink-0" />
            {open && <span>{label}</span>}
          </NavLink>
        ))}
      </nav>

      {/* User + logout */}
      <div className="p-2 border-t border-gray-800">
        {open && user && (
          <div className="flex items-center gap-2.5 px-3 py-2 mb-1">
            <div className="w-7 h-7 rounded-full bg-indigo-600 flex items-center
              justify-center text-xs text-white font-medium flex-shrink-0">
              {user.username?.[0]?.toUpperCase()}
            </div>
            <div className="min-w-0">
              <p className="text-xs font-medium text-gray-200 truncate">{user.username}</p>
              <p className="text-xs text-gray-500 truncate">{user.email}</p>
            </div>
          </div>
        )}
        <button
          onClick={() => logout.mutate()}
          className="flex items-center gap-3 px-3 py-2.5 rounded-lg text-sm text-gray-400
                     hover:text-red-400 hover:bg-red-900/20 transition-colors w-full"
        >
          <LogOut size={18} className="flex-shrink-0" />
          {open && <span>Logout</span>}
        </button>
        <button
          onClick={onToggle}
          className="flex items-center gap-3 px-3 py-2.5 rounded-lg text-sm text-gray-500
                     hover:text-gray-300 hover:bg-gray-800 transition-colors w-full mt-0.5"
        >
          {open ? <ChevronLeft size={18} /> : <ChevronRight size={18} />}
          {open && <span className="text-xs">Collapse</span>}
        </button>
      </div>
    </aside>
  )
}
