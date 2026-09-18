/**
 * The three tiers.
 *
 * SUPER_ADMIN is everything ADMIN is, plus the two actions that change who else has access:
 * assigning roles and creating accounts. Only the server's copy of this decides anything —
 * what is stored here drives which controls are drawn, never which are permitted.
 */
export type Role = 'USER' | 'ADMIN' | 'SUPER_ADMIN'

/** Roles a super admin can assign. SUPER_ADMIN is set in deployment config, not in the UI. */
export type AssignableRole = 'USER' | 'ADMIN'

export interface User {
  userId: number
  username: string
  email: string
  fullName?: string
  avatarUrl?: string
  country?: string
  institution?: string
  role: Role
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

// ---------------------------------------------------------------- practice arena

export interface ProblemSummary {
  contestId: number
  index: string
  name: string
  rating?: number
  tags: string[]
  url: string
}

export interface ProblemSample {
  input: string
  output: string
}

export interface ProblemDetail {
  contestId: string
  index: string
  name: string
  rating?: number
  tags: string[]
  timeLimit?: string
  memoryLimit?: string
  inputFile?: string
  outputFile?: string
  legendHtml?: string
  inputSpecHtml?: string
  outputSpecHtml?: string
  noteHtml?: string
  samples: ProblemSample[]
  url: string
  statementAvailable: boolean
  /** Set when the judge publishes a PDF instead of HTML; served through CPIntel's own proxy. */
  statementPdfUrl: string | null
  /** Why the statement is missing, when it is. Null whenever one was read. */
  statementIssue?: 'SESSION_STALE' | 'NO_SESSION' | 'NOT_FOUND' | 'UNAVAILABLE' | null
}

export interface LanguageOption {
  id: string
  label: string
}

export interface CfSessionStatus {
  connected: boolean
  handle?: string
  linkedAt?: string
  expiresAt?: string
  submitEnabled: boolean
}

export interface SubmitResponse {
  accepted: boolean
  submissionId: number
  verdict?: string
  passedTestCount?: number
  timeConsumedMillis?: number
  memoryConsumedBytes?: number
  message?: string
  statusUrl?: string
}

export interface VerdictResponse {
  /** Text, so one shape carries a Codeforces number and a DOMjudge id alike. */
  submissionId: string
  verdict: string
  passedTestCount?: number
  timeConsumedMillis?: number
  memoryConsumedBytes?: number
  finished: boolean
}

// ---------------------------------------------------------------- compete

export type CompetePlatform = 'CODEFORCES' | 'CODECHEF' | 'DOMJUDGE'

/**
 * Which contest, on which judge.
 *
 * Both halves are needed to address a contest now that two judges are wired up: they number
 * their contests independently, so an id on its own is ambiguous. Passing them as one object
 * keeps every hook and API call from growing a second positional argument that is easy to
 * forget and impossible to type-check against the first.
 */
export interface ContestRef {
  platform: CompetePlatform
  id: string
}

export interface ContestProblem {
  index: string
  name: string | null
  points: number | null
  rating: number | null
}

export interface ContestInfo {
  id: string
  name: string
  platform: string
  /** BEFORE, CODING, PENDING_SYSTEM_TEST, SYSTEM_TEST or FINISHED. */
  phase: string
  running: boolean
  frozen: boolean
  startsAt: string | null
  durationSeconds: number
  secondsUntilStart: number
  secondsRemaining: number
  submissionsOpen: boolean
  submissionsClosedReason: string | null
  /** Whether this contest lets you open your own uploaded files. Admin-controlled. */
  personalFilesEnabled: boolean
  /** HTML on Codeforces, PDF on DOMjudge — decides how the statement pane renders. */
  statementFormat: 'HTML' | 'PDF'
  problems: ContestProblem[]
  url: string
}

export interface ContestSubmission {
  id: string
  index: string | null
  problemName: string | null
  language: string | null
  verdict: string
  passedTestCount: number | null
  timeConsumedMillis: number | null
  memoryConsumedBytes: number | null
  createdAt: string | null
  finished: boolean
  url: string
}

export interface RankInfo {
  rank: number | null
  points: number | null
  penalty: number | null
  solvedCount: number | null
  frozen: boolean
  participating: boolean
  fetchedAt: string
}

// ── Local code runner ────────────────────────────────────────────────────────

/** A test fed to the program. `expected` is null for a scratch run with custom input. */
export interface RunTestCase {
  input: string
  expected: string | null
  label: string
}

export type RunVerdict =
  | 'OK'
  | 'WRONG_ANSWER'
  | 'TIME_LIMIT_EXCEEDED'
  | 'RUNTIME_ERROR'
  /** Ran, but there was no expected answer to compare against. */
  | 'NO_EXPECTED'

export interface RunTestResult {
  label: string
  verdict: RunVerdict
  input: string
  expected: string | null
  actual: string
  stderr: string
  durationMs: number
  exitCode: number | null
  truncated: boolean
}

export interface RunResponse {
  compiled: boolean
  /** Compiler output: errors on failure, warnings on success. */
  compileOutput: string | null
  compileMs: number
  results: RunTestResult[]
  /** Set when the run could not be attempted — runner off, toolchain missing. */
  error: string | null
}

export interface RunRequest {
  language: string
  source: string
  tests: RunTestCase[]
}

export interface RunnerRuntime {
  id: string
  displayName: string
  editorLanguage: string
  available: boolean
  unavailableReason: string | null
}

export interface RunnerStatus {
  enabled: boolean
  /** False means resource limits only — no filesystem or network isolation. */
  isolated: boolean
}

// ── Code archive ─────────────────────────────────────────────────────────────

/**
 * One past attempt at a problem.
 *
 * The list merges two sources, and `stored` says which this row came from: true means the
 * source is in CPIntel and opens instantly with no network, false means only the platform
 * has it and opening it costs a fetch.
 */
export interface ArchiveAttempt {
  /** Archive id. Null when only the platform knows about this submission. */
  id: string | null
  /** The platform's submission id. Null for an attempt the platform refused. */
  externalId: number | null
  platform: string
  contestId: string | null
  problemIndex: string | null
  problemName: string | null
  languageId: string | null
  languageLabel: string | null
  verdict: string | null
  passedTestCount: number | null
  timeConsumedMillis: number | null
  memoryConsumedBytes: number | null
  submittedAt: string | null
  sourceBytes: number | null
  sourceHash: string | null
  stored: boolean
  /** Byte-identical to the attempt below it in the list. */
  sameAsPrevious: boolean
  url: string | null
}

export interface ArchiveSource {
  id: string | null
  externalId: number | null
  platform: string
  contestId: string | null
  problemIndex: string | null
  problemName: string | null
  languageId: string | null
  languageLabel: string | null
  verdict: string | null
  submittedAt: string | null
  source: string
  /** ARCHIVE means it was already here; CODEFORCES means it was just fetched. */
  retrievedFrom: 'ARCHIVE' | 'CODEFORCES'
}

export interface ArchiveAttemptPage {
  attempts: ArchiveAttempt[]
  /** False when Codeforces could not be asked, so the list is local-only. */
  codeforcesReachable: boolean
  notice: string | null
}

/**
 * One test as the judge ran it.
 *
 * Everything but the index is nullable: Codeforces shows the data for practice submissions
 * and withholds it during a live round, so a row can legitimately be no more than
 * "test 4, wrong answer" — which is still worth showing.
 */
export interface TestOutcome {
  index: number | null
  verdict: string | null
  input: string | null
  output: string | null
  answer: string | null
  /** The checker's own words, e.g. "wrong answer Test 138: answer is not maximised". */
  checkerMessage: string | null
  exitCode: number | null
  timeMs: number | null
  memoryBytes: number | null
  /** Codeforces clipped at least one of input/output/answer. */
  truncated: boolean
}

export interface TestReport {
  available: boolean
  tests: TestOutcome[]
  testCount: number | null
  /** 1-based index of the first failing test, when the platform names one. */
  failedOnTest: number | null
  compilationError: string | null
  verdict: string | null
  notice: string | null
}

// ------------------------------------------------------------ personal files

/** One file in the library, without its bytes. */
export interface PersonalFile {
  id: string
  name: string
  label: string | null
  contentType: string
  sizeBytes: number
  /** True when the panel can render it; false means download-only. */
  textual: boolean
  sourceHash: string | null
  uploadedAt: string | null
  updatedAt: string | null
}

/** The library plus the limits it is held to, so the UI can refuse a file before uploading. */
export interface FileVault {
  files: PersonalFile[]
  usedBytes: number
  maxTotalBytes: number
  maxFileBytes: number
  maxFiles: number
  allowedExtensions: string[]
}

export interface PersonalFileContent {
  file: PersonalFile
  /** Null for anything not safe to render as text. */
  text: string | null
  /** True when a long text file was clipped for the panel; the download is never clipped. */
  truncated: boolean
  notice: string | null
}

/** An admin's decision for one contest. */
export interface ContestFileRule {
  platform: string
  contestId: string | null
  enabled: boolean
  note: string | null
  updatedBy: number | null
  updatedAt: string | null
}

export interface ContestFilePolicy {
  defaultEnabled: boolean
  /** False once an admin has overridden the deployment default at runtime. */
  defaultFromConfig: boolean
  rules: ContestFileRule[]
}

// ---------------------------------------------------------------- admin console

/** One row of the admin user table. */
export interface AdminUserRow {
  userId: number
  username: string
  email: string
  fullName: string | null
  role: Role
  active: boolean
  verified: boolean
  createdAt: string
  /** Read from the audit trail, so null means "not since auditing began", not "never". */
  lastLoginAt: string | null
}

export interface AdminUserPage {
  users: AdminUserRow[]
  page: number
  size: number
  total: number
  totalPages: number
}

export interface AdminUserDetail {
  user: AdminUserRow
  country: string | null
  institution: string | null
  avatarUrl: string | null
  platforms: PlatformAccount[]
  /** Counts and sizes only — the console never exposes anyone's file names or contents. */
  fileCount: number
  fileBytes: number
  recentActivity: AuditEntry[]
}

export interface AuditEntry {
  logId: number
  userId: number | null
  username: string | null
  action: string
  entityType: string | null
  entityId: string | null
  ipAddress: string | null
  userAgent: string | null
  createdAt: string
}

export interface AuditPage {
  entries: AuditEntry[]
  page: number
  size: number
  total: number
  totalPages: number
  /** The actions actually present in the trail, for the filter list. */
  actions: string[]
}

export interface AdminOverview {
  users: {
    total: number
    active: number
    inactive: number
    admins: number
    verified: number
    joinedLast7Days: number
  }
  sync: {
    queued: number
    running: number
    failedLast24h: number
    completedLast24h: number
  }
  files: {
    files: number
    bytes: number
    owners: number
    /** False when the file store could not be reached; the figures are then meaningless. */
    available: boolean
  }
  policy: {
    contestFilesEnabledByDefault: boolean
    defaultFromConfig: boolean
    contestExceptions: number
  }
  recentActivity: AuditEntry[]
}

// ------------------------------------------------------------- group contests

export type GroupPlatform = 'CODEFORCES' | 'DOMJUDGE'
export type GroupContestStatus = 'SCHEDULED' | 'LIVE' | 'FINISHED'

export interface GroupSummary {
  groupId: number
  name: string
  description: string | null
  active: boolean
  memberCount: number
  contestCount: number
  createdAt: string
}

export interface GroupMember {
  userId: number
  username: string
  email: string
  fullName: string | null
  /** From their linked Codeforces account, when they have one. */
  codeforcesHandle: string | null
  /** Recorded on the membership — the only way to find someone on DOMjudge. */
  externalHandle: string | null
  active: boolean
  joinedAt: string
}

export interface GroupContestSummary {
  contestId: number
  groupId: number
  groupName: string
  platform: GroupPlatform
  externalId: string
  name: string
  url: string | null
  startsAt: string | null
  endsAt: string | null
  lockdownRequired: boolean
  status: GroupContestStatus
  standingsRefreshedAt: string | null
  standingsError: string | null
}

export interface GroupDetail {
  group: GroupSummary
  members: GroupMember[]
  contests: GroupContestSummary[]
}

/** Counts only. There is no single "suspicion score", deliberately. */
export interface ViolationSummary {
  focusLosses: number
  awayMs: number
  clipboardWipes: number
  blockedActions: number
  lockdownUnavailable: boolean
  lockdownPartial: boolean
  lockdownReleased: boolean
  total: number
}

export interface StandingRow {
  /** Null when this member could not be found on the external board at all. */
  groupRank: number | null
  userId: number
  username: string
  handle: string | null
  solved: number
  penalty: number
  score: number | null
  /** Per-problem JSON, shaped by the judge it came from. */
  detail: string | null
  found: boolean
  /** Absent on a participant's own view, which carries no conduct data. */
  violations: ViolationSummary | null
}

export interface GroupStandings {
  contest: GroupContestSummary
  rows: StandingRow[]
  refreshedAt: string | null
  error: string | null
  /** Members the judge had no row for — nearly always a handle that does not match. */
  unmatched: string[]
}

export interface ViolationEntry {
  violationId: number
  userId: number
  username: string
  type: string
  detail: string | null
  durationMs: number | null
  occurredAt: string
  reportedAt: string
}

export interface ViolationFeed {
  contest: GroupContestSummary
  entries: ViolationEntry[]
  total: number
}

export interface MyGroupContest {
  contest: GroupContestSummary
  myRank: number | null
  groupSize: number | null
  mySolved: number | null
}

/** One event as the desktop lock reports it. */
export interface ViolationEvent {
  eventId: string
  type: string
  detail?: string
  durationMs?: number
  occurredAt: string
}
