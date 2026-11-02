import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { questionBankApi } from '@/api/questionBankApi'
import { useToast } from '@/components/common/Toaster'
import { useAuth } from '@/contexts/AuthContext'
import { useAuthStore } from '@/store/authStore'
import type { QuestionDetail, QuestionRequest } from '@/types'

function errorOf(err: any, fallback: string) {
  return err?.response?.data?.message ?? fallback
}

/**
 * The classrooms whose bank this person may open: those they run, then those they TA.
 * `wanted` lets the sidebar skip the request for admins, who reach the bank from the console.
 */
export function useBankClassrooms(wanted = true) {
  const { user } = useAuth()
  // A session signed in for a paper can reach nothing but that paper.
  const examMode = useAuthStore(s => s.mode) === 'EXAM'
  return useQuery({
    queryKey: ['question-bank', 'classrooms'],
    queryFn: questionBankApi.classrooms,
    enabled: wanted && !!user && !examMode,
    staleTime: 1000 * 60,
  })
}

export function useBankQuestions(classroomId: number | null, archived: boolean) {
  return useQuery({
    queryKey: ['question-bank', 'list', classroomId, archived],
    queryFn: () => questionBankApi.list(classroomId!, archived),
    enabled: classroomId != null,
  })
}

export function useBankQuestion(questionId: number | null) {
  return useQuery({
    queryKey: ['question-bank', 'question', questionId],
    queryFn: () => questionBankApi.get(questionId!),
    enabled: questionId != null,
  })
}

/**
 * Every change to one question. Each answers with the question as it now is, which replaces
 * the cached copy, so the next change names the version this one produced.
 */
export function useQuestionActions(questionId: number | null) {
  const qc = useQueryClient()
  const toast = useToast()
  const store = (q: QuestionDetail) => {
    qc.setQueryData(['question-bank', 'question', q.questionId], q)
    qc.invalidateQueries({ queryKey: ['question-bank', 'list', q.classroomId] })
  }
  const onError = (fallback: string) => (err: unknown) => {
    toast.push('error', errorOf(err, fallback))
    // A refusal is most often someone else's newer version: show it rather than the stale one.
    if (questionId != null) qc.invalidateQueries({ queryKey: ['question-bank', 'question', questionId] })
  }

  const save = useMutation({
    mutationFn: (body: QuestionRequest) => questionBankApi.update(questionId!, body),
    onSuccess: q => { store(q); toast.push('success', 'Saved') },
    onError: onError('Could not save the question'),
  })
  const setArchived = useMutation({
    mutationFn: (archived: boolean) => questionBankApi.setArchived(questionId!, archived),
    onSuccess: q => { store(q); toast.push('success', q.archived ? 'Archived' : 'Restored') },
    onError: onError('Could not change that'),
  })
  const addTests = useMutation({
    mutationFn: ({ files, sample, version }: { files: File[]; sample: boolean; version: number }) =>
      questionBankApi.addTests(questionId!, files, sample, version),
    onSuccess: store,
    onError: onError('Could not add those tests'),
  })
  const deleteTest = useMutation({
    mutationFn: ({ testId, version }: { testId: number; version: number }) =>
      questionBankApi.deleteTest(questionId!, testId, version),
    onSuccess: store,
    onError: onError('Could not remove the test'),
  })
  const reorder = useMutation({
    mutationFn: ({ sample, testIds, version }: { sample: boolean; testIds: number[]; version: number }) =>
      questionBankApi.reorder(questionId!, sample, testIds, version),
    onSuccess: store,
    onError: onError('Could not reorder the tests'),
  })
  return { save, setArchived, addTests, deleteTest, reorder }
}

export function useCreateQuestion(classroomId: number | null) {
  const qc = useQueryClient()
  const toast = useToast()
  return useMutation({
    mutationFn: (body: QuestionRequest) => questionBankApi.create(classroomId!, body),
    onSuccess: q => {
      qc.setQueryData(['question-bank', 'question', q.questionId], q)
      qc.invalidateQueries({ queryKey: ['question-bank', 'list', q.classroomId] })
    },
    onError: err => toast.push('error', errorOf(err, 'Could not create the question')),
  })
}
