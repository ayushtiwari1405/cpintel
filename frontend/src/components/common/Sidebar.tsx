import { NavLink } from 'react-router-dom'
import { forwardRef } from 'react'
import {
  ArrowLeft, LayoutDashboard, BarChart2, Map, Link2, User, PanelLeftClose, PanelLeftOpen,
  LogOut, Code2, Swords, Shield, Users, ScrollText, FolderCog, UsersRound, FileText, School,
  ClipboardCheck, Monitor, X, type LucideIcon } from 'lucide-react'
import { useLogout } from '@/hooks/useAuth'
import { ThemeToggle } from './ThemeToggle'
import { BrandLockup } from './BrandMark'
import { useAuth } from '@/contexts/AuthContext'
import { isAdmin, isSuperAdmin, roleLabel } from '@/utils/roles'
import { clsx } from 'clsx'
import { useMyEvaluationExams } from '@/hooks/useEvaluation'
import { isDesktop } from '@/utils/desktopBridge'

interface Item { to: string; icon: LucideIcon; label: string }
interface Group { label: string; items: Item[] }

const DASHBOARD: Item = { to: '/dashboard', icon: LayoutDashboard, label: 'Dashboard' }
const ANALYTICS: Item = { to: '/analytics', icon: BarChart2,       label: 'Analytics' }
// Recommendations is parked for now: the roadmap already says what to practise next, and two
// answers to the same question that can disagree are worse than one. The page and its route
// stay; add it to the Train group to bring it back.
const ROADMAP:   Item = { to: '/roadmap',   icon: Map,             label: 'Roadmap' }
const PRACTICE:  Item = { to: '/practice',  icon: Code2,           label: 'Practice' }
const COMPETE:   Item = { to: '/compete',   icon: Swords,          label: 'Compete' }
const EXAMS:     Item = { to: '/exams',     icon: FileText,        label: 'Exams' }
const TEAMS:     Item = { to: '/teams',     icon: UsersRound,      label: 'Teams' }
const EVALUATE:  Item = { to: '/evaluate',  icon: ClipboardCheck,  label: 'Evaluate' }
const PLATFORMS: Item = { to: '/platforms', icon: Link2,           label: 'Platforms' }
const PROFILE:   Item = { to: '/profile',   icon: User,            label: 'Profile' }
const DESKTOP:   Item = { to: '/download',  icon: Monitor,         label: 'Desktop app' }

// Shown only to admins. The server refuses these routes to everyone else regardless, so
// hiding them is about keeping the sidebar honest rather than about access control.
const adminItems: Item[] = [
  { to: '/admin',               icon: Shield,     label: 'Overview' },
  { to: '/admin/users',         icon: Users,      label: 'Users' },
  { to: '/admin/classrooms',    icon: School,     label: 'Classrooms' },
  { to: '/admin/teams',         icon: UsersRound, label: 'Teams' },
  { to: '/admin/exams',         icon: FileText,   label: 'Examinations' },
  { to: '/admin/audit',         icon: ScrollText, label: 'Audit trail' },
  { to: '/admin/contest-files', icon: FolderCog,  label: 'Contest files' },
]

interface Props {
  /** Wide screens: the sidebar shows labels (true) or icons only (false). */
  open: boolean
  onToggle: () => void
  /**
   * Narrow screens: the sidebar is a drawer over the page, and this says whether it is out.
   * On a wide screen it is always shown and these two are ignored.
   */
  wide: boolean
  drawerOpen: boolean
  onCloseDrawer: () => void
  /**
   * An examination is live in this tab. The links are shown but inert: following one would
   * unmount the paper and release its monitor without anything being recorded.
   */
  locked?: boolean
  /** Signed in with an examination password: no other page exists for this session. */
  examMode?: boolean
  /**
   * The page's title and back action. On a wide screen they live here rather than on a bar of
   * their own, so content starts at the top of the window; a narrow screen has a top bar for
   * them. `onBack` is absent where there is nowhere to go.
   */
  page?: { title: string; onBack?: () => void }
}

