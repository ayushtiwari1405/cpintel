import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { roadmapApi } from '@/api/roadmapApi'
import { useToast } from '@/components/common/Toaster'

export function useRoadmap() {
  return useQuery({
    queryKey: ['roadmap'],
    queryFn: () => roadmapApi.getCurrent().then(r => r.data),
  })
}

/**
 * The short list the practice workspace opens on: unlocked or in-progress nodes, weakest
 * first, each already carrying problems. A hundred and forty nodes is a map, not a to-do list.
 */
export function useNextUp() {
  return useQuery({
    queryKey: ['roadmap', 'next'],
    queryFn: () => roadmapApi.getNextUp().then(r => r.data),
    staleTime: 1000 * 60 * 5,
  })
}

/** One skill, for the practice workspace's "you are working on" banner. */
export function useRoadmapNode(nodeKey?: string | null) {
  return useQuery({
    queryKey: ['roadmap', 'node', nodeKey],
    queryFn: () => roadmapApi.getNode(nodeKey!).then(r => r.data),
    enabled: !!nodeKey,
    staleTime: 1000 * 60 * 5,
    retry: false,
  })
}

export function useRegenerateRoadmap() {
  const queryClient = useQueryClient()
  const toast = useToast()
  return useMutation({
    mutationFn: roadmapApi.regenerate,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['roadmap'] })
      toast.push('success', 'Roadmap updated from your latest mastery data')
    },
    onError: () => toast.push('error', 'Could not regenerate roadmap'),
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
