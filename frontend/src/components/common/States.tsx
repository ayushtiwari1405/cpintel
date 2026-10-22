import type { ReactNode } from 'react'
import { AlertTriangle, Loader2, RotateCw } from 'lucide-react'
import { clsx } from 'clsx'

/** Something is on its way. Announced politely, so a screen reader hears it once. */
export function LoadingState({ label = 'Loading…', className }: { label?: string; className?: string }) {
  return (
    <div role="status" className={clsx('flex items-center justify-center gap-2.5 py-12 text-sm text-gray-500',
      className)}>
      <Loader2 size={18} className="animate-spin text-accent-400" aria-hidden="true" />
      <span>{label}</span>
    </div>
  )
}

/**
 * Nothing to show, and why. Says what is missing and, where there is one, the next step —
 * never a blank panel that could be read as broken.
 */
export function EmptyState({ icon, title, children, action, className, compact }: {
  icon?: ReactNode
  title: ReactNode
  children?: ReactNode
  action?: ReactNode
  className?: string
  compact?: boolean
}) {
  return (
    <div className={clsx('flex flex-col items-center text-center', compact ? 'py-6' : 'py-10',
      className)}>
      {icon && (
        <span aria-hidden="true" className="mb-3 flex h-10 w-10 items-center justify-center
          rounded-lg border border-gray-800 bg-gray-950 text-gray-500">
          {icon}
        </span>
      )}
      <p className="text-sm font-medium text-gray-200">{title}</p>
      {children && <div className="mt-1 max-w-sm text-sm text-gray-500">{children}</div>}
      {action && <div className="mt-4">{action}</div>}
    </div>
  )
}

/** The server's own words when a request failed, the error message otherwise. */
export function errorMessage(error: unknown, fallback = 'Something went wrong.'): string {
  const e = error as { response?: { data?: { message?: string } }; message?: string } | null
  return e?.response?.data?.message || e?.message || fallback
}

/**
 * A request failed. Says so instead of rendering as empty — "no contests" and "could not load
 * contests" are different facts — and offers the retry the query already supports.
 */
export function ErrorState({ title = 'Could not load this', error, onRetry, className }: {
  title?: ReactNode
  error?: unknown
  onRetry?: () => void
  className?: string
}) {
  return (
    <div role="alert" className={clsx('flex items-start gap-3 rounded-lg border border-red-900/60',
      'bg-red-950/30 px-4 py-3 text-sm', className)}>
      <AlertTriangle size={16} className="mt-0.5 shrink-0 text-red-400" aria-hidden="true" />
      <div className="min-w-0 flex-1">
        <p className="font-medium text-red-200">{title}</p>
        {error != null && <p className="mt-0.5 wrap-break-word text-red-300/80">{errorMessage(error)}</p>}
      </div>
      {onRetry && (
        <button type="button" onClick={onRetry} className="btn-secondary btn-sm shrink-0">
          <RotateCw size={12} aria-hidden="true" /> Retry
        </button>
      )}
    </div>
  )
}
