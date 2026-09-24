/**
 * Safe bridge to Electron APIs.
 * Returns null when running in the browser — all callers must handle that.
 */

/** What the desktop app reports about the contest monitor. Mirrors LockdownState in main. */
export interface LockdownState {
  engaged: boolean
  /** What engaged it — a contest name, shown back to the user. */
  reason: string | null
  since: number | null
  /** True right now, while the contest window does not have focus. */
  away: boolean
  /** Every time focus left, however briefly. */
  focusLosses: number
  /** Only the absences that ran past the warning threshold. */
  longAbsences: number
  /** Total time away, including an absence still running. */
  awayMs: number
  /** How long the current absence has run, or 0 when present. */
  currentAwayMs: number
  /** Warnings actually shown to the contestant. */
  warnings: number
  clipboardWipes: number
  /** Navigations and window-opens refused. */
  blocked: number
  /** How long someone may be away before being warned, so the UI quotes the same number. */
  warnAfterMs: number
}

/**
 * What an examination asks the desktop client to do while it is being sat.
 *
 * Mirrors LockdownPolicy in the desktop build. Every field is a request rather than a
 * guarantee: the client applies what the operating system allows and reports what it could
 * not, and the browser build applies almost none of it.
 */
export interface LockdownPolicyRequest {
  awayWarnMs: number
  restrictWindowSwitching: boolean
  blockNavigation: boolean
  blockExternalApps: boolean
  detectAppTermination: boolean
  clipboardGuard: boolean
}

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
      openExternal: (url: string, allowedInLockdown?: boolean) => Promise<void>
      cf: {
        connect: (apiBase: string, token: string, handle?: string)
          => Promise<{ connected: boolean; handle?: string; error?: string }>
        forget: () => Promise<void>
        /** A codeforces.com request made with the app's own Codeforces session. */
        fetch?: (request: { method?: 'GET' | 'POST'; url: string; form?: Record<string, string> })
          => Promise<{ status: number; url: string; body: string } | { error: string }>
      }
      lockdown: {
        engage:   (reason: string, policy?: Partial<LockdownPolicyRequest>)
          => Promise<LockdownState>
        release:  () => Promise<LockdownState>
        getState: () => Promise<LockdownState>
        onChange: (callback: (state: LockdownState) => void) => () => void
      }
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

/**
 * Opens a link outside the app.
 *
 * `allowedInLockdown` marks the handful of links that stay open during a contest — the problem
 * on the judge's own site, which a contestant is entitled to read. Everything else is refused
 * by the main process while the lock is engaged, because a browser window is the shortest path
 * around everything else the lock is doing.
 */
export const openExternal = (url: string, allowedInLockdown = false): void => {
  if (isDesktop()) {
    window.cpintelDesktop!.openExternal(url, allowedInLockdown)
  } else {
    window.open(url, '_blank', 'noopener,noreferrer')
  }
}

/**
 * Connecting Codeforces, in the desktop app only.
 *
 * <p>The web build cannot do this and never will: a page cannot read an HttpOnly cookie
 * belonging to codeforces.com, which is why the browser needs the helper process or a pasted
 * header. The desktop app is itself a browser, so it signs in to Codeforces in a window of its
 * own and reads the session it just created. Callers branch on {@link isDesktop}.
 *
 * <p>Resolves to the connected handle, or throws with what went wrong — a closed window and a
 * session Codeforces would not vouch for are both ordinary, and both need saying out loud.
 */
export const desktopCf = {
  connect: async (apiBase: string, token: string, handle?: string): Promise<string> => {
    if (!isDesktop()) throw new Error('Not running in the desktop app.')
    const res = await window.cpintelDesktop!.cf.connect(apiBase, token, handle)
    if (!res.connected) throw new Error(res.error || 'Could not connect Codeforces.')
    return res.handle ?? ''
  },

  /** Forget the stored Codeforces sign-in, so the next connect starts from a fresh window. */
  forget: async (): Promise<void> => {
    if (isDesktop()) await window.cpintelDesktop!.cf.forget()
  },
}

/**
 * The contest monitor, or a no-op in the browser.
 *
 * Every method is safe to call anywhere: the web build simply has nothing to lock, and callers
 * should not have to branch on which build they are in.
 */
export const desktopLockdown = {
  engage: async (reason: string,
                 policy?: Partial<LockdownPolicyRequest>): Promise<LockdownState | null> =>
    isDesktop() ? window.cpintelDesktop!.lockdown.engage(reason, policy) : null,

  release: async (): Promise<LockdownState | null> =>
    isDesktop() ? window.cpintelDesktop!.lockdown.release() : null,

  getState: async (): Promise<LockdownState | null> =>
    isDesktop() ? window.cpintelDesktop!.lockdown.getState() : null,

  onChange: (callback: (state: LockdownState) => void): (() => void) =>
    isDesktop() ? window.cpintelDesktop!.lockdown.onChange(callback) : () => {},
}
