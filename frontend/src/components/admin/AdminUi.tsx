import { clsx } from 'clsx'
import { Stat } from '@/components/common/Stat'
import { formatDistanceToNow } from 'date-fns'
import { ChevronLeft, ChevronRight, Plus, type LucideIcon } from 'lucide-react'
import type { ReactNode } from 'react'
import { Link } from 'react-router-dom'

/**
 * The small pieces every admin screen uses.
 *
 * Kept together so the four pages agree on what a figure, a state pill and a pager look like —
 * a console where the same thing is drawn three ways is one where you stop trusting what you
 * are reading.
 */

/** A headline number in the console; the same component as everywhere else in the app. */
export function StatCard({ label, value, hint, tone = 'default' }: {
  label: string
  value: ReactNode
  hint?: string
  tone?: 'default' | 'warn' | 'danger' | 'good'
}) {
  return <Stat label={label} value={value} hint={hint} tone={tone}
    className="rounded-xl border border-gray-800" />
}

export function Panel({ title, description, actions, children }: {
  title: string
  description?: string
  actions?: ReactNode
  children: ReactNode
}) {
  return (
    <section className="rounded-xl border border-gray-800 bg-gray-900">
      <header className="flex flex-wrap items-start justify-between gap-x-4 gap-y-2 border-b
                         border-gray-800 px-4 py-3">
        <div className="min-w-0">
          <h2 className="text-sm font-medium text-gray-100">{title}</h2>
          {description && <p className="mt-0.5 text-xs text-gray-500">{description}</p>}
        </div>
        {actions && <div className="flex flex-wrap items-center gap-2">{actions}</div>}
      </header>
      {children}
    </section>
  )
}

export function Pill({ children, tone = 'gray' }: {
  children: ReactNode
  tone?: 'gray' | 'green' | 'red' | 'accent' | 'amber'
}) {
  return (
    // The same shape as the .badge-* classes elsewhere: one vocabulary for status app-wide.
    <span className={clsx(
      'inline-flex items-center gap-1 whitespace-nowrap rounded-sm border px-1.5 py-0.5 text-[11px]',
      'font-medium leading-4',
      {
        'border-gray-700 bg-gray-850 text-gray-300':              tone === 'gray',
        'border-green-800/70 bg-green-950/60 text-green-300':     tone === 'green',
        'border-red-800/70 bg-red-950/60 text-red-300':           tone === 'red',
        'border-accent-800/70 bg-accent-950/60 text-accent-300':  tone === 'accent',
        'border-amber-800/70 bg-amber-950/60 text-amber-300':     tone === 'amber',
      }
    )}>
      {children}
    </span>
  )
}

/** A stored action name, as something readable: ADMIN_ROLE_CHANGED -> Role changed. */
export function actionLabel(action: string) {
  const words = action.replace(/^ADMIN_/, '').replace(/_/g, ' ').toLowerCase()
  return words.charAt(0).toUpperCase() + words.slice(1)
}

export function actionTone(action: string): 'gray' | 'green' | 'red' | 'accent' | 'amber' {
  if (action === 'LOGIN_FAILED') return 'red'
  if (action === 'LOGIN' || action === 'REGISTER') return 'green'
  if (action.startsWith('ADMIN_')) return 'accent'
  return 'gray'
}

/** Timestamps as "3 hours ago", with the exact value one hover away. */
export function Ago({ at, fallback = '—' }: { at: string | null; fallback?: string }) {
  if (!at) return <span className="text-gray-500">{fallback}</span>
  const date = new Date(at)
  if (Number.isNaN(date.getTime())) return <span className="text-gray-500">{fallback}</span>
  return (
    <time dateTime={date.toISOString()} title={date.toLocaleString()} className="whitespace-nowrap">
      {formatDistanceToNow(date, { addSuffix: true })}
    </time>
  )
}

