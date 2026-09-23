import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import { adminEventsApi, examsApi, type EventBody, type LogQuery } from '@/api/examsApi'
import { useToast } from '@/components/common/Toaster'
import { useIsAdmin } from '@/hooks/useAdmin'
import type { EventKind, EventLifecycle, EventProblem } from '@/types'

// ------------------------------------------------------------- candidate

/**
 * The examinations assigned to this account.
 *
 * Polled on a slow timer rather than read once: an examination published while somebody is
 * already looking at this screen is the normal case on the morning of a sitting, and asking
 * them to reload is how a candidate misses the start.
 */
export function useMyExams() {
  return useQuery({
    queryKey: ['exams', 'mine'],
    queryFn: () => examsApi.mine().then(r => r.data),
    refetchInterval: 60_000,
  })
}

export function useMyExam(examId: number | null) {
  return useQuery({
    queryKey: ['exams', 'detail', examId],
    queryFn: () => examsApi.detail(examId!).then(r => r.data),
    enabled: examId != null,
    // The clock is computed locally; this is re-read so a change an invigilator makes — ending
    // it early, extending it — reaches the page without a reload.
    refetchInterval: 60_000,
  })
}

export function useEnterExam() {
  const qc = useQueryClient()
  const toast = useToast()
  return useMutation({
    mutationFn: (examId: number) => examsApi.enter(examId).then(r => r.data),
    onSuccess: (exam) => {
      qc.invalidateQueries({ queryKey: ['exams'] })
      qc.setQueryData(['exams', 'detail', exam.event.eventId], exam)
    },
    onError: (err: any) => {
      toast.push('error', err.response?.data?.message ?? 'Could not open that examination')
    },
  })
}

/**
 * Unlocking a live paper with the passwords handed out in the room.
 *
 * <p>The refusal is deliberately not a toast. A candidate typing a code off a slip, under a
 * clock, needs the message next to the boxes they are about to retype — a notification that
 * slides away three seconds later is the wrong shape for "check both of these and try again".
 * So the error is returned to the caller and shown in the form.
 */
export function useUnlockExam() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ examId, examPassword, passcode }:
      { examId: number; examPassword?: string; passcode?: string }) =>
      examsApi.unlock(examId, { examPassword, passcode }).then(r => r.data),
    onSuccess: (exam) => {
      // The paper's problems arrive with the unlock — they are withheld until then — so this
      // is a replacement rather than a hint to refetch.
      qc.setQueryData(['exams', 'detail', exam.event.eventId], exam)
      qc.invalidateQueries({ queryKey: ['exams', 'mine'] })
    },
  })
}

/**
 * What this candidate submitted into a paper that is over.
 *
 * Only ever asked for once the examination has ended — the server refuses otherwise — so there
 * is nothing to poll and no interval here.
 */
export function useMyExamSubmissions(examId: number | null, enabled = true) {
  return useQuery({
    queryKey: ['exams', 'submissions', examId],
    queryFn: () => examsApi.submissions(examId!).then(r => r.data),
    enabled: enabled && examId != null,
    staleTime: 5 * 60_000,
  })
}

/** One of those, with the source. Fetched only when somebody opens it. */
export function useMyExamSubmission(examId: number | null, submissionId: string | null) {
  return useQuery({
    queryKey: ['exams', 'submission', examId, submissionId],
    queryFn: () => examsApi.submission(examId!, submissionId!).then(r => r.data),
    enabled: examId != null && submissionId != null,
    staleTime: Infinity,
  })
}

// ----------------------------------------------------------------- admin

export function useAdminEvents(kind: EventKind) {
  const isAdmin = useIsAdmin()
  return useQuery({
    queryKey: ['admin', 'events', kind],
    queryFn: () => adminEventsApi.list(kind).then(r => r.data),
    enabled: isAdmin,
  })
}

export function useAdminEvent(eventId: number | null) {
  const isAdmin = useIsAdmin()
  return useQuery({
    queryKey: ['admin', 'event', eventId],
    queryFn: () => adminEventsApi.detail(eventId!).then(r => r.data),
    enabled: isAdmin && eventId != null,
  })
}

/**
 * The invigilator's dashboard, while an examination is running.
 *
 * Polled hard — every few seconds — because that is the whole point of the screen: an away
 * time that is a minute stale is worse than useless, since it reads as present. The server
 * answers it from two grouped queries and a Redis lookup, so the cost is bounded by the number
 * of candidates rather than by how long the examination has been running.
 */
export function useExamMonitor(eventId: number | null, live = true) {
  const isAdmin = useIsAdmin()
  return useQuery({
    queryKey: ['admin', 'exam-monitor', eventId],
    queryFn: () => adminEventsApi.monitor(eventId!).then(r => r.data),
    enabled: isAdmin && eventId != null,
    refetchInterval: live ? 5_000 : false,
  })
}

export function useExamLogs(eventId: number | null, query: LogQuery) {
  const isAdmin = useIsAdmin()
  return useQuery({
    queryKey: ['admin', 'exam-logs', eventId, query],
    queryFn: () => adminEventsApi.logs(eventId!, query).then(r => r.data),
    enabled: isAdmin && eventId != null,
  })
}

/**
 * The languages an event may be restricted to.
 *
 * Fixed for the life of the deployment, so it is fetched once and kept.
 */
export function useEventLanguageCatalog() {
  const isAdmin = useIsAdmin()
  return useQuery({
    queryKey: ['admin', 'language-catalog'],
    queryFn: () => adminEventsApi.languageCatalog().then(r => r.data),
    enabled: isAdmin,
    staleTime: Infinity,
  })
}

