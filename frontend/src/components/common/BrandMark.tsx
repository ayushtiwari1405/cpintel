import { clsx } from 'clsx'

/**
 * CPIntel's mark: a prompt chevron and a cursor. The same drawing as public/favicon.svg, inline
 * so it follows the theme (the chevron is the accent, the cursor the primary text colour).
 */
export function BrandMark({ className }: { className?: string }) {
  return (
    <svg viewBox="0 0 32 32" fill="none" aria-hidden="true"
      className={clsx('h-8 w-8 flex-shrink-0', className)}>
      <rect x="0.5" y="0.5" width="31" height="31" rx="6.5"
        className="fill-gray-900 stroke-gray-700" />
      <path d="M9 10.5 14.5 16 9 21.5" strokeWidth="2.6" strokeLinecap="round"
        strokeLinejoin="round" className="stroke-accent-400" />
      <path d="M17 21.5h6.5" strokeWidth="2.6" strokeLinecap="round" className="stroke-gray-200" />
    </svg>
  )
}

/** The mark with the product name beside it. */
export function BrandLockup({ className }: { className?: string }) {
  return (
    <span className={clsx('flex items-center gap-2.5', className)}>
      <BrandMark />
      <span className="text-[15px] font-semibold tracking-tight text-gray-50">
        CP<span className="text-accent-400">Intel</span>
      </span>
    </span>
  )
}
