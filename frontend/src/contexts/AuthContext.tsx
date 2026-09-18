import React, { createContext, useContext } from 'react'
import { useAuthStore } from '@/store/authStore'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { userApi } from '@/api/userApi'
import type { User } from '@/types'

interface AuthContextValue {
  user: User | null
  isAuthenticated: boolean
  logout: () => void
}

const AuthContext = createContext<AuthContextValue | null>(null)

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const {
    user, isAuthenticated,
    logout: storeLogout,
    setUser, accessToken
  } = useAuthStore()

  const queryClient = useQueryClient()

  // Re-read the profile from the server whenever there is a token — not only when the stored
  // one is missing.
  //
  // The role lives in this object, and the stored copy is only ever a cache of what the server
  // last said. Trusting it until it happened to be absent meant a role change never reached a
  // session that already had one: promote someone and they saw nothing new until they signed
  // out; demote someone and they kept the console until their token expired. Refetching here
  // makes the server's answer the one that decides what gets drawn.
  //
  // It is still only presentation. Every /api/admin route checks the role in the token, so a
  // stale or hand-edited profile buys screens that answer 403, never data.
  useQuery({
    queryKey: ['me'],
    queryFn: () => userApi.getMe().then(r => {
      if (r.data) setUser(r.data)
      return r.data
    }),
    enabled: !!accessToken,
    retry: false,
    // The window regains focus far more often than a role changes; once per mount is the
    // point at which a promotion or demotion needs to land.
    refetchOnWindowFocus: false,
    staleTime: 5 * 60 * 1000,
  })

  const logout = () => {
    storeLogout()
    queryClient.clear()
  }

  return (
    <AuthContext.Provider value={{ user, isAuthenticated, logout }}>
      {children}
    </AuthContext.Provider>
  )
}

export function useAuth() {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth must be used within AuthProvider')
  return ctx
}
