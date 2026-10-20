import type { ReactNode } from 'react'
import { clsx } from 'clsx'

export type StatTone = 'default' | 'accent' | 'good' | 'warn' | 'danger'

const TONE: Record<StatTone, string> = {
  default: 'text-gray-50',
  accent:  'text-accent-400',
  good:    'text-green-400',
  warn:    'text-amber-400',
  danger:  'text-red-400',
}

/**
 * One headline number: a label, the value in tabular monospace so a column of them lines up,
 * and an optional line of context. Tone is a second signal, never the only one — the label and
 * hint say what the number means.
 */
export function Stat({ label, value, hint, tone = 'default', className }: {
  label: ReactNode
  value: ReactNode
  hint?: ReactNode
  tone?: StatTone
  className?: string
}) {
  return (
    <div className={clsx('min-w-0 bg-gray-900 p-4', className)}>
      <p className="truncate text-[11px] font-medium uppercase tracking-wide text-gray-500">{label}</p>
      <p className={clsx('mt-1 truncate font-mono text-2xl font-semibold tabular-nums', TONE[tone])}>
        {value}
      </p>
      {hint && <p className="mt-1 text-xs text-gray-500">{hint}</p>}
    </div>
  )
}

/**
 * A strip of Stats sharing one border, separated by hairlines rather than each in a card of its
 * own: they are read together, as a row of figures.
 */
export function Stats({ children, columns = 4, className }: {
  children: ReactNode
  columns?: 2 | 3 | 4
  className?: string
}) {
  return (
    <div className={clsx(
      'grid gap-px overflow-hidden rounded-xl border border-gray-800 bg-gray-800',
      columns === 2 && 'grid-cols-1 sm:grid-cols-2',
      columns === 3 && 'grid-cols-1 sm:grid-cols-3',
      columns === 4 && 'grid-cols-2 lg:grid-cols-4',
      className,
    )}>
      {children}
    </div>
  )
}
