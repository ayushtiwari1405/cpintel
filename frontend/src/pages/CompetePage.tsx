import { useEffect, useMemo, useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { clsx } from 'clsx'
import {
  CheckCircle2, Clock, Eye, FileText, FolderOpen, ListOrdered, Loader2, Maximize, Send, Swords,
  Trophy,
} from 'lucide-react'

import { CfSessionCard } from '@/components/practice/CfSessionCard'
import { StatementView } from '@/components/practice/StatementView'
import { ContestHeader } from '@/components/compete/ContestHeader'
import { ContestLoader } from '@/components/compete/ContestLoader'
import { ProblemNav } from '@/components/compete/ProblemNav'
import { FilesPanel } from '@/components/compete/FilesPanel'
import { LeaderboardPanel } from '@/components/compete/LeaderboardPanel'
import { SubmissionsList } from '@/components/compete/SubmissionsList'
import { ExamLeaderboardView } from '@/components/exam/ExamLeaderboardView'
import { ExamList } from '@/components/exam/ExamList'
import { ExamUnlock } from '@/components/exam/ExamUnlock'
import { PastExamCode } from '@/components/exam/PastExamCode'
import { PastExamProblems } from '@/components/exam/PastExamProblems'
import { ExamNotices } from '@/components/exam/ExamNotices'
import { EditorPane } from '@/components/workspace/EditorPane'
import { SplitPane } from '@/components/workspace/SplitPane'
import { TabStrip } from '@/components/workspace/TabStrip'

import {
  useContest, useContestLanguages, useContestRank, useContestStatement,
  useContestSubmissions, useContestSubmit, useLeaderboard, useLoadContest, useStatementPdf,
  useTicker,
} from '@/hooks/useCompete'
import { useCfSession } from '@/hooks/usePractice'
import { useEnterExam, useMyExam, useMyExamLeaderboard, useMyExams } from '@/hooks/useExams'
import { useExamSession } from '@/hooks/useExamSession'
import { useLockdown } from '@/hooks/useLockdown'
import { useLogout } from '@/hooks/useAuth'
import { useAwayMonitor } from '@/hooks/useAwayMonitor'
import { useActiveGroupContest } from '@/hooks/useGroups'
import { useMonitorHeartbeat } from '@/hooks/useMonitorHeartbeat'
import { useViolationReporter } from '@/hooks/useViolationReporter'
import { archiveApi } from '@/api/archiveApi'
import { useToast } from '@/components/common/Toaster'
import { useAuthStore } from '@/store/authStore'
import { useExamMode } from '@/store/examModeStore'
import type {
  CompetePlatform, ContestRef, ContestSubmission, MyExam, VerdictResponse,
} from '@/types'
import { parseSamples } from '@/utils/parseSamples'

/** Survives a refresh mid-contest, which matters when the clock is running. */
const STORAGE_KEY = 'cpintel.compete.contest'

/** Which half of the arena was open: an ordinary contest, or an assigned examination. */
const MODE_KEY = 'cpintel.compete.mode'

/** The examination being sat, so a reload mid-paper lands back in it rather than on the list. */
const EXAM_KEY = 'cpintel.compete.exam'

/**
 * Whether this candidate may be inside this paper right now.
 *
 * <p>The server decides — `unlocked` is its answer, and every route into the examination is
 * checked against the same grant, so this is presentation rather than protection. It is a
 * function rather than an inline test because three places ask it, and three copies of
 * "requiresPassword ? unlocked : true" is three chances for one of them to drift into letting
 * somebody past the door.
 *
 * <p>A paper that asks for no password is always open: an admin who generated nothing has not
 * decided to require anything, and inventing the requirement would shut a room out of a paper
 * that was about to start.
 */
function examUnlocked(exam: MyExam): boolean {
  // Only a session signed in with this paper's examination password is ever inside it; the
  // server's `unlocked` already says so, and this keeps the screens from pretending otherwise.
  return exam.examSession && exam.unlocked
}

/** What the old single-judge build stored: a bare Codeforces contest number. */
const LEGACY_STORAGE_KEY = 'cpintel.compete.contestId'

/**
 * The contest this browser was last in, across a refresh.
 *
 * Reads the old key as well, so someone who reloads mid-round after an upgrade lands back in
 * their contest rather than on an empty picker. A bare number can only ever have been a
 * Codeforces id, which is what makes that migration unambiguous.
 */
function restoreContest(): ContestRef | null {
  try {
    const saved = localStorage.getItem(STORAGE_KEY)
    if (saved) {
      const parsed = JSON.parse(saved)
      if (parsed?.platform && parsed?.id) return parsed as ContestRef
    }
    const legacy = localStorage.getItem(LEGACY_STORAGE_KEY)
    if (legacy && Number(legacy)) return { platform: 'CODEFORCES', id: legacy }
  } catch {
    // A corrupt entry is not worth failing the page over; start from the picker.
  }
  return null
}

type Mode = 'contests' | 'exams'

function restoreMode(): Mode {
  return localStorage.getItem(MODE_KEY) === 'exams' ? 'exams' : 'contests'
}

function restoreExamId(): number | null {
  const saved = Number(localStorage.getItem(EXAM_KEY))
  return Number.isFinite(saved) && saved > 0 ? saved : null
}

/**
 * @param examOnly set in examination mode (signed in with a paper's examination password):
 *   the page is that paper and nothing else — no contests, no list, no way to another one.
 * @param section which half of the arena this route is: Compete holds the contests and
 *   Exams the examinations, each with its own place in the sidebar. Left out, the page
 *   offers both behind a switch and remembers the last one.
 */
export default function CompetePage({ examOnly, section }: {
  examOnly?: number
  section?: Mode
} = {}) {
  const qc = useQueryClient()
  const logout = useLogout()

  const [mode, setMode] =
    useState<Mode>(() => examOnly != null ? 'exams' : section ?? restoreMode())
  const [examId, setExamId] = useState<number | null>(() => examOnly ?? restoreExamId())
  // The saved contest is the Compete side's. On the examinations side the contest is always
  // the open paper's, and saving it there would reopen the paper's judge contest in Compete.
  const [contestRef, setContestRef] = useState<ContestRef | null>(
    () => examOnly != null || section === 'exams' ? null : restoreContest())
  const [selected, setSelected] = useState<string | null>(null)
  const [languageId, setLanguageId] = useState('')
  const [filesOpen, setFilesOpen] = useState(false)
  const [confirmFinish, setConfirmFinish] = useState(false)
  const [finishing, setFinishing] = useState(false)
  const [tab, setTab] =
    useState<'description' | 'problems' | 'submissions' | 'leaderboard'>('description')
  // Each problem keeps its own buffer — switching tabs must not lose work.
  const [sources, setSources] = useState<Record<string, string>>({})
  /** The submission being fetched to reopen, so its row can show it is on the way. */
  const [opening, setOpening] = useState<string | null>(null)
  const toast = useToast(s => s.push)

  const { data: session } = useCfSession()
  const load = useLoadContest()

  /**
   * The examination half of the arena.
   *
   * An examination is not something a candidate pastes a link to: it is assigned to them, and
   * entering it is itself recorded. So this half has no loader — the list is the server's
   * answer to "what am I sitting", and picking one binds the workspace below to the DOMjudge
   * contest behind it.
   */
  const { data: myExams, isLoading: examsLoading } = useMyExams()
  const { data: exam, dataUpdatedAt: examUpdatedAt } =
    useMyExam(mode === 'exams' ? examId : null)
  const enterExam = useEnterExam()

  useEffect(() => {
    if (examOnly != null || section != null) return
    localStorage.setItem(MODE_KEY, mode)
  }, [mode, examOnly, section])

  useEffect(() => {
    if (examOnly != null) return
    if (examId == null) localStorage.removeItem(EXAM_KEY)
    else localStorage.setItem(EXAM_KEY, String(examId))
  }, [examId, examOnly])

  /**
   * An examination drives the workspace rather than the other way round.
   *
   * The contest reference is derived from the examination's own judge and contest id, so there
   * is no moment where the page is showing one thing and reporting about another — which is
   * the failure that would put one candidate's session under somebody else's name.
   */
  useEffect(() => {
    if (mode !== 'exams' || !exam) return
    // Not before it is unlocked. Loading the judge's contest is what puts the statements on
    // the screen, so doing it for a paper whose password has not been given would make the
    // lock decorative — the workspace would already be sitting behind the door.
    if (!examUnlocked(exam)) return
    const next = { platform: exam.event.platform as CompetePlatform, id: exam.event.externalId }
    setContestRef(current =>
      current && current.platform === next.platform && current.id === next.id ? current : next)
  }, [mode, exam])

  /**
   * Whether this judge needs a per-user credential before anything works.
   *
   * Only Codeforces does. A DOMjudge round is submitted on the contestant's behalf by the
   * deployment's own admin account, so there is nothing for them to connect and the session
   * card must not be shown — offering it would ask 200 people to solve a problem they do not
   * have.
   */
  const needsCfSession = (contestRef?.platform ?? 'CODEFORCES') === 'CODEFORCES'
  const credentialsReady = !needsCfSession || !!session?.connected

  const { data: contest, dataUpdatedAt, error: contestError } = useContest(contestRef ?? undefined)
  const started = !!contest && contest.phase !== 'BEFORE'

  const { data: statement, isLoading: statementLoading } =
    useContestStatement(contestRef ?? undefined, selected ?? undefined, started)
  const {
    url: statementPdfUrl, text: statementText, error: statementPdfError,
  } = useStatementPdf(
    contestRef ?? undefined, selected ?? undefined,
    started && !!statement?.statementPdfUrl)
  // DOMjudge 8.0 gives a team account no sample files, so a text statement's own examples
  // are the samples. The judge's files still win whenever it does serve them.
  const samples = useMemo(
    () => (statement?.samples?.length ? statement.samples : parseSamples(statementText)),
    [statement?.samples, statementText])
  const { data: languages } = useContestLanguages(contestRef ?? undefined, started)
  const { data: submissions, isFetching: submissionsFetching } =
    useContestSubmissions(contestRef ?? undefined, credentialsReady)
  const { data: rank } = useContestRank(contestRef ?? undefined,
    credentialsReady && started)
  const submit = useContestSubmit(contestRef ?? undefined)

  /**
   * The full board, fetched only while its tab is open.
   *
   * Gating on the tab rather than on the contest keeps a panel nobody is looking at from
   * polling the judge for the whole round — the header's own rank already covers the number
   * most contestants actually want.
   */
  const { data: leaderboard, isFetching: leaderboardFetching } = useLeaderboard(
    contestRef ?? undefined,
    tab === 'leaderboard' && credentialsReady && !(mode === 'exams' && !!exam))

  // Tick once a second so the countdown moves between contest refetches.
  useTicker((!!contest && (contest.phase === 'BEFORE' || contest.running))
    || (mode === 'exams' && !!exam))
  const elapsed = dataUpdatedAt ? (Date.now() - dataUpdatedAt) / 1000 : 0
  // Counted from the server's figure rather than this machine's clock, which in a lab may
  // well be wrong; the exam is re-read every minute, so an extension reaches it too.
  const examSecondsRemaining = exam
    ? exam.secondsRemaining - (examUpdatedAt ? (Date.now() - examUpdatedAt) / 1000 : 0)
    : undefined

  const inExam = mode === 'exams' && !!exam

  /**
   * An examination has its own leaderboard, ranked by CPIntel from when each answer was sent,
   * in place of the judge's scoreboard. Fetched while its tab is open during the paper, and on
   * the review screen once it is over.
   */
  const reviewing = inExam && !!exam?.canReviewSubmissions
  const { data: examBoard, isLoading: examBoardLoading } = useMyExamLeaderboard(
    inExam && (reviewing || tab === 'leaderboard') ? examId : null, !reviewing)
  const meId = useAuthStore(s => s.user?.userId)
  /**
   * Whether what is open is live, and so watched.
   *
   * An examination is live for its own window, not its judge contest's: a paper can be set on
   * a contest that runs for days, and reading a finished paper back must not switch the
   * monitoring on again.
   */
  const roundLive = mode === 'exams'
    ? !!exam && exam.event.lifecycle === 'ACTIVE' && (examSecondsRemaining ?? 0) > 0
    : !!contest?.running

  /**
   * The desktop lock, held for exactly as long as the round is live.
   *
   * Tied to `contest.running` rather than to a button: there is no moment where someone has to
   * remember to switch it on, and no moment where they can quietly switch it off. It releases
   * itself when the contest ends, when the contest is closed, and when this page unmounts.
   * In the browser build this does nothing and returns null.
   */
  const desktopMonitor = useLockdown({
    active: roundLive,
    reason: contest?.name ?? 'Contest',
    // An examination carries its own restrictions and its own away threshold; an ordinary
    // contest sends none and the client keeps its defaults.
    policy: exam ? {
      awayWarnMs: exam.event.awayThresholdSeconds * 1000,
      ...exam.desktopPolicy,
    } : undefined,
  })

  /**
   * The browser build watches the tab instead.
   *
   * Weaker — a page can be throttled in a background tab, and closed outright — which is why a
   * contest sat this way also reports that no desktop monitor was present. But leaving the
   * contest is leaving it either way, and saying nothing at all in the browser would make the
   * web build look like the clean way to sit a monitored round.
   */
  const browserMonitor = useAwayMonitor(
    roundLive,
    // An examination sets its own threshold; a contest uses the default. What counts as
    // leaving a two-hour written paper is not what counts as leaving a five-hour round.
    exam ? exam.event.awayThresholdSeconds * 1000 : undefined)
  const lockdown = desktopMonitor ?? browserMonitor

  /**
   * Whether this round is being run for a group an admin is watching.
   *
   * The compete page only knows which Codeforces contest is open — it has no idea a group was
   * laid over it — so the server is asked. Answers null for ordinary practice, which is the
   * common case, and then nothing is reported anywhere.
   */
  const { data: groupContest } = useActiveGroupContest(
    contestRef?.platform ?? 'CODEFORCES', contestRef?.id ?? null)

  /**
   * An examination reports through its own session, not through the contest's violation feed.
   *
   * Both would otherwise record the same absence twice, in two tables, and an invigilator
   * comparing the two would find numbers that disagree for no reason anybody could explain.
   * The examination log is the wider record — it holds the ordinary course of the session as
   * well — so it is the one an examination uses.
   */
  const examSession = useExamSession({ exam: inExam ? exam : null, lockdown })

  // Tells the app's frame a paper is live, so it stops offering other pages. Cleared on the way
  // out, including when the paper ends while this page is still open.
  const setExamLive = useExamMode(s => s.setLive)
  const examLive = inExam && roundLive
  useEffect(() => {
    setExamLive(examLive)
    return () => setExamLive(false)
  }, [examLive, setExamLive])

  useViolationReporter({
    contestId: !inExam && groupContest?.lockdownRequired ? groupContest.contestId : null,
    active: !!contest?.running,
    lockdown,
  })

  /**
   * Says the monitor is still running, on a timer, whether or not anything has happened.
   *
   * The reporter above only speaks when it has something to report, so its silence is
   * ambiguous — perfect behaviour and a closed monitor look identical from the server. This is
   * what the submission gate reads, so without it a monitored round would refuse every
   * submission.
   */
  useMonitorHeartbeat({
    // The examination's own session sends its heartbeat, to the examination's route. Sending
    // both would have two timers vouching for the same monitor.
    contestId: !inExam && groupContest?.lockdownRequired ? groupContest.contestId : null,
    active: !!contest?.running,
  })

  const rows = useMemo(() => submissions ?? [], [submissions])

  useEffect(() => {
    if (mode === 'exams') return
    if (contestRef) localStorage.setItem(STORAGE_KEY, JSON.stringify(contestRef))
    else localStorage.removeItem(STORAGE_KEY)
    // The old key is cleared either way, so the migration above runs exactly once.
    localStorage.removeItem(LEGACY_STORAGE_KEY)
  }, [contestRef, mode])

  // Land on the first problem as soon as Codeforces publishes the list.
  useEffect(() => {
    if (!selected && contest?.problems?.length) setSelected(contest.problems[0].index)
  }, [contest, selected])

  useEffect(() => {
    if (!languageId && languages?.length) setLanguageId(languages[0].id)
  }, [languages, languageId])

  // A resolved verdict is the one event that can move the rank, so re-read it then rather
  // than polling standings on a fast timer.
  const finishedCount = rows.filter(s => s.finished).length
  useEffect(() => {
    if (contestRef && finishedCount > 0) {
      qc.invalidateQueries({
        queryKey: ['compete', 'rank', contestRef.platform, contestRef.id],
      })
    }
  }, [finishedCount, contestRef, qc])

  /**
   * Opening a problem is part of the examination record.
   *
   * Recorded from the page rather than from the request the statement is fetched with: a
   * prefetch or a retry is not somebody reading a problem, and a log that could not tell those
   * apart would show candidates opening problems they never saw.
   *
   * The dependency is the one callback rather than the whole session, deliberately. The session
   * object changes identity whenever the monitor's counters move — which, while somebody is
   * away, is once a second — and depending on it would file a fresh "problem opened" every
   * tick.
   */
  const recordProblemOpened = examSession.problemOpened
  useEffect(() => {
    if (!inExam || !selected) return
    recordProblemOpened(selected)
  }, [inExam, selected, recordProblemOpened])

  const handleLoad = (platform: CompetePlatform, url: string) => {
    load.mutate({ platform, url }, {
      onSuccess: (res) => {
        setContestRef({ platform, id: res.data.id })
        setSelected(null)
        setSources({})
        setTab('description')
      },
    })
  }

  /**
   * Reopens a submission: its problem on the left, its code in the editor on the right.
   *
   * The judge's list carries no source, so the code comes out of CPIntel's archive, matched on
   * the judge's submission id. The same query key as the history panel's, so a list that panel
   * already fetched is reused rather than asked for again.
   */
  const openSubmission = async (sub: ContestSubmission) => {
    if (!contestRef || !sub.index) return
    const index = sub.index
    setSelected(index)
    setTab('description')
    setOpening(sub.id)
    try {
      const page = await qc.fetchQuery({
        queryKey: ['archive', 'problem', contestRef.platform, contestRef.id, index],
        queryFn: () => archiveApi.forProblem(contestRef.platform, contestRef.id, index)
          .then(r => r.data),
        staleTime: 15_000,
      })
      const attempt = page.attempts.find(a => String(a.externalId) === sub.id)
      if (!attempt) {
        toast('info', 'That submission was not sent through CPIntel, so its code is not here.')
        return
      }
      const found = attempt.id
        ? await archiveApi.source(attempt.id).then(r => r.data)
        : await archiveApi.codeforcesSource(contestRef.id, attempt.externalId!).then(r => r.data)

      const current = (sources[index] ?? '').trim()
      if (current && current !== found.source.trim() && !window.confirm(
        `Replace what is in the editor for ${index} with this submission?`)) return

      setSources(prev => ({ ...prev, [index]: found.source }))
      // Only if the judge still offers that compiler; a dead id would fail at submit instead.
      if (found.languageId && languages?.some(l => l.id === found.languageId)) {
        setLanguageId(found.languageId)
      }
    } catch (err: any) {
      toast('error', err?.response?.data?.message ?? 'Could not open that submission.')
    } finally {
      setOpening(null)
    }
  }

  const closeContest = () => {
    setContestRef(null)
    setSelected(null)
    setSources({})
    setLanguageId('')
    // Leaving an examination returns to the list rather than to the contest loader: a
    // candidate has no link to paste, and the examination they were in is still theirs.
    setExamId(null)
  }

  /*
   * Examination mode records entering once the paper is open to this session — on arrival, or
   * the moment its window opens while the candidate waits here.
   */
  const enterNow = examOnly != null && !!exam && exam.examSession
    && exam.event.lifecycle === 'ACTIVE' && exam.unlocked && !exam.entered
  useEffect(() => {
    if (enterNow && !enterExam.isPending) enterExam.mutate(examOnly!)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [enterNow])

  /** The two halves of the arena, as one strip. Examination mode has only the one. */
  const modeStrip = examOnly != null || section != null ? null : (
    <div className="flex items-center gap-1 rounded-lg border border-gray-800 bg-gray-900 p-1">
      {([
        { id: 'contests', label: 'Contests', icon: Swords },
        { id: 'exams', label: 'Examinations', icon: FileText },
      ] as const).map(({ id, label, icon: Icon }) => (
        <button
          key={id}
          onClick={() => { setMode(id); if (id === 'contests') setExamId(null) }}
          className={clsx(
            'flex items-center gap-1.5 rounded-md px-3 py-1.5 text-xs font-medium',
            'transition-colors',
            mode === id
              ? 'bg-indigo-600/20 text-indigo-300'
              : 'text-gray-500 hover:text-gray-300'
          )}
        >
          <Icon size={13} />
          {label}
          {id === 'exams' && (myExams?.length ?? 0) > 0 && (
            <span className="rounded-full bg-gray-800 px-1.5 text-[10px] text-gray-400">
              {myExams!.length}
            </span>
          )}
        </button>
      ))}
    </div>
  )

  /*
   * A paper that is over: their own code, and nothing else.
   *
   * Handled before the loader below, because there is no judge contest to open — the
   * examination has ended, and what a candidate wants from it is the work they did, read out
   * of CPIntel's own archive rather than fetched from DOMjudge.
   */
  if (mode === 'exams' && exam && exam.canReviewSubmissions && examOnly != null) {
    // The paper is over; examination mode has nothing left to show. The code is kept, and is
    // read back from the ordinary session, under Past examinations.
    return (
      <div className="flex flex-1 items-center justify-center p-6">
        <div className="max-w-md rounded-2xl border border-gray-800 bg-gray-900 p-7 text-center">
          <CheckCircle2 size={26} className="mx-auto text-indigo-400" />
          <h1 className="mt-3 text-lg font-semibold text-gray-50">{exam.event.name} has ended</h1>
          <p className="mt-2 text-sm leading-relaxed text-gray-400">
            Your submissions are saved. Sign out, then sign in with your own password to read
            the code you submitted under Exams → Past examinations.
          </p>
          <button onClick={() => logout.mutate()} className="btn-primary mt-5">Sign out</button>
        </div>
      </div>
    )
  }

  if (mode === 'exams' && exam && exam.canReviewSubmissions) {
    return (
      <div className="flex-1 min-h-0 overflow-y-auto">
        <div className="mx-auto flex max-w-5xl flex-col gap-4 p-6">
          {modeStrip}

          <div className="flex flex-wrap items-center justify-between gap-3">
            <div className="min-w-0">
              <h1 className="truncate text-lg font-semibold text-gray-50">{exam.event.name}</h1>
              <p className="text-xs text-gray-500">
                This examination has ended. Below are its questions and the code you
                submitted.
              </p>
            </div>
            <button
              onClick={() => setExamId(null)}
              className="rounded-lg border border-gray-800 bg-gray-900 px-3 py-2 text-sm
                text-gray-300 transition-colors hover:bg-gray-800"
            >
              Back to your examinations
            </button>
          </div>

          <section className="flex flex-col gap-2">
            <h2 className="text-sm font-medium text-gray-300">Questions</h2>
            <PastExamProblems exam={exam} />
          </section>

          <section className="flex flex-col gap-2">
            <h2 className="text-sm font-medium text-gray-300">Your code</h2>
            <PastExamCode exam={exam} />
          </section>

          <section className="flex flex-col gap-2">
            <h2 className="text-sm font-medium text-gray-300">Final standings</h2>
            <div className="rounded-xl border border-gray-800 bg-gray-900">
              <ExamLeaderboardView board={examBoard} isLoading={examBoardLoading} meId={meId} />
            </div>
          </section>
        </div>
      </div>
    )
  }

  /*
   * A live paper that has not been opened with the passwords handed out in the room.
   *
   * The third of the three things that have to be true before somebody is inside an
   * examination, and the only one the other two cannot supply: the roster was fixed a
   * fortnight ago and cannot say who is in the room, and the clock cannot say the paper is
   * open for this person, here.
   */
  if (mode === 'exams' && exam && !exam.examSession) {
    // An ordinary session looking at a paper that has not ended: its details, and how to sit it.
    return (
      <div className="flex-1 min-h-0 overflow-y-auto">
        <div className="mx-auto flex max-w-3xl flex-col gap-4 p-6">
          {modeStrip}
          <div className="rounded-2xl border border-gray-800 bg-gray-900 p-7">
            <h1 className="text-lg font-semibold text-gray-50">{exam.event.name}</h1>
            <p className="mt-2 text-sm leading-relaxed text-gray-400">
              Examinations are sat in examination mode. When your invigilator says so, sign out
              and sign in again with your username and the examination password on your slip —
              not your own password. That opens this paper and nothing else.
            </p>
            <p className="mt-2 text-xs text-gray-500">
              Your ordinary session cannot open, submit to, or change anything in the paper.
              Once it ends, your code appears here under Past examinations.
            </p>
            <button onClick={() => setExamId(null)}
              className="mt-5 rounded-lg border border-gray-800 bg-gray-900 px-3 py-2 text-sm
                text-gray-300 transition-colors hover:bg-gray-800">
              Back to your examinations
            </button>
          </div>
        </div>
      </div>
    )
  }

  if (mode === 'exams' && exam && !examUnlocked(exam)) {
    return (
      <div className="flex-1 min-h-0 overflow-y-auto">
        <div className="mx-auto flex max-w-3xl flex-col gap-4 p-6">
          {modeStrip}
          <ExamUnlock exam={exam}
            onLeave={examOnly != null ? undefined : () => setExamId(null)} />
        </div>
      </div>
    )
  }

  if (mode === 'exams' && (!examId || !contest)) {
    return (
      <div className="flex-1 min-h-0 overflow-y-auto">
        <div className="mx-auto flex max-w-3xl flex-col gap-4 p-6">
          {modeStrip}

          {examOnly == null && <ExamList
            exams={myExams}
            isLoading={examsLoading}
            entering={enterExam.isPending ? enterExam.variables ?? null : null}
            onEnter={(id: number) => {
              setExamId(id)
              setSelected(null)
              setSources({})
              setTab('description')
              // Entering is recorded by the examination session itself; an ordinary session
              // only looks — at the paper's details, or at its own code once it is over.
            }}
          />}

          {examId != null && !contest && !contestError && (
            <div className="flex items-center justify-center gap-2 text-sm text-gray-500">
              <Loader2 size={14} className="animate-spin" /> Opening the examination…
            </div>
          )}

          {/* A judge that will not open the contest for this candidate is the one failure a
              spinner must not hide: it happens minutes before a paper starts, and the person
              needs to know to go and find an invigilator rather than sit watching a wheel. */}
          {examId != null && contestError && (
            <div className="rounded-lg border border-amber-900 bg-amber-950/40 px-3 py-2
              text-xs leading-relaxed text-amber-200">
              <p className="font-medium">The judge would not open this examination.</p>
              <p className="mt-0.5">
                {(contestError as any)?.response?.data?.message
                  ?? 'The contest could not be read from DOMjudge.'}
                {' '}Tell your invigilator — this usually means the account attached to you is
                not registered for it.
              </p>
              {examOnly == null && (
                <button
                  onClick={() => setExamId(null)}
                  className="mt-2 rounded-md border border-amber-900 px-2 py-1 text-amber-200
                             transition-colors hover:bg-amber-900/40"
                >
                  Back to your examinations
                </button>
              )}
            </div>
          )}
        </div>
      </div>
    )
  }

  if (!contestRef || !contest) {
    return (
      <div className="flex-1 min-h-0 overflow-y-auto">
        <div className="mx-auto flex max-w-3xl flex-col gap-4 p-6">
          {modeStrip}
          {needsCfSession && !session?.connected && <CfSessionCard />}
          <ContestLoader onLoad={handleLoad} loading={load.isPending} />
          {contestRef && !contest && (
            <div className="flex items-center justify-center gap-2 text-sm text-gray-500">
              <Loader2 size={14} className="animate-spin" /> Loading contest {contestRef.id}…
            </div>
          )}

          {/* Uploading is a before-the-round job, so the library is reachable from here too. */}
          <button
            onClick={() => setFilesOpen(true)}
            className="mx-auto flex items-center gap-1.5 text-xs text-gray-600
                       hover:text-indigo-300 transition-colors"
          >
            <FolderOpen size={13} /> Your files
          </button>

          <FilesPanel open={filesOpen} onClose={() => setFilesOpen(false)} />
        </div>
      </div>
    )
  }

  // The newest submission for the open problem drives the verdict chip in the console.
  const latest = rows.find(s => s.index === selected)
  const verdict: VerdictResponse | null = latest ? {
    submissionId: latest.id,
    verdict: latest.verdict,
    passedTestCount: latest.passedTestCount ?? undefined,
    timeConsumedMillis: latest.timeConsumedMillis ?? undefined,
    memoryConsumedBytes: latest.memoryConsumedBytes ?? undefined,
    finished: latest.finished,
  } : null

  const source = selected ? (sources[selected] ?? '') : ''
  const pendingCount = rows.filter(s => !s.finished).length

  const leftPane = (
    <div className="flex flex-col flex-1 min-w-0 min-h-0 bg-gray-950">
      <TabStrip
        tabs={[
          { id: 'description', label: 'Description', icon: FileText },
          {
            id: 'problems',
            label: 'Problems',
            icon: ListOrdered,
            badge: contest.problems.length > 0
              ? <span className="text-[10px] text-gray-600">{contest.problems.length}</span>
              : null,
          },
          ...(inExam ? [{
            id: 'leaderboard',
            label: 'Leaderboard',
            icon: Trophy,
            badge: (() => {
              const mine = examBoard?.standings?.rows.find(r => r.userId === meId)
              return mine ? <span className="text-[10px] text-gray-600">#{mine.rank}</span> : null
            })(),
          }] : contestRef.platform === 'DOMJUDGE' ? [{
            id: 'leaderboard',
            label: 'Leaderboard',
            icon: Trophy,
            badge: leaderboard?.myTeam?.rank != null
              ? <span className="text-[10px] text-gray-600">#{leaderboard.myTeam.rank}</span>
              : null,
          }] : []),
          {
            id: 'submissions',
            label: 'Submissions',
            icon: Send,
            // A spinner here is the only sign that something is still being judged once the
            // console has moved on to the next problem.
            badge: pendingCount > 0
              ? <Loader2 size={11} className="animate-spin" />
              : rows.length > 0
                ? <span className="text-[10px] text-gray-600">{rows.length}</span>
                : null,
          },
        ]}
        active={tab}
        onChange={id => setTab(id as typeof tab)}
      />

      {/* Each tab owns its own scrolling. A scrollbar on this wrapper as well would give the
          statement two, nested one inside the other. */}
      <div className="flex-1 min-h-0">
        {tab === 'description' && (
          <div className="h-full px-4 py-3">
            <StatementView
              problem={statement}
              isLoading={statementLoading}
              pdfUrl={statementPdfUrl}
              pdfText={statementText}
              pdfError={statementPdfError}
              samples={samples}
            />
          </div>
        )}

        {tab === 'problems' && (
          <div className="h-full overflow-y-auto p-3">
            <ProblemNav
              problems={contest.problems}
              selected={selected}
              onSelect={index => { setSelected(index); setTab('description') }}
              submissions={rows}
            />
          </div>
        )}

        {tab === 'leaderboard' && (
          <div className="h-full min-h-0">
            {inExam
              ? <ExamLeaderboardView board={examBoard} isLoading={examBoardLoading} meId={meId} />
              : <LeaderboardPanel board={leaderboard} isLoading={leaderboardFetching} />}
          </div>
        )}

        {tab === 'submissions' && (
          <div className="h-full min-h-0">
            <SubmissionsList
              submissions={rows}
              isLoading={submissionsFetching}
              onOpen={openSubmission}
              opening={opening}
            />
          </div>
        )}
      </div>
    </div>
  )

  return (
    <div className="flex flex-col flex-1 min-h-0">
      {/* The paper is covered, not merely warned about, until it is full screen: a notice is
          easy to dismiss and read past; a cover is not. Nothing underneath is lost — the
          buffers stay in state and the editor is still mounted behind it. */}
      {inExam && examSession.fullscreenRequired && !examSession.fullscreen && (
        <div className="fixed inset-0 z-[60] flex items-center justify-center bg-gray-950 p-6">
          <div className="flex max-w-md flex-col items-center gap-3 text-center">
            <Maximize size={28} className="text-indigo-400" />
            <p className="text-base font-medium text-gray-100">
              This examination is sat in full screen
            </p>
            <p className="text-sm leading-relaxed text-gray-500">
              The paper appears once the window is full screen. Leaving full screen covers it
              again and is recorded, the same way leaving the window is.
            </p>
            <button
              onClick={examSession.enterFullscreen}
              className="mt-1 flex items-center gap-1.5 rounded-lg bg-indigo-600 px-4 py-2
                         text-sm font-medium text-white transition-colors hover:bg-indigo-500"
            >
              <Maximize size={14} /> Enter full screen
            </button>
          </div>
        </div>
      )}

      {!inExam && needsCfSession && !session?.connected && (
        <div className="px-4 pt-3 flex-shrink-0">
          <CfSessionCard />
        </div>
      )}

      {/* The examination's own banner: what it is, how long is left, what is being recorded,
          and a way to say "I am done" that an invigilator can see. */}
      {inExam && exam && (
        <div className="mx-4 mt-3 flex flex-col gap-2 flex-shrink-0">
          <div className="flex flex-wrap items-center justify-between gap-2 rounded-lg
            border border-gray-800 bg-gray-900 px-3 py-2">
            <div className="min-w-0">
              <p className="truncate text-sm font-medium text-gray-200">{exam.event.name}</p>
              <p className="text-xs text-gray-500">
                Examination
                {exam.event.lockdownRequired && (
                  <> · monitored, {exam.event.awayThresholdSeconds}s away allowed</>
                )}
                {examSession.focusLosses > 0 && (
                  <> · {examSession.focusLosses} focus loss
                    {examSession.focusLosses === 1 ? '' : 'es'} recorded</>
                )}
              </p>
            </div>
            {/* Asked in the page rather than with window.confirm: a native dialog takes
                focus from the window, which the desktop lock would record as leaving it. */}
            {confirmFinish ? (
              <div className="flex flex-wrap items-center gap-2">
                <span className="text-xs text-gray-400">
                  Finish and sign out? Anything already submitted still counts.
                </span>
                <button
                  onClick={() => setConfirmFinish(false)}
                  disabled={finishing}
                  className="rounded-lg border border-gray-800 bg-gray-900 px-3 py-1.5 text-xs
                             text-gray-300 transition-colors hover:bg-gray-800"
                >
                  Keep working
                </button>
                <button
                  onClick={async () => {
                    setFinishing(true)
                    await examSession.completed()
                    logout.mutate()
                  }}
                  disabled={finishing}
                  className="flex items-center gap-1.5 rounded-lg bg-indigo-600 px-3 py-1.5
                             text-xs font-medium text-white transition-colors
                             hover:bg-indigo-500 disabled:opacity-60"
                >
                  {finishing
                    ? <Loader2 size={13} className="animate-spin" />
                    : <CheckCircle2 size={13} />}
                  Yes, finish and sign out
                </button>
              </div>
            ) : (
              <button
                onClick={() => setConfirmFinish(true)}
                className="flex items-center gap-1.5 rounded-lg border border-gray-800
                           bg-gray-900 px-3 py-1.5 text-xs text-gray-300 transition-colors
                           hover:bg-gray-800"
              >
                <CheckCircle2 size={13} /> I have finished
              </button>
            )}
          </div>

          {exam.rules && (
            <p className="rounded-lg border border-gray-800 bg-gray-900/60 px-3 py-2 text-xs
              leading-relaxed text-gray-400 whitespace-pre-line">{exam.rules}</p>
          )}

          <ExamNotices notices={examSession.notices} onDismiss={examSession.dismiss} />
        </div>
      )}

      {/* Someone being watched is told so, on the screen where it is happening. A lock that
          reports on a contestant without saying so is a different and much worse product. */}
      {!inExam && contest.running && groupContest?.lockdownRequired && (
        <div className="mx-4 mt-3 flex items-start gap-2 rounded-lg border border-indigo-900
          bg-indigo-950/40 px-3 py-2 text-xs text-indigo-200 flex-shrink-0">
          <Eye size={13} className="mt-0.5 flex-shrink-0" />
          <span>
            This round counts for <span className="font-medium">{groupContest.groupName}</span>.
            Leaving this window is noticed: you are warned after
            {' '}{Math.round((lockdown?.warnAfterMs ?? 10_000) / 1000)} seconds away, and
            absences past that are reported to whoever is running the group.
          </span>
        </div>
      )}

      <div className="px-4 pt-3 pb-3 flex-shrink-0">
        <ContestHeader
          contest={contest}
          elapsed={elapsed}
          rank={rank}
          onClose={examOnly != null ? undefined : closeContest}
          onOpenFiles={contest.personalFilesEnabled ? () => setFilesOpen(true) : undefined}
          lockdown={lockdown}
          examSecondsRemaining={inExam ? examSecondsRemaining : undefined}
        />
      </div>

      {/* Reads go through the contest, so the rule for this contest is what answers. */}
      <FilesPanel
        open={filesOpen && contest.personalFilesEnabled}
        onClose={() => setFilesOpen(false)}
        contest={contestRef}
        // Inserted above whatever is in the buffer rather than over it: a template is
        // usually the first thing in a file, and nothing typed during a contest may be lost
        // to a mis-click.
        onUseInEditor={selected
          ? text => setSources(prev => {
              const current = prev[selected] ?? ''
              return { ...prev, [selected]: current ? `${text}\n${current}` : text }
            })
          : undefined}
      />

      {!started ? (
        <div className="flex flex-1 flex-col items-center justify-center gap-3 border-t
          border-gray-800 text-center">
          <Clock size={28} className="text-indigo-400" />
          <div className="text-sm text-gray-300">
            {inExam ? 'This examination has not started yet.' : 'This contest has not started yet.'}
          </div>
          <p className="max-w-md text-xs leading-relaxed text-gray-600">
            The statements, editor and submissions all appear here the moment the judge moves
            it to running — this page is watching for that, so leave it open.
            {inExam
              ? ' Nothing else is needed from you; you are already registered for it.'
              : needsCfSession
                ? ' Make sure you are registered on Codeforces before then.'
                : ' Your team is already registered on the judge; nothing else to do.'}
          </p>
        </div>
      ) : (
        <>
          {!contest.submissionsOpen && contest.submissionsClosedReason && (
            <p className="px-4 pb-2 text-xs text-yellow-500/90 flex-shrink-0">
              {contest.submissionsClosedReason}
            </p>
          )}

          <SplitPane
            className="flex-1 border-t border-gray-800"
            storageKey="cpintel.compete.split"
            initial={45}
            min={25}
            max={70}
            first={leftPane}
            second={
              <EditorPane
                languages={languages ?? []}
                languageId={languageId}
                onLanguageChange={setLanguageId}
                source={source}
                onSourceChange={value => {
                  if (selected) setSources(prev => ({ ...prev, [selected]: value }))
                }}
                onSubmit={() => {
                  if (selected && source.trim()) {
                    submit.mutate({ index: selected, languageId, source })
                  }
                }}
                submitting={submit.isPending}
                polling={!!latest && !latest.finished}
                verdict={verdict}
                canSubmit={contest.submissionsOpen}
                problemSelected={!!selected}
                samples={samples}
                problemKey={selected ? `${contestRef.id}${selected}` : undefined}
                contest={contestRef}
                problemIndex={selected ?? undefined}
                storageKey="cpintel.compete"
                submitLabel="Submit"
              />
            }
          />
        </>
      )}
    </div>
  )
}
