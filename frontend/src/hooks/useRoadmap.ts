import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { roadmapApi } from '@/api/roadmapApi'
import { useToast } from '@/components/common/Toaster'

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
