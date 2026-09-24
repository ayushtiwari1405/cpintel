import { create } from 'zustand'
import { persist, createJSONStorage } from 'zustand/middleware'
import type { User } from '@/types'
import type { SessionMode } from '@/api/authApi'

/**
 * Who is signed in, and the short-lived access token.
 *
 * <p>Only the user and the signed-in flag are persisted. The access token lives in memory for its
 * fifteen minutes; the refresh token is an HttpOnly cookie the server sets, which no script on
 * the page can read. Both used to sit in localStorage, where anything that ever ran on the page
 * could take a week-long login. After a reload the first request finds no token, gets a 401,
 * and the client renews from the cookie (api/client.ts) — which is also how the app notices a
 * session that has ended.
 */
interface AuthState {
  user: User | null
  accessToken: string | null
  isAuthenticated: boolean
  /**
   * Which password signed this session in. EXAM means the examination password on a slip: the
   * app shows that one paper and nothing else (the server holds the session to it regardless).
   */
  mode: SessionMode
  /** The paper an EXAM session is for. */
  examId: number | null
  setUser: (user: User) => void
  setAccessToken: (access: string) => void
  setSession: (mode: SessionMode, examId: number | null) => void
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
      isAuthenticated: false,
      mode:            'NORMAL',
      examId:          null,

      setSession: (mode, examId) =>
        set({ mode, examId: mode === 'EXAM' ? examId : null }),

      setUser: (user) =>
        set({ user, isAuthenticated: true }),

      setAccessToken: (accessToken) =>
        set({ accessToken }),

      logout: () =>
        set({
          user:            null,
          accessToken:     null,
          isAuthenticated: false,
          mode:            'NORMAL',
          examId:          null,
        }),
    }),
    {
      name: 'cpintel-auth',
      storage,
      // Tokens are deliberately not here — see above. Bumped so tokens stored by an older
      // build are dropped rather than read back.
      version: 3,
      migrate: (state: any) => ({
        user: state?.user ?? null,
        isAuthenticated: !!state?.isAuthenticated,
        mode: state?.mode === 'EXAM' ? 'EXAM' : 'NORMAL',
        examId: state?.examId ?? null,
      }),
      partialize: (s) => ({
        user:            s.user,
        isAuthenticated: s.isAuthenticated,
        mode:            s.mode,
        examId:          s.examId,
      }),
    }
  )
)
