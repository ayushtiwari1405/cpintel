import { apiClient } from './client'
import type {
  ApiResponse, EventDetail, EventKind, EventLifecycle, EventProblem, EventSummary, JudgeProblem,
  ExamClientEvent, ExamFlagReport, ExamLeaderboard, ExamLeaderboardSettings, ExamLogPage,
  ExamMonitorSnapshot, ExamPasswordStatus, ExamEventType,
  IssuedPasscode, LanguageChoice, MyExam, MyExamSubmission, TeamAnalytics,
} from '@/types'

/**
 * Examinations, from both sides.
 *
 * The two halves are deliberately different surfaces on the server — a candidate's routes
 * cannot reach the roster, the monitoring dashboard or anybody else's session — and they are
 * kept apart here for the same reason: it should be obvious at the call site which one a screen
 * is using.
 */

export interface EventBody {
  kind: EventKind
  platform: 'CODEFORCES' | 'DOMJUDGE'
  externalId: string
  name: string
  description?: string | null
  rules?: string | null
  url?: string | null
  startsAt?: string | null
  endsAt?: string | null
  visibility?: string
  lockdownRequired?: boolean
  awayThresholdSeconds?: number
  desktopPolicy?: EventDetail['desktopPolicy']
  allowedLanguages?: string[]
  teamId?: number | null
  teamIds?: number[]
  userIds?: number[]
  problems?: Omit<EventProblem, 'problemId'>[]
  /** Decided with the event and fixed once it starts. */
  personalFilesAllowed?: boolean
}

export interface LogQuery {
  userId?: number | null
  teamId?: number | null
  type?: ExamEventType | ''
  from?: string
  to?: string
  page?: number
  size?: number
}

/** A candidate's own examinations. */
export const examsApi = {
  mine: () =>
    apiClient.get<ApiResponse<EventSummary[]>>('/exams').then(r => r.data),

  detail: (examId: number) =>
    apiClient.get<ApiResponse<MyExam>>(`/exams/${examId}`).then(r => r.data),

  /** Opening the examination, which is itself recorded. */
  enter: (examId: number) =>
    apiClient.post<ApiResponse<MyExam>>(`/exams/${examId}/enter`, {}).then(r => r.data),

  /**
   * Unlocking a live paper with the passwords handed out in the room.
   *
   * The third of the three things that have to be true before somebody is inside an
   * examination: assigned, its window open, and given the passwords at the desk. Only the last
   * is decided by the invigilator standing in the room, which is why it cannot be derived from
   * either of the others.
   */
  unlock: (examId: number, body: { examPassword?: string; passcode?: string }) =>
    apiClient.post<ApiResponse<MyExam>>(`/exams/${examId}/unlock`, body).then(r => r.data),

  /**
   * What you submitted into a paper that is over. Your own code, and nothing else.
   *
   * Refused while the examination is running: there is nothing to recover then, since the
   * editor still holds it.
   */
  submissions: (examId: number) =>
    apiClient.get<ApiResponse<MyExamSubmission[]>>(`/exams/${examId}/submissions`)
      .then(r => r.data),

  /** One of those, with the source. The list deliberately carries none. */
  submission: (examId: number, submissionId: string) =>
    apiClient.get<ApiResponse<MyExamSubmission>>(
      `/exams/${examId}/submissions/${submissionId}`).then(r => r.data),

  /** The exam's leaderboard, when the admin shows one: live during it, final after it. */
  leaderboard: (examId: number) =>
    apiClient.get<ApiResponse<ExamLeaderboard>>(`/exams/${examId}/leaderboard`)
      .then(r => r.data),

  /**
   * What this candidate's own client observed.
   *
   * Batched, and safe to retry: every event carries an id the server treats as idempotent, so
   * resending a queue after a dropped connection cannot turn one absence into five.
   */
  report: (examId: number, events: ExamClientEvent[]) =>
    apiClient.post<ApiResponse<{ stored: number }>>(`/exams/${examId}/events`, { events })
      .then(r => r.data),

  /**
   * Says the monitoring is still running.
   *
   * Distinct from the report above, which only speaks when something happened — and silence
   * from somebody behaving perfectly looks exactly like silence from a closed window.
   */
  heartbeat: (examId: number) =>
    apiClient.post<ApiResponse<{ intervalSeconds: number }>>(
      `/exams/${examId}/monitor/heartbeat`, {}).then(r => r.data),
}

