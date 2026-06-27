import { contextBridge, ipcRenderer } from 'electron'

contextBridge.exposeInMainWorld('cpintelDesktop', {
  // App info
  getVersion:  () => ipcRenderer.invoke('app:version'),
  getPlatform: () => ipcRenderer.invoke('app:platform'),

  // Persistent store (replaces localStorage for desktop)
  store: {
    get: (key: string)                    => ipcRenderer.invoke('store:get', key),
    set: (key: string, value: unknown)    => ipcRenderer.invoke('store:set', key, value),
  },

  // Shell
  openExternal: (url: string) => ipcRenderer.invoke('shell:openExternal', url),

  // Flag so the React app knows it's running in Electron
  isDesktop: true,
})
