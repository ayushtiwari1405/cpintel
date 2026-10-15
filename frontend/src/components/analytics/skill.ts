import type { SkillBasis, TopicMastery } from '@/types'

/**
 * How to read one topic's rating against the rating it started from.
 *
 * Measured on real histories, topic offsets are small (a spread of about 30 points) and only
 * about one in twenty is larger than its own uncertainty. So a topic is called a strength or a
 * weak spot only when its distance from the overall rating clears its ±; anything closer is
 * reported as what it is, in line with the rating, rather than as a difference.
 */
export type SkillReading =
  | { kind: 'strength' | 'weak'; offset: number }
  | { kind: 'in-line' }
  | { kind: 'none' }

export function readSkill(t: Pick<TopicMastery, 'skillRating' | 'skillSd'>,
                          base: number | null | undefined): SkillReading {
  if (t.skillRating == null || base == null) return { kind: 'none' }
  const offset = t.skillRating - base
  const sd = t.skillSd ?? 0
  if (Math.abs(offset) <= sd) return { kind: 'in-line' }
  return { kind: offset > 0 ? 'strength' : 'weak', offset }
}

export const BASIS_LABEL: Record<NonNullable<SkillBasis['basis']>, string> = {
  CODEFORCES: 'your Codeforces rated rounds',
  GAUNTLET: 'your placement gauntlet',
}

export function fmtRating(r: number | null | undefined) {
  return r == null ? '—' : Math.round(r).toString()
}
