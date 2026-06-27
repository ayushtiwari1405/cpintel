import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { authApi } from '@/api/authApi'
import { userApi } from '@/api/userApi'
import { useAuthStore } from '@/store/authStore'
import { useToast } from '@/components/common/Toaster'
import { useNavigate } from 'react-router-dom'

export function useLogin() {
  const { setTokens, setUser } = useAuthStore()
  const toast = useToast()
  const navigate = useNavigate()

  return useMutation({
    mutationFn: authApi.login,
    onSuccess: (res) => {
      setTokens(res.data.accessToken, res.data.refreshToken)
      setUser(res.data.user)
      navigate('/dashboard')
    },
    onError: (err: any) => {
      toast.push('error', err.response?.data?.message ?? 'Login failed')
    },
  })
}

export function useRegister() {
  const { setTokens, setUser } = useAuthStore()
  const toast = useToast()
  const navigate = useNavigate()

  return useMutation({
    mutationFn: authApi.register,
    onSuccess: (res) => {
      setTokens(res.data.accessToken, res.data.refreshToken)
      setUser(res.data.user)
      navigate('/dashboard')
      toast.push('success', 'Account created! Welcome to CPIntel.')
    },
    onError: (err: any) => {
      toast.push('error', err.response?.data?.message ?? 'Registration failed')
    },
  })
}

export function useLogout() {
  const { logout } = useAuthStore()
  const queryClient = useQueryClient()
  const navigate = useNavigate()

  return useMutation({
    mutationFn: authApi.logout,
    onSettled: () => {
      logout()
      queryClient.clear()
      navigate('/login')
    },
  })
}

export function useMe() {
  const { isAuthenticated } = useAuthStore()
  return useQuery({
    queryKey: ['me'],
    queryFn: () => userApi.getMe().then(r => r.data),
    enabled: isAuthenticated,
    staleTime: 1000 * 60 * 10,
  })
}
