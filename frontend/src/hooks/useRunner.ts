import { useMutation, useQuery } from '@tanstack/react-query'
import { runApi } from '@/api/runApi'
import type { RunRequest } from '@/types'

/**
 * Whether this deployment will run code at all, and whether it does so sandboxed.
 *
 * Long-lived: the answer only changes when the backend is reconfigured or a compiler is
 * installed, neither of which happens mid-session.
 */
export function useRunnerStatus() {
  return useQuery({
    queryKey: ['runner', 'status'],
    queryFn: () => runApi.status().then(r => r.data),
    staleTime: 1000 * 60 * 30,
  })
}

/** Languages the backend can build here, with the reason for any that it cannot. */
export function useRunnerLanguages() {
  return useQuery({
    queryKey: ['runner', 'languages'],
    queryFn: () => runApi.languages().then(r => r.data),
    staleTime: 1000 * 60 * 30,
  })
}

/**
 * Run a solution against tests.
 *
 * No toast on failure: the result panel shows compile errors and per-test verdicts in
 * place, which is where the user is already looking. A toast would only repeat it.
 */
export function useRunCode() {
  return useMutation({
    mutationFn: (body: RunRequest) => runApi.run(body).then(r => r.data),
  })
}
