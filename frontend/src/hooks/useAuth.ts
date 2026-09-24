import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { authApi } from '@/api/authApi'
import { userApi } from '@/api/userApi'
import { useAuthStore } from '@/store/authStore'
import { useToast } from '@/components/common/Toaster'
import { useNavigate } from 'react-router-dom'

export function useLogin() {
  const { setAccessToken, setUser, setSession } = useAuthStore()
  const toast = useToast()
  const navigate = useNavigate()

  return useMutation({
    mutationFn: authApi.login,
    onSuccess: (res) => {
      setAccessToken(res.data.accessToken)
      setUser(res.data.user)
      // The examination password opens the paper it belongs to and nothing else.
      const exam = res.data.mode === 'EXAM' && res.data.examId != null
      setSession(exam ? 'EXAM' : 'NORMAL', exam ? res.data.examId! : null)
      navigate(exam ? '/exam' : '/dashboard')
    },
    onError: (err: any) => {
      toast.push('error', err.response?.data?.message ?? 'Login failed')
    },
  })
}

/**
 * Asking for a reset link.
 *
 * Deliberately shows the same message however it goes, including when the address belongs to
 * nobody — the server answers identically, and a screen that drew a distinction would put the
 * enumeration back that the endpoint removed.
 */
export function useForgotPassword() {
  return useMutation({
    mutationFn: authApi.forgotPassword,
  })
}

export function useResetPassword() {
  const toast = useToast()
  const navigate = useNavigate()

  return useMutation({
    mutationFn: authApi.resetPassword,
    onSuccess: (res) => {
      toast.push('success', res.message ?? 'Your password has been changed.')
      navigate('/login')
    },
    onError: (err: any) => {
      toast.push('error', err.response?.data?.message ?? 'That reset link did not work.')
    },
  })
}

/**
 * Changing your own password.
 *
 * Succeeding ends every session, including this one, so the local tokens are dropped and the
 * user is sent back to sign in. Leaving them on a page whose next request would 401 would look
 * like the change had failed.
 */
export function useChangePassword() {
  const { logout } = useAuthStore()
  const queryClient = useQueryClient()
  const toast = useToast()
  const navigate = useNavigate()

  return useMutation({
    mutationFn: authApi.changePassword,
    onSuccess: (res) => {
      toast.push('success', res.message ?? 'Your password has been changed.')
      logout()
      queryClient.clear()
      navigate('/login')
    },
    onError: (err: any) => {
      toast.push('error', err.response?.data?.message ?? 'Could not change your password.')
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