/** Contests and examinations as an admin runs them. Server-enforced console role. */
export const adminEventsApi = {
  list: (kind: EventKind) =>
    apiClient.get<ApiResponse<EventSummary[]>>('/admin/events', { params: { kind } })
      .then(r => r.data),

  detail: (eventId: number) =>
    apiClient.get<ApiResponse<EventDetail>>(`/admin/events/${eventId}`).then(r => r.data),

  create: (body: EventBody) =>
    apiClient.post<ApiResponse<EventDetail>>('/admin/events', body).then(r => r.data),

  update: (eventId: number, body: EventBody) =>
    apiClient.put<ApiResponse<EventDetail>>(`/admin/events/${eventId}`, body).then(r => r.data),

  lifecycle: (eventId: number, lifecycle: EventLifecycle) =>
    apiClient.put<ApiResponse<EventDetail>>(`/admin/events/${eventId}/lifecycle`, { lifecycle })
      .then(r => r.data),

  remove: (eventId: number) =>
    apiClient.delete<ApiResponse<void>>(`/admin/events/${eventId}`).then(r => r.data),

  assign: (eventId: number, body: { teamIds?: number[]; userIds?: number[] }) =>
    apiClient.post<ApiResponse<EventDetail>>(`/admin/events/${eventId}/assignments`, body)
      .then(r => r.data),

  unassignTeam: (eventId: number, teamId: number) =>
    apiClient.delete<ApiResponse<EventDetail>>(
      `/admin/events/${eventId}/assignments/teams/${teamId}`).then(r => r.data),

  unassignUser: (eventId: number, userId: number) =>
    apiClient.delete<ApiResponse<EventDetail>>(
      `/admin/events/${eventId}/assignments/users/${userId}`).then(r => r.data),

  /** The linked DOMjudge contest's problems, which the Problems tab starts from. */
  judgeProblems: (eventId: number) =>
    apiClient.get<ApiResponse<JudgeProblem[]>>(`/admin/events/${eventId}/judge-problems`)
      .then(r => r.data),

  /** The whole list at once: ordering and marks are a set that has to stay consistent. */
  setProblems: (eventId: number, problems: Omit<EventProblem, 'problemId'>[]) =>
    apiClient.put<ApiResponse<EventProblem[]>>(`/admin/events/${eventId}/problems`, { problems })
      .then(r => r.data),

  monitor: (eventId: number) =>
    apiClient.get<ApiResponse<ExamMonitorSnapshot>>(`/admin/events/${eventId}/monitor`)
      .then(r => r.data),

  leaderboard: (eventId: number) =>
    apiClient.get<ApiResponse<ExamLeaderboard>>(`/admin/events/${eventId}/leaderboard`)
      .then(r => r.data),

  refreshLeaderboard: (eventId: number) =>
    apiClient.post<ApiResponse<ExamLeaderboard>>(
      `/admin/events/${eventId}/leaderboard/refresh`, {}).then(r => r.data),

  leaderboardSettings: (eventId: number, body: ExamLeaderboardSettings) =>
    apiClient.put<ApiResponse<ExamLeaderboard>>(
      `/admin/events/${eventId}/leaderboard/settings`, body).then(r => r.data),

  flags: (eventId: number) =>
    apiClient.get<ApiResponse<ExamFlagReport>>(`/admin/events/${eventId}/flags`)
      .then(r => r.data),

  logs: (eventId: number, query: LogQuery) =>
    apiClient.get<ApiResponse<ExamLogPage>>(`/admin/events/${eventId}/logs`, { params: query })
      .then(r => r.data),

  /**
   * The languages an event's submissions may be restricted to.
   *
   * Wider than what this host can run: an examination on DOMjudge may be Java-only whatever
   * compilers the backend has, and the Run button being unavailable for it is a smaller
   * problem than not being able to set the rule.
   */
  languageCatalog: () =>
    apiClient.get<ApiResponse<LanguageChoice[]>>('/admin/events/languages').then(r => r.data),

  // ----------------------------------------------------- examination passwords
  //
  // Generated here, printed here, and never emailed anywhere: an examination password that
  // arrives in a mailbox defeats the arrangement it belongs to, since the credential in
  // somebody's inbox is exactly what getting into the paper is meant to need more than.
  //
  // Reading them is split from the status deliberately. The status route says whether
  // passwords exist and is safe to load with the page; the two reveal routes hand over the
  // secrets and are audited every time they are called.

  passwordStatus: (eventId: number) =>
    apiClient.get<ApiResponse<ExamPasswordStatus>>(`/admin/events/${eventId}/passwords`)
      .then(r => r.data),

  /** Generates or rotates the password the invigilator gives the room. Returned once. */
  generateExamPassword: (eventId: number) =>
    apiClient.post<ApiResponse<{ password: string }>>(
      `/admin/events/${eventId}/passwords/exam`, {}).then(r => r.data),

  revealExamPassword: (eventId: number) =>
    apiClient.get<ApiResponse<{ password?: string }>>(
      `/admin/events/${eventId}/passwords/exam`).then(r => r.data),

  clearExamPassword: (eventId: number) =>
    apiClient.delete<ApiResponse<void>>(`/admin/events/${eventId}/passwords/exam`)
      .then(r => r.data),

  /**
   * Issues a personal code to every assigned candidate.
   *
   * Additive unless `regenerate`, so a candidate added the morning of the paper gets a slip
   * without invalidating the two hundred already printed.
   */
  issuePasscodes: (eventId: number, regenerate = false) =>
    apiClient.post<ApiResponse<IssuedPasscode[]>>(
      `/admin/events/${eventId}/passwords/candidates`, {}, { params: { regenerate } })
      .then(r => r.data),

  revealPasscodes: (eventId: number) =>
    apiClient.get<ApiResponse<IssuedPasscode[]>>(
      `/admin/events/${eventId}/passwords/candidates`).then(r => r.data),

  /** One candidate's code again, for the slip that went under a radiator at eleven. */
  reissuePasscode: (eventId: number, userId: number) =>
    apiClient.post<ApiResponse<IssuedPasscode>>(
      `/admin/events/${eventId}/passwords/candidates/${userId}`, {}).then(r => r.data),

  revokePasscodes: (eventId: number) =>
    apiClient.delete<ApiResponse<void>>(`/admin/events/${eventId}/passwords/candidates`)
      .then(r => r.data),

  /** Makes one candidate unlock again — for a move to another machine, or a removal. */
  revokeAccess: (eventId: number, userId: number) =>
    apiClient.post<ApiResponse<void>>(
      `/admin/events/${eventId}/access/${userId}/revoke`, {}).then(r => r.data),

  teamAnalytics: (teamId: number) =>
    apiClient.get<ApiResponse<TeamAnalytics>>(`/admin/groups/${teamId}/analytics`)
      .then(r => r.data),
}
