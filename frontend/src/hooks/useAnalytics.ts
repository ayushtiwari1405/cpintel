import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { analyticsApi, recommendationApi, roadmapApi } from '@/api/analyticsApi'
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

export function useRefreshAnalytics() {
  const queryClient = useQueryClient()
  const toast = useToast()
  return useMutation({
    mutationFn: analyticsApi.refresh,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['analytics'] })
      queryClient.invalidateQueries({ queryKey: ['dashboard'] })
      toast.push('success', 'Analytics refreshed')
    },
    onError: () => toast.push('error', 'Refresh failed'),
  })
}

export function useDailyRecs() {
  return useQuery({
    queryKey: ['recommendations', 'daily'],
    queryFn: () => recommendationApi.getDaily().then(r => r.data),
    staleTime: 1000 * 60 * 30,
  })
}

export function useWeeklyRecs() {
  return useQuery({
    queryKey: ['recommendations', 'weekly'],
    queryFn: () => recommendationApi.getWeekly().then(r => r.data),
    staleTime: 1000 * 60 * 60,
  })
}

export function useRevisionQueue() {
  return useQuery({
    queryKey: ['recommendations', 'revision'],
    queryFn: () => recommendationApi.getRevision().then(r => r.data),
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
