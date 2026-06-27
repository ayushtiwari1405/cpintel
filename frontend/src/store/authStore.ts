import { create } from 'zustand'
import { persist, createJSONStorage } from 'zustand/middleware'
import type { User } from '@/types'

interface AuthState {
  user: User | null
  accessToken: string | null
  refreshToken: string | null
  isAuthenticated: boolean
  setUser: (user: User) => void
  setTokens: (access: string, refresh: string) => void
  logout: () => void
}

// Use localStorage — works in both browser and Electron renderer
const storage = createJSONStorage(() => {
  try {
    return localStorage
  } catch {
    return {
      getItem:    () => null,
      setItem:    () => {},
      removeItem: () => {},
    }
  }
})

export const useAuthStore = create<AuthState>()(
  persist(
    (set) => ({
      user:            null,
      accessToken:     null,
      refreshToken:    null,
      isAuthenticated: false,

      setUser: (user) =>
        set({ user, isAuthenticated: true }),

      setTokens: (accessToken, refreshToken) =>
        set({ accessToken, refreshToken }),

      logout: () =>
        set({
          user:            null,
          accessToken:     null,
          refreshToken:    null,
          isAuthenticated: false,
        }),
    }),
    {
      name: 'cpintel-auth',
      storage,
      partialize: (s) => ({
        accessToken:     s.accessToken,
        refreshToken:    s.refreshToken,
        user:            s.user,
        isAuthenticated: s.isAuthenticated,
      }),
    }
  )
)
