import React, { createContext, useContext, useEffect } from 'react'
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

  // Hydrate user from API on mount if we have a token but lost state
  useQuery({
    queryKey: ['me'],
    queryFn: () => userApi.getMe().then(r => {
      if (r.data) setUser(r.data)
      return r.data
    }),
    enabled: !!accessToken && !user,
    retry: false,
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
