/**
 * Safe bridge to Electron APIs.
 * Returns null when running in the browser — all callers must handle that.
 */

declare global {
  interface Window {
    cpintelDesktop?: {
      getVersion:   () => Promise<string>
      getPlatform:  () => Promise<string>
      isDesktop:    boolean
      store: {
        get: (key: string) => Promise<unknown>
        set: (key: string, value: unknown) => Promise<void>
      }
      openExternal: (url: string) => Promise<void>
    }
  }
}

export const isDesktop = (): boolean =>
  typeof window !== 'undefined' && !!window.cpintelDesktop?.isDesktop

export const desktopStore = {
  get: async <T>(key: string, fallback: T): Promise<T> => {
    if (!isDesktop()) return fallback
    const val = await window.cpintelDesktop!.store.get(key)
    return val !== undefined ? (val as T) : fallback
  },
  set: async (key: string, value: unknown): Promise<void> => {
    if (!isDesktop()) return
    await window.cpintelDesktop!.store.set(key, value)
  },
}

export const openExternal = (url: string): void => {
  if (isDesktop()) {
    window.cpintelDesktop!.openExternal(url)
  } else {
    window.open(url, '_blank', 'noopener,noreferrer')
  }
}
