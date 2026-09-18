import { contextBridge, ipcRenderer, type IpcRendererEvent } from 'electron'

/** Mirrors LockdownState in lockdown.ts — kept structural so the two stay independent. */
interface LockdownState {
  engaged: boolean
  reason: string | null
  since: number | null
  away: boolean
  focusLosses: number
  longAbsences: number
  awayMs: number
  currentAwayMs: number
  warnings: number
  clipboardWipes: number
  blocked: number
  warnAfterMs: number
}

contextBridge.exposeInMainWorld('cpintelDesktop', {
  // App info
  getVersion:  () => ipcRenderer.invoke('app:version'),
  getPlatform: () => ipcRenderer.invoke('app:platform'),

  // Persistent store (replaces localStorage for desktop)
  store: {
    get: (key: string)                    => ipcRenderer.invoke('store:get', key),
    set: (key: string, value: unknown)    => ipcRenderer.invoke('store:set', key, value),
  },

  // Shell. The second argument marks a link the contest lockdown lets through — the judge's
  // own site, which a contestant is entitled to open. Main decides; this only asks.
  openExternal: (url: string, allowedInLockdown = false) =>
    ipcRenderer.invoke('shell:openExternal', url, allowedInLockdown),

  /**
   * Codeforces sign-in, in a window the app owns.
   *
   * The token goes to main because main is what posts the session to the backend; the cookie
   * never comes back this way, so the page cannot leak a credential it never holds.
   */
  cf: {
    connect: (apiBase: string, token: string, handle?: string) =>
      ipcRenderer.invoke('cf:connect', apiBase, token, handle),
    forget: () => ipcRenderer.invoke('cf:forget'),
  },

  // Contest lockdown
  lockdown: {
    engage:   (reason: string) => ipcRenderer.invoke('lockdown:engage', reason),
    release:  ()               => ipcRenderer.invoke('lockdown:release'),
    getState: ()               => ipcRenderer.invoke('lockdown:state'),

    /**
     * Subscribes to lockdown changes and returns its own unsubscribe.
     *
     * The listener is wrapped rather than handed over directly: passing the renderer's
     * function to ipcRenderer.off later requires the same reference, and giving the page a
     * raw IpcRendererEvent would leak a sender it has no business holding.
     */
    onChange: (callback: (state: LockdownState) => void) => {
      const listener = (_event: IpcRendererEvent, state: LockdownState) => callback(state)
      ipcRenderer.on('lockdown:changed', listener)
      return () => ipcRenderer.off('lockdown:changed', listener)
    },
  },

  // Flag so the React app knows it's running in Electron
  isDesktop: true,
})
