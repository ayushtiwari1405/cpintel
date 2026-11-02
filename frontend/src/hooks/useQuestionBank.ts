import { useEffect, useRef } from 'react'
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
  const uploadPdf = useMutation({
    mutationFn: ({ file, version }: { file: File; version: number }) =>
      questionBankApi.uploadStatementPdf(questionId!, file, version),
    onSuccess: q => { store(q); toast.push('success', 'The PDF is now the statement') },
    onError: onError('Could not use that PDF'),
  })
  const removePdf = useMutation({
    mutationFn: (version: number) => questionBankApi.removeStatementPdf(questionId!, version),
    onSuccess: q => { store(q); toast.push('success', 'Back to the written statement') },
    onError: onError('Could not remove the PDF'),
  })
  return { save, setArchived, addTests, deleteTest, reorder, uploadPdf, removePdf }
}

/**
 * The latest check of a question, polled while one is waiting or running. When it finishes the
 * question is reloaded: a pass makes it ready, and generated answers make a new version.
 */
export function useQuestionCheck(questionId: number) {
  const qc = useQueryClient()
  const query = useQuery({
    queryKey: ['question-bank', 'check', questionId],
    queryFn: () => questionBankApi.latestCheck(questionId),
    refetchInterval: q => (q.state.data && ['QUEUED', 'RUNNING'].includes(q.state.data.status) ? 2000 : false),
  })
  const status = query.data?.status
  const active = status === 'QUEUED' || status === 'RUNNING'
  const wasActive = useRef(false)
  useEffect(() => {
    if (wasActive.current && !active) {
      qc.invalidateQueries({ queryKey: ['question-bank', 'question', questionId] })
      qc.invalidateQueries({ queryKey: ['question-bank', 'list'] })
    }
    wasActive.current = active
  }, [active, qc, questionId])
  return query
}

export function useStartCheck(questionId: number) {
  const qc = useQueryClient()
  const toast = useToast()
  return useMutation({
    mutationFn: (generateMissing: boolean) => questionBankApi.startCheck(questionId, generateMissing),
    onSuccess: c => qc.setQueryData(['question-bank', 'check', questionId], c),
    onError: err => toast.push('error', errorOf(err, 'Could not start the check')),
  })
}

export function useImportQuestion(classroomId: number | null) {
  const qc = useQueryClient()
  const toast = useToast()
  return useMutation({
    mutationFn: (file: File) => questionBankApi.importPackage(classroomId!, file),
    onSuccess: r => {
      qc.setQueryData(['question-bank', 'question', r.question.questionId], r.question)
      qc.invalidateQueries({ queryKey: ['question-bank', 'list', r.question.classroomId] })
    },
    onError: err => toast.push('error', errorOf(err, 'Could not import that package')),
  })
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