export const Sidebar = forwardRef<HTMLElement, Props>(function Sidebar(
  { open, onToggle, wide, drawerOpen, onCloseDrawer, locked, examMode, page }, ref) {
  const logout = useLogout()
  const { user } = useAuth()
  const showAdmin = isAdmin(user)
  // Teaching assistants get a marking page; everyone else never sees the link.
  const { data: marking } = useMyEvaluationExams()
  const marks = !!marking && marking.length > 0
  // Labels everywhere except a collapsed wide sidebar; the drawer is always full width.
  const expanded = wide ? open : true

  const studentGroups: Group[] = [
    { label: 'Train',       items: [DASHBOARD, ANALYTICS, ROADMAP] },
    { label: 'Solve',       items: [PRACTICE, COMPETE, EXAMS] },
    { label: 'Collaborate', items: marks ? [TEAMS, EVALUATE] : [TEAMS] },
    // Where to get the desktop app — pointless inside it.
    { label: 'Account',     items: isDesktop() ? [PLATFORMS, PROFILE] : [PLATFORMS, PROFILE, DESKTOP] },
  ]
  // Admins run the console and are not students here, so the student pages are not offered to
  // them — only their profile, for their own password.
  const groups: Group[] = showAdmin
    ? [{ label: isSuperAdmin(user) ? 'Super admin' : 'Console', items: adminItems },
       { label: 'Account', items: [PROFILE] }]
    : studentGroups

  const linkClass = ({ isActive }: { isActive: boolean }) => clsx(
    'group relative flex items-center gap-3 rounded-lg text-sm transition-colors',
    expanded ? 'px-2.5 py-2' : 'h-9 w-9 justify-center mx-auto',
    isActive
      ? 'bg-accent-400/10 text-gray-50 font-medium'
      : 'text-gray-400 hover:text-gray-100 hover:bg-gray-850',
  )

  return (
    <aside
      ref={ref}
      id="app-sidebar"
      aria-label="Main navigation"
      className={clsx(
        'fixed inset-y-0 left-0 z-40 flex flex-col border-r border-gray-800 bg-gray-900',
        'transition-[width,transform] duration-200 ease-out',
        wide
          ? (open ? 'w-60' : 'w-16')
          : clsx('w-72 max-w-[85vw] shadow-2xl shadow-black/40',
              drawerOpen ? 'translate-x-0' : '-translate-x-full'),
      )}
      // A closed drawer is off screen; keep it out of the tab order and the accessibility tree.
      inert={!wide && !drawerOpen}
    >
      {/* Brand, with the collapse (wide) or close (drawer) control. */}
      <div className={clsx('flex h-14 shrink-0 items-center border-b border-gray-800',
        expanded ? 'gap-3 px-4' : 'justify-center')}>
        {expanded && <BrandLockup />}
        {wide ? (
          <button
            type="button"
            onClick={onToggle}
            title={open ? 'Collapse the sidebar' : 'Expand the sidebar'}
            aria-label={open ? 'Collapse sidebar' : 'Expand sidebar'}
            aria-expanded={open}
            aria-controls="app-sidebar"
            className={clsx('flex h-8 w-8 shrink-0 items-center justify-center rounded-lg',
              'text-gray-500 transition-colors hover:bg-gray-850 hover:text-gray-200',
              open && 'ml-auto')}
          >
            {open ? <PanelLeftClose size={17} /> : <PanelLeftOpen size={17} />}
          </button>
        ) : (
          <button
            type="button"
            onClick={onCloseDrawer}
            aria-label="Close navigation"
            className="ml-auto flex h-8 w-8 items-center justify-center rounded-lg text-gray-500
                       transition-colors hover:bg-gray-850 hover:text-gray-200"
          >
            <X size={18} />
          </button>
        )}
      </div>

      {wide && page && (
        <div className={clsx('flex shrink-0 items-center gap-1 border-b border-gray-800 py-2',
          expanded ? 'px-2' : 'justify-center')}>
          {page.onBack && (
            <button
              type="button"
              onClick={page.onBack}
              title="Back to the previous page"
              aria-label="Back"
              className="flex shrink-0 items-center gap-1 rounded-md px-2 py-1 text-xs
                         text-gray-500 transition-colors hover:bg-gray-850 hover:text-gray-200"
            >
              <ArrowLeft size={14} /> {expanded && 'Back'}
            </button>
          )}
          {expanded && (
            <span className={clsx('truncate text-xs font-medium text-gray-300',
              !page.onBack && 'px-2 py-1')} title={page.title}>
              {page.title}
            </span>
          )}
        </div>
      )}

      {examMode ? (
        <div className="flex-1 p-3">
          {expanded ? (
            <p className="rounded-lg border border-accent-800/60 bg-accent-950/40 px-3 py-2.5
                          text-xs leading-relaxed text-accent-200">
              Examination mode — only this paper is available. Sign out when you are done.
            </p>
          ) : (
            <span className="sr-only">Examination mode: only this paper is available.</span>
          )}
        </div>
      ) : (
        <nav
          className={clsx('flex-1 overflow-y-auto px-2 py-3',
            locked && 'pointer-events-none opacity-40')}
          aria-disabled={locked || undefined}
        >
          {groups.map((group, gi) => (
            <div key={group.label} className={clsx(gi > 0 && 'mt-4')}>
              {expanded ? (
                <p className="px-2.5 pb-1.5 text-[10px] font-semibold uppercase tracking-[0.08em]
                              text-gray-500">
                  {group.label}
                </p>
              ) : (
                gi > 0 && <div className="mx-auto mb-3 h-px w-6 bg-gray-800" aria-hidden="true" />
              )}
              <ul className="space-y-0.5">
                {group.items.map(({ to, icon: Icon, label }) => (
                  <li key={to}>
                    <NavLink
                      to={to}
                      // The overview lives at the root of /admin, so without this every admin
                      // link would light up whenever any admin page was open.
                      end={to === '/admin'}
                      onClick={() => { if (!wide) onCloseDrawer() }}
                      className={linkClass}
                      title={expanded ? undefined : label}
                      aria-label={expanded ? undefined : label}
                      tabIndex={locked ? -1 : undefined}
                    >
                      {({ isActive }) => (
                        <>
                          {isActive && (
                            <span aria-hidden="true"
                              className="absolute left-0 top-1.5 bottom-1.5 w-0.5 rounded-full
                                         bg-accent-400" />
                          )}
                          <Icon size={17} className={clsx('shrink-0',
                            isActive ? 'text-accent-400' : 'text-gray-500 group-hover:text-gray-300')} />
                          {expanded && <span className="truncate">{label}</span>}
                        </>
                      )}
                    </NavLink>
                  </li>
                ))}
              </ul>
            </div>
          ))}
        </nav>
      )}

      {locked && expanded && (
        <p className="mx-3 mb-2 rounded-lg border border-gray-800 bg-gray-950/60 px-3 py-2
                      text-xs leading-relaxed text-gray-400" role="status">
          Other pages are closed while your examination is running.
        </p>
      )}

      {/* Who is signed in, sign out, theme. */}
      <div className="shrink-0 border-t border-gray-800 p-2">
        {user && (
          expanded ? (
            <div className="flex items-center gap-2.5 px-2 py-2">
              <span aria-hidden="true" className="flex h-8 w-8 shrink-0 items-center
                justify-center rounded-md border border-accent-800/70 bg-accent-950/60
                font-mono text-xs font-semibold text-accent-300">
                {user.username?.[0]?.toUpperCase()}
              </span>
              <div className="min-w-0">
                <p className="truncate font-mono text-xs font-medium text-gray-100"
                  title={user.username}>{user.username}</p>
                <p className="truncate text-[11px] text-gray-500"
                  title={showAdmin ? roleLabel(user.role) : user.email}>
                  {showAdmin ? roleLabel(user.role) : user.email}
                </p>
              </div>
            </div>
          ) : (
            <div className="flex justify-center py-2" title={user.username}>
              <span className="flex h-8 w-8 items-center justify-center rounded-md border
                border-accent-800/70 bg-accent-950/60 font-mono text-xs font-semibold
                text-accent-300">
                {user.username?.[0]?.toUpperCase()}
                <span className="sr-only">Signed in as {user.username}</span>
              </span>
            </div>
          )
        )}
        <div className={clsx('flex gap-1', expanded ? 'flex-row' : 'flex-col items-center')}>
          <button
            type="button"
            onClick={() => logout.mutate()}
            title="Sign out"
            className={clsx('flex items-center gap-2.5 rounded-lg text-sm text-gray-400',
              'transition-colors hover:bg-red-950/40 hover:text-red-300',
              expanded ? 'flex-1 px-2.5 py-2' : 'h-9 w-9 justify-center')}
          >
            <LogOut size={17} className="shrink-0" />
            {expanded ? <span>Sign out</span> : <span className="sr-only">Sign out</span>}
          </button>
          <ThemeToggle className={expanded ? '' : 'h-9 w-9 justify-center'} />
        </div>
      </div>
    </aside>
  )
})
