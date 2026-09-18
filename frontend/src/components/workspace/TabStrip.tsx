import { clsx } from 'clsx'
import type { LucideIcon } from 'lucide-react'
import type { ReactNode } from 'react'

export interface TabDef {
  id: string
  label: string
  icon?: LucideIcon
  /** A count or dot rendered after the label — submissions pending, cases failing. */
  badge?: ReactNode
}

interface Props {
  tabs: TabDef[]
  active: string
  onChange: (id: string) => void
  /** Controls that belong to the pane rather than to a tab: language, Run, Submit. */
  right?: ReactNode
  className?: string
}

/**
 * The header every pane in the workspace wears.
 *
 * One row, fixed height, tabs on the left and the pane's own controls on the right. Keeping
 * both in the same strip is what buys the editor its vertical space — the thing there is
 * never enough of on a problem-solving screen.
 */
export function TabStrip({ tabs, active, onChange, right, className }: Props) {
  return (
    <div className={clsx(
      'flex items-center gap-1 h-10 px-2 flex-shrink-0',
      'border-b border-gray-800 bg-gray-900',
      className
    )}>
      {/* The tabs never shrink; the controls do. Squeezing a tab label instead would clip
          the one word on the strip that says what you are looking at. */}
      <div className="flex items-center gap-0.5 flex-shrink-0">
        {tabs.map(tab => {
          const Icon = tab.icon
          const isActive = tab.id === active
          return (
            <button
              key={tab.id}
              onClick={() => onChange(tab.id)}
              aria-current={isActive ? 'page' : undefined}
              className={clsx(
                'flex items-center gap-1.5 px-2.5 py-1.5 rounded-md text-xs whitespace-nowrap',
                'transition-colors',
                isActive
                  ? 'bg-gray-800 text-gray-100 font-medium'
                  : 'text-gray-500 hover:text-gray-300 hover:bg-gray-800/50'
              )}
            >
              {Icon && <Icon size={13} className="flex-shrink-0" />}
              {tab.label}
              {tab.badge != null && tab.badge}
            </button>
          )
        })}
      </div>

      {right && (
        <div className="ml-auto flex min-w-0 items-center justify-end gap-1.5">{right}</div>
      )}
    </div>
  )
}
