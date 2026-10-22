import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { analyticsApi, recommendationApi, roadmapApi } from '@/api/analyticsApi'
import type { PlanType } from '@/types'
import { useToast } from '@/components/common/Toaster'

export function useOverview() {
  return useQuery({
    queryKey: ['analytics', 'overview'],
    queryFn: () => analyticsApi.getOverview().then(r => r.data),
    staleTime: 1000 * 60 * 10,
  })
}

export function useTopicAnalytics() {
  return useQuery({
    queryKey: ['analytics', 'topics'],
    queryFn: () => analyticsApi.getTopics().then(r => r.data),
    staleTime: 1000 * 60 * 10,
  })
}

export function useContestAnalytics() {
  return useQuery({
    queryKey: ['analytics', 'contests'],
    queryFn: () => analyticsApi.getContests().then(r => r.data),
    staleTime: 1000 * 60 * 10,
  })
}

export function useTrends() {
  return useQuery({
    queryKey: ['analytics', 'trends'],
    queryFn: () => analyticsApi.getTrends().then(r => r.data),
    staleTime: 1000 * 60 * 10,
  })
}

export function useSkillBasis() {
  return useQuery({
    queryKey: ['analytics', 'skill-basis'],
    queryFn: () => analyticsApi.getSkillBasis().then(r => r.data),
    staleTime: 1000 * 60 * 10,
  })
}

/** Changing the basis rebuilds every rating, the roadmap with them. */
export function useSetSkillBasis() {
  const queryClient = useQueryClient()
  const toast = useToast()
  return useMutation({
    mutationFn: analyticsApi.setSkillBasis,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['analytics'] })
      queryClient.invalidateQueries({ queryKey: ['dashboard'] })
      queryClient.invalidateQueries({ queryKey: ['roadmap'] })
      toast.push('success', 'Skill ratings rebuilt')
    },
    onError: (err: any) => toast.push('error',
      err.response?.data?.message ?? 'Could not change what ratings are based on'),
  })
}

export function useRefreshAnalytics() {
  const queryClient = useQueryClient()
  const toast = useToast()
  return useMutation({
    mutationFn: analyticsApi.refresh,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['analytics'] })
      queryClient.invalidateQueries({ queryKey: ['dashboard'] })
      queryClient.invalidateQueries({ queryKey: ['recommendations'] })
      toast.push('success', 'Analytics refreshed')
    },
    onError: (err: any) => toast.push('error', err.response?.data?.message ?? 'Refresh failed'),
  })
}

/** One practice plan. Opening it builds it on the server the first time, so it is not refetched eagerly. */
export function usePlan(type: PlanType) {
  return useQuery({
    queryKey: ['recommendations', type],
    queryFn: () => recommendationApi.getPlan(type).then(r => r.data),
    staleTime: 1000 * 60 * 5,
  })
}

/** Replaces a plan with a new one; the server keeps a sheet built in the last minute. */
export function useRegeneratePlan(type: PlanType) {
  const queryClient = useQueryClient()
  const toast = useToast()
  return useMutation({
    mutationFn: () => recommendationApi.regenerate(type).then(r => r.data),
    onSuccess: plan => {
      queryClient.setQueryData(['recommendations', type], plan)
      // A new sheet leaves the others' problems out, so they are worth reading again.
      queryClient.invalidateQueries({ queryKey: ['recommendations'] })
      toast.push('success', 'New plan ready')
    },
    onError: (err: any) => toast.push('error', err.response?.data?.message ?? 'Could not build a new plan'),
  })
}

export function useRevisionQueue(horizonDays = 14) {
  return useQuery({
    queryKey: ['recommendations', 'revision', horizonDays],
    queryFn: () => recommendationApi.getRevision(horizonDays).then(r => r.data),
  })
}

export function useMarkRevisionDone() {
  const queryClient = useQueryClient()
  const toast = useToast()
  return useMutation({
    mutationFn: (id: number) => recommendationApi.markRevisionDone(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['recommendations', 'revision'] })
      toast.push('success', 'Revision marked complete')
    },
    onError: (err: any) => toast.push('error', err.response?.data?.message ?? 'Could not mark it done'),
  })
}

export function useRoadmap() {
  return useQuery({
    queryKey: ['roadmap'],
    queryFn: () => roadmapApi.getCurrent().then(r => r.data),
  })
}

export function useRegenerateRoadmap() {
  const queryClient = useQueryClient()
  const toast = useToast()
  return useMutation({
    mutationFn: roadmapApi.regenerate,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['roadmap'] })
      toast.push('success', 'Roadmap updated based on your progress')
    },
  })
}

export function useUpdateRoadmapNode() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ nodeId, status }: { nodeId: number; status: string }) =>
      roadmapApi.updateNode(nodeId, status),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['roadmap'] }),
  })
}