// ------------------------------------------------- examination passwords

/**
 * Whether this examination has passwords, without asking what they are.
 *
 * Safe to load with the rest of the screen. The two reveal calls below are separate and
 * audited server-side, because reading the room's codes is an action somebody took rather than
 * a page that happened to render — and folding them into this query would make every visit to
 * the admin screen look like one.
 */
export function useExamPasswordStatus(eventId: number | null) {
  const isAdmin = useIsAdmin()
  return useQuery({
    queryKey: ['admin', 'exam-passwords', eventId],
    queryFn: () => adminEventsApi.passwordStatus(eventId!).then(r => r.data),
    enabled: isAdmin && eventId != null,
  })
}

export function useGenerateExamPassword() {
  return useEventMutation(
    ({ eventId }: { eventId: number }) => adminEventsApi.generateExamPassword(eventId),
    () => 'Generated. Rotating it ends every session opened with the previous one.',
  )
}

export function useClearExamPassword() {
  return useEventMutation(
    ({ eventId }: { eventId: number }) => adminEventsApi.clearExamPassword(eventId),
    () => 'This examination no longer asks for a shared password',
  )
}

export function useIssuePasscodes() {
  return useEventMutation(
    ({ eventId, regenerate }: { eventId: number; regenerate?: boolean }) =>
      adminEventsApi.issuePasscodes(eventId, regenerate),
    (_args, result) =>
      `${result.data.length} candidate code${result.data.length === 1 ? '' : 's'} ready to print`,
  )
}

export function useReissuePasscode() {
  return useEventMutation(
    ({ eventId, userId }: { eventId: number; userId: number }) =>
      adminEventsApi.reissuePasscode(eventId, userId),
    () => 'A new code has been issued. The old one no longer opens the paper.',
  )
}

export function useRevokePasscodes() {
  return useEventMutation(
    ({ eventId }: { eventId: number }) => adminEventsApi.revokePasscodes(eventId),
    () => 'Every personal code on this examination has been withdrawn',
  )
}

export function useRevokeExamAccess() {
  return useEventMutation(
    ({ eventId, userId }: { eventId: number; userId: number }) =>
      adminEventsApi.revokeAccess(eventId, userId),
    () => 'They have to enter the examination password again',
  )
}

export function useTeamAnalytics(teamId: number | null) {
  const isAdmin = useIsAdmin()
  return useQuery({
    queryKey: ['admin', 'team-analytics', teamId],
    queryFn: () => adminEventsApi.teamAnalytics(teamId!).then(r => r.data),
    enabled: isAdmin && teamId != null,
  })
}

/**
 * Anything that changes an event.
 *
 * Invalidates the whole admin cache rather than one key: assigning a team changes the event's
 * participant count, the monitoring dashboard's roster and the team's own analytics at once.
 */
function useEventMutation<TArgs, TResult>(
  fn: (args: TArgs) => Promise<TResult>,
  success: (args: TArgs, result: TResult) => string,
) {
  const qc = useQueryClient()
  const toast = useToast()

  return useMutation({
    mutationFn: fn,
    onSuccess: (result, args) => {
      qc.invalidateQueries({ queryKey: ['admin'] })
      toast.push('success', success(args, result))
    },
    onError: (err: any) => {
      toast.push('error', err.response?.data?.message ?? 'That change did not go through')
    },
  })
}

export function useCreateEvent() {
  return useEventMutation(
    (body: EventBody) => adminEventsApi.create(body),
    (body) => `${body.name} created as a draft — publish it when it is ready`,
  )
}

export function useUpdateEvent() {
  return useEventMutation(
    ({ eventId, body }: { eventId: number; body: EventBody }) =>
      adminEventsApi.update(eventId, body),
    () => 'Saved',
  )
}

export function useSetLifecycle() {
  return useEventMutation(
    ({ eventId, lifecycle }: { eventId: number; lifecycle: EventLifecycle }) =>
      adminEventsApi.lifecycle(eventId, lifecycle),
    ({ lifecycle }) => ({
      DRAFT:     'Taken back to a draft — nobody can see it now',
      SCHEDULED: 'Published — it opens by itself when its window does',
      ACTIVE:    'Published',
      ENDED:     'Ended — no further submissions are accepted',
      ARCHIVED:  'Archived — kept for the record, out of everybody’s way',
    })[lifecycle],
  )
}

export function useDeleteEvent() {
  return useEventMutation(
    ({ eventId }: { eventId: number }) => adminEventsApi.remove(eventId),
    () => 'Deleted',
  )
}

export function useAssignToEvent() {
  return useEventMutation(
    ({ eventId, teamIds, userIds }:
      { eventId: number; teamIds?: number[]; userIds?: number[] }) =>
      adminEventsApi.assign(eventId, { teamIds, userIds }),
    () => 'Assigned',
  )
}

export function useUnassignTeam() {
  return useEventMutation(
    ({ eventId, teamId }: { eventId: number; teamId: number }) =>
      adminEventsApi.unassignTeam(eventId, teamId),
    () => 'Team removed from this event',
  )
}

export function useUnassignUser() {
  return useEventMutation(
    ({ eventId, userId }: { eventId: number; userId: number }) =>
      adminEventsApi.unassignUser(eventId, userId),
    () => 'Removed from this event',
  )
}

export function useSetProblems() {
  return useEventMutation(
    ({ eventId, problems }:
      { eventId: number; problems: Omit<EventProblem, 'problemId'>[] }) =>
      adminEventsApi.setProblems(eventId, problems),
    ({ problems }) => `${problems.length} problem${problems.length === 1 ? '' : 's'} saved`,
  )
}
