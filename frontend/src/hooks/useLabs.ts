import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { adminLabsApi, labsApi } from '@/api/labsApi'
import { useToast } from '@/components/common/Toaster'
import { errorMessage } from '@/components/common/States'
import { useIsAdmin } from '@/hooks/useAdmin'
import type { LabRequest } from '@/types'

// ------------------------------------------------------------------ student

export function useMyLabs() {
  return useQuery({
    queryKey: ['labs', 'mine'],
    queryFn: () => labsApi.mine().then(r => r.data),
    // Solves are found on a schedule, so a page left open picks them up without a reload.
    refetchInterval: 1000 * 60 * 2,
  })
}

export function useLabCsesProblem(problemId: number) {
  return useQuery({
    queryKey: ['labs', 'cses', problemId],
    queryFn: () => labsApi.csesProblem(problemId).then(r => r.data),
    enabled: Number.isFinite(problemId),
    staleTime: 1000 * 60 * 5,
  })
}

export function useLabLeetCodeProblem(problemId: number) {
  return useQuery({
    queryKey: ['labs', 'leetcode', problemId],
    queryFn: () => labsApi.leetCodeProblem(problemId).then(r => r.data),
    enabled: Number.isFinite(problemId),
    staleTime: 1000 * 60 * 5,
  })
}

export function useCheckMyLabs() {
  const queryClient = useQueryClient()
  const toast = useToast()
  return useMutation({
    mutationFn: () => labsApi.check().then(r => r.data),
    onSuccess: result => {
      queryClient.invalidateQueries({ queryKey: ['labs', 'mine'] })
      toast.push(result.recorded > 0 ? 'success' : 'info', result.recorded > 0
        ? `Found ${result.recorded} new solve${result.recorded === 1 ? '' : 's'}.`
        : 'No new solves found.')
    },
    onError: err => toast.push('error', errorMessage(err, 'Could not check your solves')),
  })
}

// -------------------------------------------------------------------- admin

export function useAdminLabs(classroomId: number | null) {
  const isAdmin = useIsAdmin()
  return useQuery({
    queryKey: ['admin', 'classroom', classroomId, 'labs'],
    queryFn: () => adminLabsApi.list(classroomId!).then(r => r.data),
    enabled: isAdmin && classroomId != null,
  })
}

export function useAdminLab(classroomId: number, labId: number | null) {
  const isAdmin = useIsAdmin()
  return useQuery({
    queryKey: ['admin', 'classroom', classroomId, 'lab', labId],
    queryFn: () => adminLabsApi.detail(classroomId, labId!).then(r => r.data),
    enabled: isAdmin && labId != null,
  })
}

export function useLabProgress(classroomId: number, labId: number) {
  const isAdmin = useIsAdmin()
  return useQuery({
    queryKey: ['admin', 'classroom', classroomId, 'lab', labId, 'progress'],
    queryFn: () => adminLabsApi.progress(classroomId, labId).then(r => r.data),
    enabled: isAdmin,
    refetchInterval: 1000 * 30,
  })
}

export function useSaveLab(classroomId: number, labId: number | null) {
  const queryClient = useQueryClient()
  const toast = useToast()
  return useMutation({
    mutationFn: (body: LabRequest) => (labId == null
      ? adminLabsApi.create(classroomId, body)
      : adminLabsApi.update(classroomId, labId, body)).then(r => r.data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['admin', 'classroom', classroomId] })
      toast.push('success', labId == null ? 'Lab created' : 'Lab saved')
    },
    onError: err => toast.push('error', errorMessage(err, 'Could not save the lab')),
  })
}

export function useDeleteLab(classroomId: number) {
  const queryClient = useQueryClient()
  const toast = useToast()
  return useMutation({
    mutationFn: (labId: number) => adminLabsApi.remove(classroomId, labId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['admin', 'classroom', classroomId, 'labs'] })
      toast.push('info', 'Lab deleted')
    },
    onError: err => toast.push('error', errorMessage(err, 'Could not delete the lab')),
  })
}

export function useCheckLab(classroomId: number, labId: number) {
  const queryClient = useQueryClient()
  const toast = useToast()
  return useMutation({
    mutationFn: () => adminLabsApi.check(classroomId, labId).then(r => r.data),
    onSuccess: result => {
      toast.push(result.started ? 'success' : 'info', result.message)
      // The check runs in the background; look again once it has had time to get going.
      setTimeout(() => queryClient.invalidateQueries({
        queryKey: ['admin', 'classroom', classroomId, 'lab', labId, 'progress'],
      }), 10_000)
    },
    onError: err => toast.push('error', errorMessage(err, 'Could not start a check')),
  })
}

export function useDownloadLabMarks(classroomId: number) {
  const toast = useToast()
  return useMutation({
    mutationFn: async ({ labId, title }: { labId: number; title: string }) => {
      const blob = await adminLabsApi.marksXlsx(classroomId, labId)
      const base = title.trim().replace(/[^A-Za-z0-9._-]+/g, '_').replace(/^[._]+|[._]+$/g, '') || 'lab'
      const url = URL.createObjectURL(blob)
      const link = document.createElement('a')
      link.href = url
      link.download = `${base}-marks.xlsx`
      document.body.appendChild(link)
      link.click()
      link.remove()
      URL.revokeObjectURL(url)
    },
    onError: () => toast.push('error', 'That download did not go through'),
  })
}
