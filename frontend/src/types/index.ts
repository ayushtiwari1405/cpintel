export interface User {
  userId: number
  username: string
  email: string
  fullName?: string
  avatarUrl?: string
  country?: string
  institution?: string
  role: 'USER' | 'ADMIN'
  isVerified: boolean
  createdAt: string
}

export interface PlatformAccount {
  accountId: number
  platform: 'CODEFORCES' | 'LEETCODE' | 'CODECHEF'
  handle: string
  currentRating?: number
  maxRating?: number
  lastSyncedAt?: string
  syncStatus: 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED'
}

export interface TopicMastery {
  masteryId: number
  topic: string
  masteryScore: number
  confidenceScore: number
  revisionScore: number
  decayScore: number
  problemsSolved: number
  problemsAttempted: number
  lastPracticedAt?: string
  masteryBand: 'STRONG' | 'MODERATE' | 'WEAK' | 'UNTOUCHED'
}

export interface ContestSummary {
  contestId: number
  platform: string
  contestName: string
  rank?: number
  ratingBefore?: number
  ratingAfter?: number
  ratingChange?: number
  problemsSolved: number
  totalProblems: number
  firstSolveMins?: number
  wrongSubmissions: number
  contestDate: string
}

export interface UnifiedScore {
  cfScore: number
  lcScore: number
  ccScore: number
  unifiedScore: number
  cfWeight: number
  lcWeight: number
  ccWeight: number
  computedAt: string
}

export interface Recommendation {
  recId: number
  recType: 'DAILY' | 'WEEKLY' | 'REVISION' | 'CONTEST_PREP'
  problems: RecommendedProblem[]
  generatedAt: string
  expiresAt?: string
}

export interface RecommendedProblem {
  platform: string
  problemId: string
  title: string
  difficulty: number
  tags: string[]
  url: string
  reason: string
}

export interface RoadmapNode {
  nodeId: number
  topic: string
  parentTopic?: string
  status: 'LOCKED' | 'UNLOCKED' | 'IN_PROGRESS' | 'COMPLETED'
  orderIndex: number
  unlockedAt?: string
  completedAt?: string
}

export interface RevisionItem {
  revisionId: number
  topic: string
  nextRevisionAt: string
  revisionPriority: number
  decayScore: number
  intervalDays: number
  repetitionCount: number
}

export interface SyncJob {
  jobId: number
  platform: string
  status: 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'FAILED'
  progressPct: number
  itemsSynced: number
  errorMsg?: string
  startedAt?: string
  completedAt?: string
}

export interface ApiResponse<T> {
  success: boolean
  message?: string
  data: T
  timestamp: string
}

export interface PagedResponse<T> {
  content: T[]
  totalElements: number
  totalPages: number
  page: number
  size: number
}
