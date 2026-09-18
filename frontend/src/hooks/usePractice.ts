import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { practiceApi, type ProblemQuery } from '@/api/practiceApi'
import { useToast } from '@/components/common/Toaster'
import { useEffect, useRef, useState } from 'react'
import type { VerdictResponse } from '@/types'

export function useProblemSearch(query: ProblemQuery, enabled = true) {
  return useQuery({
    queryKey: ['practice', 'problems', query],
    queryFn: () => practiceApi.search(query).then(r => r.data),
    enabled,
    staleTime: 1000 * 60 * 30,
  })
}

export function useProblemTags() {
  return useQuery({
    queryKey: ['practice', 'tags'],
    queryFn: () => practiceApi.tags().then(r => r.data),
    staleTime: 1000 * 60 * 60 * 6,
  })
}

export function useProblem(contestId?: number, index?: string) {
  return useQuery({
    queryKey: ['practice', 'problem', contestId, index],
    queryFn: () => practiceApi.getProblem(contestId!, index!).then(r => r.data),
    enabled: !!contestId && !!index,
    staleTime: 1000 * 60 * 60,
  })
}

export function useLanguages() {
  return useQuery({
    queryKey: ['practice', 'languages'],
    queryFn: () => practiceApi.languages().then(r => r.data),
    staleTime: 1000 * 60 * 60,
  })
}

export function useCfSession() {
  return useQuery({
    queryKey: ['practice', 'cf-session'],
    queryFn: () => practiceApi.sessionStatus().then(r => r.data),
  })
}

export function useConnectCfSession() {
  const qc = useQueryClient()
  const toast = useToast()

  return useMutation({
    mutationFn: (cookieHeader: string) => practiceApi.connectSession(cookieHeader),
    onSuccess: (res) => {
      qc.invalidateQueries({ queryKey: ['practice', 'cf-session'] })
      qc.invalidateQueries({ queryKey: ['practice', 'languages'] })
      toast.push('success', `Connected as ${res.data.handle}`)
    },
    onError: (err: any) => {
      toast.push('error', err.response?.data?.message ?? 'Could not connect that session')
    },
  })
}

export function useDisconnectCfSession() {
  const qc = useQueryClient()
  const toast = useToast()

  return useMutation({
    mutationFn: () => practiceApi.disconnectSession(),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['practice', 'cf-session'] })
      toast.push('info', 'Codeforces session removed')
    },
  })
}

/**
 * Submits, then polls the verdict until Codeforces stops saying TESTING.
 * Polls every 2s for the first ~25s, then backs off to 5s so a stuck judge does not
 * hammer the API forever.
 */
export function useSubmitSolution() {
  const qc = useQueryClient()
  const toast = useToast()
  const [verdict, setVerdict] = useState<VerdictResponse | null>(null)
  const [polling, setPolling] = useState(false)
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  const stopPolling = () => {
    if (timerRef.current) clearTimeout(timerRef.current)
    timerRef.current = null
    setPolling(false)
  }

  useEffect(() => () => stopPolling(), [])

  const poll = (submissionId: number, attempt = 0) => {
    const delay = attempt < 12 ? 2000 : 5000
    timerRef.current = setTimeout(async () => {
      try {
        const res = await practiceApi.verdict(submissionId)
        setVerdict(res.data)
        if (res.data.finished) {
          stopPolling()
          toast.push(
            res.data.verdict === 'OK' ? 'success' : 'error',
            res.data.verdict === 'OK'
              ? 'Accepted on Codeforces'
              : `Verdict: ${res.data.verdict.replace(/_/g, ' ')}`,
          )
          return
        }
      } catch {
        // transient — keep polling
      }
      if (attempt < 40) poll(submissionId, attempt + 1)
      else stopPolling()
    }, delay)
  }

  const mutation = useMutation({
    mutationFn: (body: {
      contestId: number; index: string; languageId: string; source: string
    }) => practiceApi.submit(body),
    onMutate: () => {
      stopPolling()
      setVerdict(null)
    },
    onSuccess: (res) => {
      const data = res.data
      setVerdict({
        submissionId: String(data.submissionId),
        verdict: data.verdict ?? 'TESTING',
        passedTestCount: data.passedTestCount,
        timeConsumedMillis: data.timeConsumedMillis,
        memoryConsumedBytes: data.memoryConsumedBytes,
        finished: false,
      })
      toast.push('info', data.message ?? 'Submitted to Codeforces')
      // The archive gained a row before the code even left the server, so the history panel
      // must not keep serving the list it cached a moment ago.
      qc.invalidateQueries({ queryKey: ['archive'] })
      setPolling(true)
      poll(data.submissionId)
    },
    onError: (err: any) => {
      const message = err.response?.data?.message ?? 'Submission failed'
      toast.push('error', message)
      // A dead session is dropped server-side, so refresh the badge rather than leaving
      // the UI claiming the account is still connected.
      qc.invalidateQueries({ queryKey: ['practice', 'cf-session'] })
      // A refused submission is still archived — that copy is how the user gets the code
      // back — so the history list changed here too.
      qc.invalidateQueries({ queryKey: ['archive'] })
    },
  })

  return { ...mutation, verdict, polling, reset: () => { stopPolling(); setVerdict(null) } }
}
