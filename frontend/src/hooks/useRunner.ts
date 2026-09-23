import { useMutation, useQuery } from '@tanstack/react-query'
import { runApi, type RunScope } from '@/api/runApi'
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

/**
 * Languages the backend can build here, with the reason for any that it cannot.
 *
 * Narrowed to what the event allows when one is named. Keyed on the scope so a candidate who
 * leaves a restricted examination and opens Practice is not served the examination's shortened
 * list out of the cache.
 */
export function useRunnerLanguages(scope: RunScope = {}) {
  return useQuery({
    queryKey: ['runner', 'languages', scope.platform ?? null, scope.contestId ?? null],
    queryFn: () => runApi.languages(scope).then(r => r.data),
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
