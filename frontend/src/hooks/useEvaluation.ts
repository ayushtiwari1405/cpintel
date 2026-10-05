import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { adminEvaluationApi, evaluationApi, type EvaluationSource } from '@/api/evaluationApi'
import { useToast } from '@/components/common/Toaster'
import { useIsAdmin } from '@/hooks/useAdmin'
import { useAuth } from '@/contexts/AuthContext'
import { useAuthStore } from '@/store/authStore'
import type { EvaluationSheet, MarkRequest, TaAssignmentRequest, TaRequest } from '@/types'

function errorOf(err: any, fallback: string) {
  return err?.response?.data?.message ?? fallback
}

// -------------------------------------------------------------- classroom TAs

export function useClassroomTas(classroomId: number | null) {
  const isAdmin = useIsAdmin()
  return useQuery({
    queryKey: ['admin', 'classroom', classroomId, 'tas'],
    queryFn: () => adminEvaluationApi.tas(classroomId!).then(r => r.data),
    enabled: isAdmin && classroomId != null,
  })
}

export function useClassroomTaActions(classroomId: number) {
  const qc = useQueryClient()
  const toast = useToast()
  const refresh = () => {
    qc.invalidateQueries({ queryKey: ['admin', 'classroom', classroomId, 'tas'] })
    qc.invalidateQueries({ queryKey: ['admin', 'evaluation'] })
  }
  const add = useMutation({
    mutationFn: (body: TaRequest) => adminEvaluationApi.addTa(classroomId, body).then(r => r.data),
    onSuccess: refresh,
    onError: err => toast.push('error', errorOf(err, 'Could not add the TA')),
  })
  const remove = useMutation({
    mutationFn: (userId: number) => adminEvaluationApi.removeTa(classroomId, userId),
    onSuccess: refresh,
    onError: err => toast.push('error', errorOf(err, 'Could not remove the TA')),
  })
  return { add, remove }
}

// ---------------------------------------------------------------- assignments

export function useAssignmentBoard(eventId: number) {
  const isAdmin = useIsAdmin()
  return useQuery({
    queryKey: ['admin', 'evaluation', eventId, 'board'],
    queryFn: () => adminEvaluationApi.board(eventId).then(r => r.data),
    enabled: isAdmin,
  })
}

export function useAssignmentActions(eventId: number) {
  const qc = useQueryClient()
  const toast = useToast()
  const store = (board: Awaited<ReturnType<typeof adminEvaluationApi.board>>['data']) =>
    qc.setQueryData(['admin', 'evaluation', eventId, 'board'], board)
  const assign = useMutation({
    mutationFn: (body: TaAssignmentRequest) =>
      adminEvaluationApi.assign(eventId, body).then(r => r.data),
    onSuccess: store,
    onError: err => toast.push('error', errorOf(err, 'Could not assign that')),
  })
  const unassign = useMutation({
    mutationFn: (assignmentId: number) =>
      adminEvaluationApi.unassign(eventId, assignmentId).then(r => r.data),
    onSuccess: store,
    onError: err => toast.push('error', errorOf(err, 'Could not take that back')),
  })
  return { assign, unassign }
}

// ---------------------------------------------------------------------- sheet

/** The examinations this person marks. Empty for anyone who is not a TA anywhere. */
export function useMyEvaluationExams() {
  const { user } = useAuth()
  const isAdmin = useIsAdmin()
  // A session signed in for a paper can reach nothing but that paper.
  const examMode = useAuthStore(s => s.mode) === 'EXAM'
  return useQuery({
    queryKey: ['evaluation', 'exams'],
    queryFn: () => evaluationApi.exams().then(r => r.data),
    enabled: !!user && !isAdmin && !examMode,
    staleTime: 1000 * 60,
  })
}

export function useEvaluationSheet(source: EvaluationSource | null) {
  return useQuery({
    queryKey: [...(source?.key ?? ['evaluation', 'none']), 'sheet'],
    queryFn: () => source!.sheet(),
    enabled: source != null,
  })
}

export function useEvaluationSubmission(source: EvaluationSource, userId: number | null,
                                        label: string | null) {
  return useQuery({
    queryKey: [...source.key, 'submission', userId, label],
    queryFn: () => source.submission(userId!, label!),
    enabled: userId != null && label != null,
    staleTime: 1000 * 60 * 5,
  })
}

export function useSetMark(source: EvaluationSource) {
  const qc = useQueryClient()
  const toast = useToast()
  return useMutation({
    mutationFn: (body: MarkRequest) => source.mark(body),
    onSuccess: cell => {
      // Put the saved cell straight into the sheet, so the row updates without a refetch.
      qc.setQueryData<EvaluationSheet>([...source.key, 'sheet'], sheet => sheet && {
        ...sheet,
        cells: sheet.cells.map(c =>
          c.userId === cell.userId && c.label === cell.label ? cell : c),
      })
      qc.invalidateQueries({ queryKey: ['evaluation', 'exams'] })
      qc.invalidateQueries({ queryKey: ['admin', 'exam-leaderboard'] })
    },
    onError: err => toast.push('error', errorOf(err, 'Could not save the mark')),
  })
}
