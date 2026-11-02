import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { examBankApi } from '@/api/examBankApi'
import { useToast } from '@/components/common/Toaster'
import type { ExamBankState } from '@/types'

function errorOf(err: any, fallback: string) {
  return err?.response?.data?.message ?? fallback
}

export function useExamBank(eventId: number) {
  return useQuery({
    queryKey: ['admin', 'exam-bank', eventId],
    queryFn: () => examBankApi.state(eventId),
  })
}

/**
 * Every change to an examination's bank questions. Each answers with the new state; publishing
 * also changes the examination's problem list, so its detail is reloaded too.
 */
export function useExamBankActions(eventId: number) {
  const qc = useQueryClient()
  const toast = useToast()
  const store = (s: ExamBankState) => qc.setQueryData(['admin', 'exam-bank', eventId], s)
  const published = (s: ExamBankState) => {
    store(s)
    qc.invalidateQueries({ queryKey: ['admin', 'event', eventId] })
  }
  const onError = (fallback: string) => (err: unknown) => {
    toast.push('error', errorOf(err, fallback))
    // A publish that stopped part way recorded how far it got.
    qc.invalidateQueries({ queryKey: ['admin', 'exam-bank', eventId] })
  }
  const choose = useMutation({
    mutationFn: (questionIds: number[]) => examBankApi.choose(eventId, questionIds),
    onSuccess: store,
    onError: onError('Could not save the choice'),
  })
  const publish = useMutation({
    mutationFn: () => examBankApi.publish(eventId),
    onSuccess: s => { published(s); toast.push('success', 'Published to the judge') },
    onError: onError('Could not publish'),
  })
  const republish = useMutation({
    mutationFn: ({ questionId, notice }: { questionId: number; notice: string | null }) =>
      examBankApi.republish(eventId, questionId, notice),
    onSuccess: s => { published(s); toast.push('success', 'The judge has the corrected question') },
    onError: onError('Could not publish the fix'),
  })
  const announce = useMutation({
    mutationFn: (message: string) => examBankApi.announce(eventId, message),
    onSuccess: list => {
      qc.setQueryData<ExamBankState>(['admin', 'exam-bank', eventId], s => (s ? { ...s, announcements: list } : s))
      toast.push('success', 'Sent to everyone sitting it')
    },
    onError: onError('Could not send the notice'),
  })
  return { choose, publish, republish, announce }
}
