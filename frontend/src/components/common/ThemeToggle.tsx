import { Moon, Sun } from 'lucide-react'
import { clsx } from 'clsx'
import { useTheme } from '@/contexts/ThemeContext'

interface Props {
  /** Show the "Light mode"/"Dark mode" label next to the icon. */
  showLabel?: boolean
  className?: string
}

export function ThemeToggle({ showLabel, className }: Props) {
  const { theme, toggleTheme } = useTheme()
  const next = theme === 'dark' ? 'light' : 'dark'
  const Icon = theme === 'dark' ? Sun : Moon

  return (
    <button
      type="button"
      onClick={toggleTheme}
      title={`Switch to ${next} mode`}
      aria-label={`Switch to ${next} mode`}
      className={clsx(
        'flex items-center gap-3 rounded-lg text-sm text-gray-500 transition-colors',
        'hover:text-gray-300 hover:bg-gray-800',
        showLabel !== undefined ? 'px-3 py-2.5 w-full' : 'p-2',
        className,
      )}
    >
      <Icon size={18} className="flex-shrink-0" />
      {showLabel && <span className="text-xs">{next === 'light' ? 'Light mode' : 'Dark mode'}</span>}
    </button>
  )
}
