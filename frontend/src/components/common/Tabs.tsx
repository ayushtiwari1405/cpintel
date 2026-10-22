import { useRef, type KeyboardEvent, type ReactNode } from 'react'
import { clsx } from 'clsx'

export interface TabDef<K extends string> {
  key: K
  label: ReactNode
  /** A count or marker shown after the label. */
  badge?: ReactNode
}

/** The ids that tie a tab to its panel; use panelProps() on the element the tab shows. */
export const tabId = (base: string, key: string) => `${base}-tab-${key}`
export const panelId = (base: string, key: string) => `${base}-panel-${key}`
export function panelProps(base: string, key: string) {
  return { role: 'tabpanel' as const, id: panelId(base, key), 'aria-labelledby': tabId(base, key), tabIndex: 0 }
}

/**
 * A row of tabs that behaves like one for keyboard and screen-reader users: a tablist with the
 * selected tab announced, one tab stop for the whole row, and the arrow, Home and End keys to
 * move along it (the standard ARIA tabs pattern, selection following focus).
 */
export function Tabs<K extends string>({ id, tabs, value, onChange, label, className }: {
  /** Stable prefix for the ids; unique on the page. */
  id: string
  tabs: TabDef<K>[]
  value: K
  onChange: (key: K) => void
  /** What the tabs choose between, for assistive technology. */
  label: string
  className?: string
}) {
  const refs = useRef<(HTMLButtonElement | null)[]>([])

  const onKeyDown = (e: KeyboardEvent<HTMLButtonElement>, i: number) => {
    const last = tabs.length - 1
    const next = e.key === 'ArrowRight' ? (i === last ? 0 : i + 1)
      : e.key === 'ArrowLeft' ? (i === 0 ? last : i - 1)
      : e.key === 'Home' ? 0 : e.key === 'End' ? last : null
    if (next == null) return
    e.preventDefault()
    onChange(tabs[next].key)
    refs.current[next]?.focus()
  }

  return (
    <div role="tablist" aria-label={label}
      className={clsx('flex max-w-full gap-1 overflow-x-auto border-b border-gray-800', className)}>
      {tabs.map((t, i) => {
        const selected = t.key === value
        return (
          <button
            key={t.key}
            ref={el => { refs.current[i] = el }}
            type="button"
            role="tab"
            id={tabId(id, t.key)}
            aria-selected={selected}
            aria-controls={panelId(id, t.key)}
            tabIndex={selected ? 0 : -1}
            onClick={() => onChange(t.key)}
            onKeyDown={e => onKeyDown(e, i)}
            className={clsx(
              '-mb-px flex shrink-0 items-center gap-1.5 whitespace-nowrap border-b-2 px-3 py-2',
              'text-sm font-medium transition-colors',
              selected
                ? 'border-accent-400 text-gray-50'
                : 'border-transparent text-gray-400 hover:border-gray-600 hover:text-gray-200',
            )}
          >
            {t.label}
            {t.badge != null && (
              <span className={clsx('rounded-sm px-1.5 font-mono text-[11px] tabular-nums',
                selected ? 'bg-accent-400/15 text-accent-300' : 'bg-gray-800 text-gray-400')}>
                {t.badge}
              </span>
            )}
          </button>
        )
      })}
    </div>
  )
}
