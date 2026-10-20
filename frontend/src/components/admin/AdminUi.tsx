import { clsx } from 'clsx'
import { Stat } from '@/components/common/Stat'
import { formatDistanceToNow } from 'date-fns'
import { ChevronLeft, ChevronRight } from 'lucide-react'
import type { ReactNode } from 'react'

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
      'inline-flex items-center gap-1 whitespace-nowrap rounded border px-1.5 py-0.5 text-[11px]',
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
