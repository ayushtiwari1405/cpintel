import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { platformApi } from '@/api/platformApi'
import { useToast } from '@/components/common/Toaster'
import { useEffect, useRef } from 'react'

export function useLinkedAccounts() {
  return useQuery({
    queryKey: ['platforms'],
    queryFn: () => platformApi.getLinked().then(r => r.data),
  })
}

export function useLinkAccount() {
  const queryClient = useQueryClient()
  const toast = useToast()

  return useMutation({
    mutationFn: ({ platform, handle }: { platform: string; handle: string }) =>
      platformApi.link(platform, handle),
    onSuccess: (_, vars) => {
      queryClient.invalidateQueries({ queryKey: ['platforms'] })
      toast.push('success', `${vars.platform} account linked! Syncing now…`)
    },
    onError: (err: any) => {
      toast.push('error', err.response?.data?.message ?? 'Failed to link account')
    },
  })
}

export function useUnlinkAccount() {
  const queryClient = useQueryClient()
  const toast = useToast()

  return useMutation({
    mutationFn: (platform: string) => platformApi.unlink(platform),
    onSuccess: (_, platform) => {
      queryClient.invalidateQueries({ queryKey: ['platforms'] })
      toast.push('info', `${platform} account unlinked`)
    },
    onError: (err: any) => {
      toast.push('error', err.response?.data?.message ?? 'Failed to unlink account')
    },
  })
}

export function useSyncAccount() {
  const queryClient = useQueryClient()
  const toast = useToast()
  const pollingRef = useRef<Record<number, ReturnType<typeof setInterval>>>({})

  const pollStatus = (jobId: number, platform: string) => {
    const interval = setInterval(async () => {
      try {
        const res = await platformApi.getSyncStatus(jobId)
        const job = res.data
        if (job.status === 'COMPLETED') {
          clearInterval(pollingRef.current[jobId])
          delete pollingRef.current[jobId]
          queryClient.invalidateQueries({ queryKey: ['platforms'] })
          toast.push('success', `${platform} sync complete — ${job.itemsSynced} items synced`)
        } else if (job.status === 'FAILED') {
          clearInterval(pollingRef.current[jobId])
          delete pollingRef.current[jobId]
          toast.push('error', `${platform} sync failed: ${job.errorMsg}`)
        }
      } catch {}
    }, 3000)
    pollingRef.current[jobId] = interval
  }

  return useMutation({
    mutationFn: (platform: string) => platformApi.sync(platform),
    onSuccess: (res, platform) => {
      const jobId = res.data?.jobId
      if (jobId) pollStatus(jobId, platform)
      toast.push('info', `${platform} sync started`)
    },
    onError: (err: any) => {
      toast.push('error', err.response?.data?.message ?? 'Sync failed')
    },
  })
}
