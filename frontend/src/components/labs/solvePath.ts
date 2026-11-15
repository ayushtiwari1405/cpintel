import type { LabProblemView } from '@/types'

/**
 * Where a lab problem is solved inside CPIntel: the LeetCode or CSES workspace, or Practice for
 * Codeforces. Null for Codeforces gym problems, which Practice cannot open.
 */
export function solvePath(problem: LabProblemView): string | null {
  if (problem.problemId == null) return null
  if (problem.platform === 'LEETCODE') return `/labs/problems/${problem.problemId}`
  if (problem.platform === 'CSES') return `/labs/cses/${problem.problemId}`
  const [contest, index] = problem.key.split('/')
  return Number(contest) < 100_000 ? `/practice?contest=${contest}&index=${index}` : null
}