export function bytes(value: number) {
  if (value < 1024) return `${value} B`
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`
  return `${(value / (1024 * 1024)).toFixed(1)} MB`
}

export function Pager({ page, totalPages, total, onPage }: {
  page: number
  totalPages: number
  total: number
  onPage: (page: number) => void
}) {
  if (total === 0) return null
  return (
    <nav aria-label="Pages" className="flex items-center justify-between gap-3 border-t border-gray-800 px-4 py-2.5">
      <p className="text-xs text-gray-500">
        <span className="font-mono tabular-nums text-gray-300">{total.toLocaleString()}</span>
        {' '}{total === 1 ? 'entry' : 'entries'}
        {totalPages > 1 && <> · page <span className="font-mono tabular-nums text-gray-300">{page + 1}</span>
          {' '}of <span className="font-mono tabular-nums">{totalPages}</span></>}
      </p>
      {totalPages > 1 && (
        <div className="flex gap-1">
          <button
            type="button"
            onClick={() => onPage(page - 1)}
            disabled={page <= 0}
            className="flex h-8 w-8 items-center justify-center rounded-md border border-gray-700
                       text-gray-300 hover:bg-gray-800 hover:text-gray-50 disabled:cursor-not-allowed
                       disabled:opacity-30 disabled:hover:bg-transparent"
            aria-label="Previous page"
          >
            <ChevronLeft size={14} />
          </button>
          <button
            type="button"
            onClick={() => onPage(page + 1)}
            disabled={page >= totalPages - 1}
            className="flex h-8 w-8 items-center justify-center rounded-md border border-gray-700
                       text-gray-300 hover:bg-gray-800 hover:text-gray-50 disabled:cursor-not-allowed
                       disabled:opacity-30 disabled:hover:bg-transparent"
            aria-label="Next page"
          >
            <ChevronRight size={14} />
          </button>
        </div>
      )}
    </nav>
  )
}

export function EmptyRow({ children, colSpan }: { children: ReactNode; colSpan: number }) {
  return (
    <tr>
      <td colSpan={colSpan} className="px-4 py-10 text-center text-sm text-gray-500">
        {children}
      </td>
    </tr>
  )
}

type TileTone = 'gray' | 'green' | 'amber' | 'accent' | 'red'

const COVER: Record<TileTone, string> = {
  gray:   'border-gray-800 bg-gray-850 text-gray-500',
  green:  'border-green-900/60 bg-green-950/40 text-green-400',
  amber:  'border-amber-900/60 bg-amber-950/40 text-amber-400',
  accent: 'border-accent-900/60 bg-accent-950/40 text-accent-400',
  red:    'border-red-900/60 bg-red-950/40 text-red-400',
}

/**
 * The console's lists of things to open — classrooms, labs, examinations, teams — as a grid of
 * tiles rather than page-wide rows, so a dozen fit on one screen and each is one click away.
 * Fills the row with as many ~15rem columns as fit; one column on a phone.
 */
export function TileGrid({ children, label, className }: {
  children: ReactNode
  label: string
  className?: string
}) {
  return (
    <ul aria-label={label}
      className={clsx('grid grid-cols-[repeat(auto-fill,minmax(15rem,1fr))] gap-x-4 gap-y-5', className)}>
      {children}
    </ul>
  )
}

/**
 * One tile: a tinted cover (no picture — an icon, what kind of thing it is, its state, and one
 * headline figure in the corner the way a video shows its length), then the title and up to a
 * few lines of detail underneath. The whole tile is the link.
 */
export function Tile({ to, icon: Icon, kind, tone = 'gray', status, corner, title, meta }: {
  to: string
  icon: LucideIcon
  /** What it is, top-left on the cover: "Lab", "Exam", "Team". */
  kind: string
  tone?: TileTone
  /** Top-right on the cover, usually a <Pill>. */
  status?: ReactNode
  /** Bottom-right on the cover: the one figure worth seeing first. */
  corner?: ReactNode
  title: ReactNode
  meta?: ReactNode[]
}) {
  return (
    <li className="min-w-0">
      <Link to={to}
        className="group block rounded-xl outline-hidden focus-visible:ring-2
                   focus-visible:ring-accent-500/60 focus-visible:ring-offset-2
                   focus-visible:ring-offset-gray-950">
        <div className={clsx('relative flex h-28 items-center justify-center overflow-hidden rounded-xl',
          'border transition-colors group-hover:border-accent-600', COVER[tone])}>
          <Icon size={34} strokeWidth={1.4} aria-hidden="true"
            className="opacity-70 transition-transform duration-200 group-hover:scale-110
                       motion-reduce:transition-none motion-reduce:group-hover:scale-100" />
          <span className="absolute left-2 top-2 text-[10px] font-semibold uppercase tracking-wider
                           text-gray-400">
            {kind}
          </span>
          {status && <span className="absolute right-2 top-2">{status}</span>}
          {corner != null && (
            <span className="absolute bottom-2 right-2 rounded-sm bg-black/75 px-1.5 py-0.5
                             font-mono text-[11px] font-medium tabular-nums text-white">
              {corner}
            </span>
          )}
        </div>
        <div className="px-0.5 pt-2.5">
          <h3 className="line-clamp-2 text-sm font-medium leading-snug text-gray-100
                         group-hover:text-accent-300">
            {title}
          </h3>
          {meta?.filter(Boolean).map((line, i) => (
            <p key={i} className="mt-0.5 truncate text-xs text-gray-500">{line}</p>
          ))}
        </div>
      </Link>
    </li>
  )
}

/** The "make another" tile at the start of a grid: same footprint, dashed, no detail lines. */
export function NewTile({ label, to, onClick }: {
  label: string
  to?: string
  onClick?: () => void
}) {
  const body = (
    <span className="flex h-28 flex-col items-center justify-center gap-1.5 rounded-xl border
                     border-dashed border-gray-700 text-sm text-gray-400 transition-colors
                     group-hover:border-accent-600 group-hover:text-accent-300">
      <Plus size={20} aria-hidden="true" />
      {label}
    </span>
  )
  const cls = 'group block w-full rounded-xl text-left outline-hidden focus-visible:ring-2 '
    + 'focus-visible:ring-accent-500/60'
  return (
    <li className="min-w-0">
      {to
        ? <Link to={to} className={cls}>{body}</Link>
        : <button type="button" onClick={onClick} className={cls}>{body}</button>}
    </li>
  )
}

/**
 * A titled row of tiles on a page, the way a video site groups its shelves: a heading with a
 * count, an optional action on the right, the grid below. Lighter than a <Panel>, which stays
 * for forms and tables.
 */
export function Shelf({ title, count, actions, children }: {
  title: string
  count?: number
  actions?: ReactNode
  children: ReactNode
}) {
  return (
    <section className="space-y-3">
      <header className="flex flex-wrap items-center justify-between gap-2">
        <h2 className="flex items-center gap-2 text-base font-semibold text-gray-100">
          {title}
          {count != null && (
            <span className="rounded-sm bg-gray-800 px-1.5 font-mono text-[11px] font-medium
                             tabular-nums text-gray-400">
              {count}
            </span>
          )}
        </h2>
        {actions && <div className="flex flex-wrap items-center gap-2">{actions}</div>}
      </header>
      {children}
    </section>
  )
}

/** A tile-sized note where tiles would be: loading, failed or nothing yet. */
export function TileNote({ children }: { children: ReactNode }) {
  return (
    <li className="flex h-28 items-center justify-center rounded-xl border border-gray-800
                   bg-gray-900 px-4 text-center text-xs text-gray-500">
      {children}
    </li>
  )
}

/**
 * What a "New …" tile does when its form is further down the same page: scroll the form into
 * view and put the cursor in its first field. Called after any state change that renders the
 * form, hence the frame's wait.
 */
export function revealForm(id: string) {
  requestAnimationFrame(() => {
    const el = document.getElementById(id)
    el?.scrollIntoView({ behavior: 'smooth', block: 'start' })
    el?.querySelector<HTMLElement>('input, select, button')?.focus({ preventScroll: true })
  })
}
