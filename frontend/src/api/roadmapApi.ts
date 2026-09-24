import { apiClient } from './client'
import type { ApiResponse } from '@/types'

/**
 * A problem suggested for a skill.
 *
 * `practicePath` is the in-app route that opens it in the practice workspace. Everything used
 * to link straight out to `judgeUrl` in a new tab, which walked the user out of the product
 * past the editor, the runner and the sample console that exist for exactly this moment.
 * `judgeUrl` is kept for anyone who genuinely wants the original page.
 */
export interface RoadmapProblem {
  contestId: number
  index: string
  name: string
  rating: number | null
  tags: string[]
  solved: boolean
  /** How well the difficulty suits the user's current level in this node, 0-100. */
  fit: number
  practicePath: string | null
  judgeUrl: string | null
}

export interface RoadmapNodeData {
  nodeId: number
  nodeKey: string
  /** Display name of the skill. */
  topic: string
  /** Coarse roll-up topic this feeds on the radar. */
  parentTopic: string
  /** Top-level grouping the tree renders as a section. */
  track: string | null
  /** One line explaining what the skill is. */
  blurb: string | null
  status: 'LOCKED' | 'UNLOCKED' | 'IN_PROGRESS' | 'COMPLETED'
  orderIndex: number
  minDifficulty: number
  maxDifficulty: number
  prereqKeys: string[]
  /** Mastery in this node specifically, not in its parent topic. */
  masteryScore: number
  confidenceScore: number
  decayScore: number
  problemsSolved: number
  problemsAttempted: number
  lastPracticedAt: string | null
  unlockedAt: string | null
  completedAt: string | null
  problems: RoadmapProblem[]
}

// ── placement gauntlet ─────────────────────────────────────────────────────

export interface GauntletQuestion {
  id: string
  tier: number
  prompt: string
  code: string | null
  /** Already shuffled; nothing says which is right until the answer is checked. */
  options: string[]
}

export interface GauntletSection {
  id: string
  title: string
  blurb: string
  questions: GauntletQuestion[]
}

export interface GauntletSectionResult {
  section: string
  title: string
  tiersPassed: number
  rating: number
}

export interface GauntletResult {
  overallRating: number
  sections: GauntletSectionResult[]
  nodesPlaced: number
  takenAt: string
}

export interface GauntletPaper {
  sections: GauntletSection[]
  tierRatings: number[]
  lastResult: GauntletResult | null
  /** When another attempt may start; null when one may start now. */
  nextAttemptAt: string | null
}

export interface GauntletChecked {
  id: string
  correct: boolean
  correctIndex: number
  explanation: string
}

/** Question id → picked option index as served; -1 for "not sure yet". */
export type GauntletAnswers = Record<string, number>

export const roadmapApi = {
  getCurrent: () =>
    apiClient.get<ApiResponse<RoadmapNodeData[]>>('/roadmaps/current').then(r => r.data),

  /** The few nodes worth working on now, weakest first. */
  getNextUp: () =>
    apiClient.get<ApiResponse<RoadmapNodeData[]>>('/roadmaps/next').then(r => r.data),

  /** One skill by key - used by the practice workspace to name the skill a problem came from. */
  getNode: (nodeKey: string) =>
    apiClient.get<ApiResponse<RoadmapNodeData>>(
      `/roadmaps/nodes/${encodeURIComponent(nodeKey)}`
    ).then(r => r.data),

  regenerate: () =>
    apiClient.post<ApiResponse<RoadmapNodeData[]>>('/roadmaps/regenerate').then(r => r.data),

  updateNode: (nodeId: number, status: string) =>
    apiClient.patch<ApiResponse<RoadmapNodeData>>(
      `/roadmaps/nodes/${nodeId}?status=${status}`
    ).then(r => r.data),

  gauntlet: () =>
    apiClient.get<ApiResponse<GauntletPaper>>('/roadmaps/gauntlet').then(r => r.data),

  /** Opens an attempt. Each answer is recorded against it the first time it is checked. */
  startGauntlet: () =>
    apiClient.post<ApiResponse<{ attemptId: string }>>('/roadmaps/gauntlet/start')
      .then(r => r.data),

  checkGauntlet: (attemptId: string, answers: GauntletAnswers) =>
    apiClient.post<ApiResponse<GauntletChecked[]>>('/roadmaps/gauntlet/check',
      { attemptId, answers }).then(r => r.data),

  /** Placed from what the attempt recorded — nothing sent here changes the answers. */
  submitGauntlet: (attemptId: string) =>
    apiClient.post<ApiResponse<GauntletResult>>('/roadmaps/gauntlet/submit', { attemptId })
      .then(r => r.data),
}
