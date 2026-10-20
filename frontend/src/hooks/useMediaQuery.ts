import { useSyncExternalStore } from 'react'

/**
 * Whether a CSS media query matches, kept in step as the window changes.
 *
 * The shell needs this in script rather than only in CSS because the same sidebar is a fixed
 * column on a wide screen and a dismissable drawer on a narrow one, and only the drawer traps
 * the page behind it.
 */
export function useMediaQuery(query: string): boolean {
  return useSyncExternalStore(
    (onChange) => {
      const list = window.matchMedia(query)
      list.addEventListener('change', onChange)
      return () => list.removeEventListener('change', onChange)
    },
    () => window.matchMedia(query).matches,
    () => false,
  )
}

/** The width at which the sidebar stops being a drawer: Tailwind's `lg`. */
export const WIDE = '(min-width: 1024px)'
