import type { RoadmapNodeData } from '@/api/roadmapApi'

/*
 * The roadmap's rules as the server applies them (RoadmapService), so the page can say what a
 * node is waiting for. With a skill rating: a prerequisite clears a third of the way into its
 * band; a node is done three-quarters of the way up, with a few of its problems solved. Without
 * one (coverage): a few solves clear a prerequisite; a node is done after five solves of which the
 * hardest reaches the middle of its band.
 */
export const UNLOCK_SHARE = 0.35
export const COMPLETE_SHARE = 0.75
export const COMPLETE_MIN_SOLVED = 3
export const COVERAGE_UNLOCK_SOLVED = 3
export const COVERAGE_COMPLETE_SOLVED = 5

type Band = Pick<RoadmapNodeData, 'minDifficulty' | 'maxDifficulty'>

export function bandPoint(n: Band, share: number) {
  const lo = n.minDifficulty ?? 0
  const hi = n.maxDifficulty ?? lo
  return Math.round(lo + share * (hi - lo))
}

export const isRated = (n: Pick<RoadmapNodeData, 'skillRating'>) => n.skillRating != null

export function cleared(n: RoadmapNodeData) {
  return isRated(n)
    ? (n.skillRating ?? 0) >= bandPoint(n, UNLOCK_SHARE)
    : n.problemsSolved >= COVERAGE_UNLOCK_SOLVED
}

/** How far along the node someone is, 0 to 1 — what its progress bar shows. */
export function progress(n: RoadmapNodeData) {
  if (isRated(n)) {
    const lo = n.minDifficulty ?? 0
    const span = Math.max(1, (n.maxDifficulty ?? lo) - lo)
    return Math.max(0, Math.min(1, ((n.skillRating ?? 0) - lo) / span))
  }
  return Math.min(1, n.problemsSolved / COVERAGE_COMPLETE_SOLVED)
}

/** What still stands between this node and done, in words. */
export function doneWhen(n: RoadmapNodeData) {
  return isRated(n)
    ? `Done at a rating of ${bandPoint(n, COMPLETE_SHARE)} here, with ${COMPLETE_MIN_SOLVED} problems solved`
    : `Done after ${COVERAGE_COMPLETE_SOLVED} solves, the hardest rated ${bandPoint(n, 0.5)} or more`
}

/** What the node's headline number is: its rating, or how much was solved. */
export function headline(n: RoadmapNodeData) {
  return isRated(n)
    ? `${Math.round(n.skillRating ?? 0)}`
    : `${n.problemsSolved} solved`
}
