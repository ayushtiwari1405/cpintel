import { clsx } from 'clsx'
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

export function StatCard({ label, value, hint, tone = 'default' }: {
  label: string
  value: ReactNode
  hint?: string
  tone?: 'default' | 'warn' | 'danger' | 'good'
}) {
  return (
    <div className="rounded-xl border border-gray-800 bg-gray-900 p-4">
      <p className="text-xs text-gray-500">{label}</p>
      <p className={clsx('mt-1 text-2xl font-semibold tabular-nums', {
        'text-gray-100':  tone === 'default',
        'text-amber-400': tone === 'warn',
        'text-red-400':   tone === 'danger',
        'text-green-400': tone === 'good',
      })}>
        {value}
      </p>
      {hint && <p className="mt-1 text-xs text-gray-600">{hint}</p>}
    </div>
  )
}

export function Panel({ title, description, actions, children }: {
  title: string
  description?: string
  actions?: ReactNode
  children: ReactNode
}) {
  return (
    <section className="rounded-xl border border-gray-800 bg-gray-900">
      <header className="flex items-start justify-between gap-4 border-b border-gray-800 px-4 py-3">
        <div>
          <h2 className="text-sm font-medium text-gray-200">{title}</h2>
          {description && <p className="mt-0.5 text-xs text-gray-500">{description}</p>}
        </div>
        {actions}
      </header>
      {children}
    </section>
  )
}

export function Pill({ children, tone = 'gray' }: {
  children: ReactNode
  tone?: 'gray' | 'green' | 'red' | 'indigo' | 'amber'
}) {
  return (
    <span className={clsx(
      'inline-flex items-center rounded-full px-2 py-0.5 text-[11px] font-medium',
      {
        'bg-gray-800 text-gray-400':        tone === 'gray',
        'bg-green-900/40 text-green-400':   tone === 'green',
        'bg-red-900/40 text-red-400':       tone === 'red',
        'bg-indigo-900/40 text-indigo-300': tone === 'indigo',
        'bg-amber-900/40 text-amber-400':   tone === 'amber',
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

export function actionTone(action: string): 'gray' | 'green' | 'red' | 'indigo' | 'amber' {
  if (action === 'LOGIN_FAILED') return 'red'
  if (action === 'LOGIN' || action === 'REGISTER') return 'green'
  if (action.startsWith('ADMIN_')) return 'indigo'
  return 'gray'
}

/** Timestamps as "3 hours ago", with the exact value one hover away. */
export function Ago({ at, fallback = '—' }: { at: string | null; fallback?: string }) {
  if (!at) return <span className="text-gray-600">{fallback}</span>
  const date = new Date(at)
  if (Number.isNaN(date.getTime())) return <span className="text-gray-600">{fallback}</span>
  return (
    <span title={date.toLocaleString()}>
      {formatDistanceToNow(date, { addSuffix: true })}
    </span>
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
    <div className="flex items-center justify-between border-t border-gray-800 px-4 py-2.5">
      <p className="text-xs text-gray-500">
        {total.toLocaleString()} {total === 1 ? 'entry' : 'entries'}
        {totalPages > 1 && ` · page ${page + 1} of ${totalPages}`}
      </p>
      {totalPages > 1 && (
        <div className="flex gap-1">
          <button
            onClick={() => onPage(page - 1)}
            disabled={page <= 0}
            className="rounded-md border border-gray-800 p-1.5 text-gray-400 hover:bg-gray-800
                       hover:text-gray-200 disabled:opacity-30 disabled:hover:bg-transparent"
            aria-label="Previous page"
          >
            <ChevronLeft size={14} />
          </button>
          <button
            onClick={() => onPage(page + 1)}
            disabled={page >= totalPages - 1}
            className="rounded-md border border-gray-800 p-1.5 text-gray-400 hover:bg-gray-800
                       hover:text-gray-200 disabled:opacity-30 disabled:hover:bg-transparent"
            aria-label="Next page"
          >
            <ChevronRight size={14} />
          </button>
        </div>
      )}
    </div>
  )
}

export function EmptyRow({ children, colSpan }: { children: ReactNode; colSpan: number }) {
  return (
    <tr>
      <td colSpan={colSpan} className="px-4 py-10 text-center text-sm text-gray-600">
        {children}
      </td>
    </tr>
  )
}
