import { useSyncExternalStore } from 'react'

/**
 * Where an examination's notices go: a place in the sidebar, shown only while the sidebar is open
 * on a wide screen. The paper renders its rules and notices into it, and above the paper when
 * there is none, so nothing the candidate must read is ever hidden in a closed sidebar.
 */
let slot: HTMLElement | null = null
const listeners = new Set<() => void>()

export function setExamSidebarSlot(el: HTMLElement | null) {
  if (slot === el) return
  slot = el
  listeners.forEach(l => l())
}

export function useExamSidebarSlot(): HTMLElement | null {
  return useSyncExternalStore(
    cb => { listeners.add(cb); return () => { listeners.delete(cb) } },
    () => slot,
  )
}
