import type { ReactNode } from 'react'
import { clsx } from 'clsx'

interface Props {
  title: ReactNode
  /** One line under the title: what the page is for, or the record it shows. */
  description?: ReactNode
  /** Small context label above the title, e.g. the area of the console. */
  eyebrow?: ReactNode
  /** Buttons for the page as a whole, kept on the title's line on wide screens. */
  actions?: ReactNode
  /** Facts about the record (ids, status pills, dates) shown under the description. */
  meta?: ReactNode
  className?: string
}

/**
 * The top of every reading page: the page's one <h1>, so a screen reader has a heading to land
 * on, and the same size and spacing everywhere instead of each page choosing its own.
 */
export function PageHeader({ title, description, eyebrow, actions, meta, className }: Props) {
  return (
    <header className={clsx('mb-6 flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between',
      className)}>
      <div className="min-w-0">
        {eyebrow && (
          <p className="mb-1 text-[11px] font-medium uppercase tracking-[0.08em] text-accent-400">
            {eyebrow}
          </p>
        )}
        <h1 className="wrap-break-word text-xl font-semibold tracking-tight text-gray-50 sm:text-2xl">
          {title}
        </h1>
        {description && (
          <p className="mt-1 max-w-3xl text-sm text-gray-400">{description}</p>
        )}
        {meta && <div className="mt-2 flex flex-wrap items-center gap-2 text-xs">{meta}</div>}
      </div>
      {actions && (
        <div className="flex shrink-0 flex-wrap items-center gap-2">{actions}</div>
      )}
    </header>
  )
}

/** A titled block inside a page: a panel with a heading row and optional actions. */
export function Section({ title, icon, actions, children, className, bodyClassName }: {
  title: ReactNode
  icon?: ReactNode
  actions?: ReactNode
  children: ReactNode
  className?: string
  bodyClassName?: string
}) {
  return (
    <section className={clsx('rounded-xl border border-gray-800 bg-gray-900', className)}>
      <div className="flex items-center justify-between gap-3 border-b border-gray-800 px-4 py-3">
        <h2 className="flex min-w-0 items-center gap-2 text-sm font-medium text-gray-200">
          {icon && <span className="shrink-0 text-gray-500" aria-hidden="true">{icon}</span>}
          <span className="truncate">{title}</span>
        </h2>
        {actions && <div className="flex shrink-0 items-center gap-2 text-xs">{actions}</div>}
      </div>
      <div className={clsx('p-4', bodyClassName)}>{children}</div>
    </section>
  )
}
