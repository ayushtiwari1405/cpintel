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
  /**
   * When you last set your own password, or null if you never have.
   *
   * Null is a fact rather than a gap: the first password on an account is chosen by whoever
   * created it and handed over with the username, so until this is set somebody else knows it.
   */
  passwordChangedAt?: string | null
}

export interface PlatformAccount {
  accountId: number
  platform: 'CODEFORCES'
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
  unifiedScore: number
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
  statementIssue?: 'SESSION_STALE' | 'NO_SESSION' | 'NOT_FOUND' | 'UNAVAILABLE'
    | 'BROWSER_CHECK' | 'NOT_CACHED' | null
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
  /** Linked through this browser (extension or desktop app): no cookies on the server. */
  viaBrowser?: boolean
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

export type CompetePlatform = 'CODEFORCES' | 'DOMJUDGE'

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

/** One problem's state for one team, as the judge's scoreboard reports it. */
export interface LeaderboardCell {
  index: string
  solved: boolean
  attempts: number
  /** Minutes from the contest start; null unless the problem is actually solved. */
  minute: number | null
}

export interface LeaderboardRow {
  rank: number | null
  teamId: string
  teamName: string
  solved: number
  penalty: number | null
  /** True for the viewer's own team, so the board can pin and highlight it. */
  mine: boolean
  problems: LeaderboardCell[]
}

/**
 * The contest's full board, plus the viewer's own team pulled out of it.
 *
 * `frozen` and `live` say different things and the page shows both. `frozen` means the contest
 * has entered its freeze, which every contestant expects. `live` is false when CPIntel could
 * only read the public board — a property of the credentials, not the contest. A frozen board
 * presented as current looks exactly like a room where nobody is solving anything.
 */
export interface Leaderboard {
  rows: LeaderboardRow[]
  myTeamId: string | null
  myTeamName: string | null
  myTeam: LeaderboardRow | null
  problemIndexes: string[]
  frozen: boolean
  live: boolean
  fetchedAt: string
}

/**
 * The DOMjudge account an admin attached.
 *
 * Two teams, deliberately not merged. `teamId` is the judge's own answer — where submissions
 * made as this login actually land, which nothing in CPIntel can change. `assignedTeamId` is
 * the team an admin decided this person belongs to, and drives CPIntel's grouping only.
 * `teamMismatch` is true when they disagree, which is occasionally deliberate and usually a
 * mistake; only the admin looking at it can tell which.
 */
export interface DomjudgeAccount {
  linked: boolean
  username: string | null
  name: string | null
  teamId: string | null
  teamName: string | null
  assignedTeamId: string | null
  assignedTeamName: string | null
  teamMismatch: boolean
  provisioned: string | null
  expiresInSeconds: number | null
}

/** One team an admin may put somebody in. */
export interface DomjudgeTeamOption {
  id: string
  name: string
}

/** One contest the attached DOMjudge account may enter. */
export interface DomjudgeContestSummary {
  id: string
  name: string
  phase: 'BEFORE' | 'CODING' | 'FINISHED'
  running: boolean
  startsAt: string | null
  endsAt: string | null
  durationSeconds: number
  secondsUntilStart: number
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
  /**
   * Which event this run belongs to, when it belongs to one.
   *
   * Both omitted on Practice. Sent by the contest and examination workspaces so an
   * examination restricted to two languages restricts Run as well — otherwise the rule would
   * hold at Submit and nowhere else, which is where candidates spend the least of their time.
   */
  platform?: string
  contestId?: string
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
  /**
   * When the owner last set this password themselves, or null if they never have.
   *
   * Null is a fact rather than a gap: until it is set, the password on the account is the one
   * somebody else typed when they created it, and whoever that was still knows it.
   */
  passwordChangedAt: string | null
}

/**
 * One language an event's submissions may be restricted to.
 *
 * Served by the backend rather than listed in the page, so the options an admin picks from are
 * the same ones the server validates against — two copies would mean a language offered in the
 * console and refused on save.
 */
export interface LanguageChoice {
  id: string
  label: string
}

/** A password an administrator just set. Shown once; the server keeps only a hash. */
export interface GeneratedPassword {
  userId: number
  username: string
  password: string
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
  /** CONTEST or EXAM — one row carries both, see the backend's GroupContest. */
  kind: EventKind
  /** Null for an examination set for named individuals, which belongs to no team. */
  groupId: number | null
  groupName: string | null
  platform: GroupPlatform
  externalId: string
  name: string
  url: string | null
  startsAt: string | null
  endsAt: string | null
  lockdownRequired: boolean
  /** How long someone may be away before it is recorded, in seconds. */
  awayThresholdSeconds: number
  status: GroupContestStatus
  lifecycle: EventLifecycle
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

// ------------------------------------------------------ contests and exams

export type EventKind = 'CONTEST' | 'EXAM'

/**
 * Where an event is in its own life.
 *
 * DRAFT and ARCHIVED are stored; the three in between are read from the window, so a client
 * never has to work out for itself whether an examination has started.
 */
export type EventLifecycle = 'DRAFT' | 'SCHEDULED' | 'ACTIVE' | 'ENDED' | 'ARCHIVED'

export type EventVisibility = 'PUBLIC' | 'TEAMS' | 'USERS'

/**
 * What an examination asks a locked-down desktop client to do.
 *
 * Requests rather than guarantees: the desktop applies what the operating system allows and
 * reports what it could not, and a browser applies almost none of it — which is why an
 * examination sat in a browser records that its monitoring was the weaker kind.
 */
export interface DesktopPolicy {
  restrictWindowSwitching: boolean
  blockNavigation: boolean
  blockExternalApps: boolean
  detectLeavingExam: boolean
  detectAppTermination: boolean
  clipboardGuard: boolean
  /** The paper is sat full screen; leaving full screen covers the workspace and is recorded. */
  requireFullscreen: boolean
}

export interface EventProblem {
  problemId: number | null
  label: string
  title: string | null
  externalId: string | null
  ordering: number
  /** Points in a contest, marks in an examination. */
  points: number | null
}

/** One problem of the linked judge contest, as the judge lists it. */
export interface JudgeProblem {
  label: string
  title: string | null
  externalId: string | null
}

export interface EventSummary {
  eventId: number
  kind: EventKind
  platform: GroupPlatform
  externalId: string
  name: string
  description: string | null
  url: string | null
  startsAt: string | null
  endsAt: string | null
  durationSeconds: number | null
  lifecycle: EventLifecycle
  visibility: EventVisibility
  lockdownRequired: boolean
  awayThresholdSeconds: number
  teamId: number | null
  teamName: string | null
  assignedTeams: number
  assignedUsers: number
  participantCount: number
  problemCount: number
  standingsRefreshedAt: string | null
  standingsError: string | null
}

export interface EventDetail {
  event: EventSummary
  rules: string | null
  desktopPolicy: DesktopPolicy
  allowedLanguages: string[]
  problems: EventProblem[]
  teams: { teamId: number; name: string; memberCount: number }[]
  users: { userId: number; username: string; fullName: string | null; active: boolean }[]
  /** Whether contestants may open their own uploaded files during it. */
  personalFilesAllowed: boolean
  /** True once it has started: only the end time (and name) can still change. */
  settingsLocked: boolean
}

/** An examination as the person sitting it sees it — their clock, their problems, their place. */
export interface MyExam {
  event: EventSummary
  rules: string | null
  problems: EventProblem[]
  desktopPolicy: DesktopPolicy
  allowedLanguages: string[]
  secondsUntilStart: number
  secondsRemaining: number
  entered: boolean
  mySubmissions: number
  myRank: number | null
  mySolved: number | null
  /**
   * Whether this paper asks for a password, and whether this device has given it.
   *
   * Both are needed and neither implies the other. A paper with no password is always
   * unlocked; somebody who unlocked one on their own machine an hour ago still has to be told
   * it is password-protected when they open it on another.
   */
  requiresPassword: boolean
  unlocked: boolean
  /**
   * Which of the two the paper wants, separately.
   *
   * `requiresPassword` is the union and cannot stand in for either — a form with only the
   * union would not know whether to draw one box or two, and would make a candidate guess how
   * many secrets they were meant to have been handed.
   */
  needsExamPassword: boolean
  /** True when a code was issued to this candidate personally, so they must present it too. */
  needsPasscode: boolean
  /** True once the paper has ended and they may read their own code back. */
  canReviewSubmissions: boolean
  /** True when this session was signed in for this paper with its examination password. */
  examSession: boolean
}

/** What a candidate submitted into a past examination. Their own work, and nothing else. */
export interface MyExamSubmission {
  id: string
  externalId: number | null
  problemLabel: string | null
  problemName: string | null
  languageId: string | null
  languageLabel: string | null
  verdict: string | null
  submittedAt: string
  sourceBytes: number | null
  /** Present only when one submission was asked for; the list never carries source. */
  source: string | null
}

/** One candidate's examination code, as the screen that prints the desk slips sees it. */
export interface IssuedPasscode {
  userId: number
  username: string
  fullName: string | null
  code: string
  issuedAt: string
  /** When this code first opened the paper, or null if it never has. */
  firstUsedAt: string | null
  useCount: number
}

/** How an examination's passwords stand, without saying what any of them are. */
export interface ExamPasswordStatus {
  /** False when CPINTEL_EXAM_PASSWORD_KEY is unset, so nothing can be generated. */
  keyConfigured: boolean
  examPasswordSet: boolean
  examPasswordSetAt: string | null
  examPasswordSetBy: string | null
  /** Bumped by each rotation; every session opened under an older one is ended. */
  generation: number
  passcodesIssued: number
  participantCount: number
}

/** Everything one examination session can produce. Matches the backend enum exactly. */
export type ExamEventType =
  | 'EXAM_STARTED' | 'EXAM_ENTERED' | 'PROBLEM_OPENED' | 'PROBLEM_SUBMITTED'
  | 'SUBMISSION_RESULT' | 'FOCUS_LOST' | 'FOCUS_REGAINED' | 'AWAY_THRESHOLD_EXCEEDED'
  | 'EXAM_EXITED' | 'EXAM_COMPLETED' | 'SESSION_TERMINATED' | 'LOCKDOWN_TRIGGERED'
  | 'SUSPICIOUS_ACTIVITY'

export interface ExamClientEvent {
  /** Client-generated and stable across retries, so a dropped connection cannot inflate a log. */
  eventId: string
  type: ExamEventType
  problemLabel?: string | null
  durationMs?: number | null
  detail?: string | null
  occurredAt: string
}

export interface ExamLogEntry {
  id: number
  userId: number
  username: string
  type: ExamEventType
  problemLabel: string | null
  durationMs: number | null
  detail: string | null
  occurredAt: string
  recordedAt: string
}

export interface ExamLogPage {
  event: EventSummary
  entries: ExamLogEntry[]
  page: number
  size: number
  total: number
  totalPages: number
  types: ExamEventType[]
  retentionDays: number
}

/** One candidate on the invigilator's dashboard. Every figure is an observation. */
export interface ExamMonitorRow {
  userId: number
  username: string
  fullName: string | null
  status: 'NOT_STARTED' | 'ACTIVE' | 'AWAY' | 'SUBMITTED' | 'LEFT'
  monitorAlive: boolean
  focused: boolean
  focusLosses: number
  awayMs: number
  lastActivityAt: string | null
  submissions: number
  currentProblem: string | null
  problemsAttempted: number
  events: number
}

export interface ExamMonitorSnapshot {
  event: EventSummary
  rows: ExamMonitorRow[]
  generatedAt: string
  expected: number
  present: number
  away: number
  notStarted: number
}

export type ExamFlagKind =
  | 'LONG_AWAY' | 'FREQUENT_AWAY' | 'MULTIPLE_SIGN_INS' | 'FAST_SUBMISSION' | 'LOCKDOWN'
  | 'FLAGGED'

/** Something in a session worth a human look — computed from the log, never a finding. */
export interface ExamFlag {
  userId: number
  username: string
  fullName: string | null
  kind: ExamFlagKind
  severity: 'HIGH' | 'MEDIUM'
  problemLabel: string | null
  detail: string | null
  occurredAt: string | null
}

export interface ExamFlagReport {
  flags: ExamFlag[]
  longAwaySeconds: number
  frequentAwayCount: number
  fastSubmissionSeconds: number
}

export interface ExamLeaderboardSettings {
  enabled: boolean
  refreshMinutes: number
  /** Added per wrong attempt before a solve. 0 means no penalty. */
  penaltyMinutes: number
  /** Whether candidates see the final board, in normal mode, once the paper is over. */
  finalPublic: boolean
}

export interface ExamLeaderboardCell {
  label: string
  solved: boolean
  wrongAttempts: number
  /** Seconds from the start to when the accepted code was sent. */
  solvedAtSeconds: number | null
  pending: boolean
  firstSolve: boolean
  /** Marks earned on it: the problem's marks once solved, otherwise zero. */
  marks: number
}

export interface ExamLeaderboardRow {
  rank: number
  userId: number
  username: string
  fullName: string | null
  solved: number
  /** Marks for the problems solved — what the board ranks by first. */
  score: number
  totalSeconds: number
  cells: ExamLeaderboardCell[]
}

export interface ExamLeaderboardStandings {
  problems: string[]
  /** What each problem is worth, by label. One each when no marks were set. */
  marks: Record<string, number>
  /** Whether the admin set any marks, as opposed to the one-per-problem fallback. */
  marked: boolean
  rows: ExamLeaderboardRow[]
  penaltyMinutes: number
  pendingSubmissions: number
  generatedAt: string
}

export interface ExamLeaderboard {
  eventId: number
  eventName: string
  state: 'DISABLED' | 'NOT_STARTED' | 'LIVE' | 'FINAL' | 'UNPUBLISHED'
  settings: ExamLeaderboardSettings
  standings: ExamLeaderboardStandings | null
  nextRefreshAt: string | null
}

export interface ParticipationRow {
  eventId: number
  kind: EventKind
  name: string
  platform: GroupPlatform
  startsAt: string | null
  endsAt: string | null
  lifecycle: EventLifecycle
  rank: number | null
  groupSize: number | null
  solved: number | null
  penalty: number | null
  score: number | null
  entered: boolean
  submissions: number
  focusLosses: number
}

export interface TeamEventRow {
  eventId: number
  kind: EventKind
  name: string
  startsAt: string | null
  lifecycle: EventLifecycle
  ranked: number
  averageSolved: number
  bestRank: number | null
  bestMember: string | null
}

export interface TeamAnalytics {
  teamId: number
  name: string
  memberCount: number
  events: number
  contests: number
  exams: number
  participants: number
  participationRate: number
  averageSolved: number
  averageScore: number
  totalSolved: number
  recent: TeamEventRow[]
}
