import { useState } from 'react'
import { useMutation, useQuery } from '@tanstack/react-query'
import { runApi, type RunScope } from '@/api/runApi'
import { localRunner } from '@/runner/local'
import { useAuthStore } from '@/store/authStore'
import type { RunRequest, RunnerStatus } from '@/types'

/**
 * Whether Run goes to the server.
 *
 * Only in an examination session. The server's runner is kept for examinations, where every
 * candidate must be run on the same machine under the same limits; everywhere else code runs
 * on the student's own computer (see runner/local.ts), so practice never competes with an
 * examination room for the server.
 */
function useServerRuns(): boolean {
  return useAuthStore(s => s.mode === 'EXAM')
}

const LOCAL_STATUS: RunnerStatus = { enabled: true, isolated: true, local: true }

/**
 * Whether this deployment will run code at all, and whether it does so sandboxed.
 *
 * Long-lived: the answer only changes when the backend is reconfigured or a compiler is
 * installed, neither of which happens mid-session.
 */
export function useRunnerStatus() {
  const server = useServerRuns()
  return useQuery({
    queryKey: ['runner', 'status', server],
    queryFn: () => server ? runApi.status().then(r => r.data) : LOCAL_STATUS,
    staleTime: 1000 * 60 * 30,
  })
}

/**
 * Languages that can be run here, with the reason for any that cannot.
 *
 * Narrowed to what the event allows when one is named. Keyed on the scope so a candidate who
 * leaves a restricted examination and opens Practice is not served the examination's shortened
 * list out of the cache. Running locally, the restriction still comes from the server — the
 * same list the Submit button is held to — and only the running happens here.
 */
export function useRunnerLanguages(scope: RunScope = {}) {
  const server = useServerRuns()
  return useQuery({
    queryKey: ['runner', 'languages', server, scope.platform ?? null, scope.contestId ?? null],
    queryFn: async () => {
      if (server) return runApi.languages(scope).then(r => r.data)
      const local = await localRunner.languages()
      if (!scope.platform || !scope.contestId) return local
      try {
        const allowed = new Set((await runApi.languages(scope)).data.map(r => r.id))
        return local.filter(r => allowed.has(r.id))
      } catch {
        // The restriction is a convenience here, not a gate: the judge enforces it at submit.
        return local
      }
    },
    staleTime: 1000 * 60 * 30,
  })
}

/**
 * Run a solution against tests.
 *
 * No toast on failure: the result panel shows compile errors and per-test verdicts in
 * place, which is where the user is already looking. A toast would only repeat it.
 *
 * `progress` is a line for the console while a local run does something slow — the first
 * download of a browser compiler, mostly — and null otherwise.
 */
export function useRunCode() {
  const server = useServerRuns()
  const [progress, setProgress] = useState<string | null>(null)
  const mutation = useMutation({
    mutationFn: (body: RunRequest) => server
      ? runApi.run(body).then(r => r.data)
      : localRunner.run(body, setProgress),
  })
  return { ...mutation, progress }
}
