import { useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { clsx } from 'clsx'
import {
  ArrowRight, Check, CheckCircle2, Flag, Loader2, Map, Swords, X, XCircle,
} from 'lucide-react'

import { roadmapApi } from '@/api/roadmapApi'
import type {
  GauntletAnswers, GauntletChecked, GauntletQuestion, GauntletResult, GauntletSection,
} from '@/api/roadmapApi'
import { useGauntlet, useSubmitGauntlet } from '@/hooks/useRoadmap'
import { useToast } from '@/components/common/Toaster'

const TIERS = 4

/** Codeforces' own names for a rating, so a placement reads like something people know. */
function rank(rating: number): string {
  if (rating < 1200) return 'Starting out'
  if (rating < 1400) return 'Pupil'
  if (rating < 1600) return 'Specialist'
  if (rating < 1900) return 'Expert'
  if (rating < 2100) return 'Candidate Master'
  if (rating < 2300) return 'Master'
  return 'Grandmaster range'
}

function ratingTone(rating: number): string {
  if (rating < 1200) return 'text-gray-400'
  if (rating < 1400) return 'text-green-400'
  if (rating < 1600) return 'text-cyan-400'
  if (rating < 1900) return 'text-blue-400'
  if (rating < 2100) return 'text-violet-400'
  if (rating < 2400) return 'text-orange-400'
  return 'text-red-400'
}

/**
 * The placement gauntlet.
 *
 * <p>Six areas, climbed one at a time. Each tier is two questions on one screen; both right
 * moves the area up a tier, anything else ends that area and moves to the next. So somebody
 * new answers a dozen questions and somebody strong answers most of the forty-eight, and both
 * are done in about ten minutes.
 *
 * <p>Each run is a server-side attempt. The page checks a tier as it goes so it knows whether to
 * climb; the server records each answer the first time it is checked, refuses a tier whose tier
 * below was not passed, and places the user from what it recorded — this component decides
 * what to ask next, never where anybody lands.
 */
export default function GauntletPage() {
  const { data: paper, isLoading } = useGauntlet()
  const submit = useSubmitGauntlet()
  const toast = useToast(s => s.push)

  const [phase, setPhase] = useState<'intro' | 'running' | 'done'>('intro')
  const [sectionIndex, setSectionIndex] = useState(0)
  const [tier, setTier] = useState(1)
  const [answers, setAnswers] = useState<GauntletAnswers>({})
  const [checked, setChecked] = useState<Record<string, GauntletChecked>>({})
  const [checking, setChecking] = useState(false)
  const [result, setResult] = useState<GauntletResult | null>(null)
  const [attemptId, setAttemptId] = useState<string | null>(null)
  const [starting, setStarting] = useState(false)

  const sections = useMemo(() => paper?.sections ?? [], [paper])
  const section: GauntletSection | undefined = sections[sectionIndex]
  const questions = useMemo(
    () => section?.questions.filter(q => q.tier === tier) ?? [], [section, tier])
  const tierChecked = questions.length > 0 && questions.every(q => checked[q.id])
  const tierPassed = tierChecked && questions.every(q => checked[q.id].correct)
  const allAnswered = questions.every(q => answers[q.id] !== undefined)

  const restart = async () => {
    setStarting(true)
    try {
      const { attemptId: id } = await roadmapApi.startGauntlet().then(r => r.data)
      setAttemptId(id)
      setSectionIndex(0); setTier(1); setAnswers({}); setChecked({}); setResult(null)
      setPhase('running')
    } catch (err: any) {
      toast('error', err?.response?.data?.message ?? 'Could not start the gauntlet.')
    } finally {
      setStarting(false)
    }
  }

  const check = async () => {
    setChecking(true)
    try {
      const picks = Object.fromEntries(questions.map(q => [q.id, answers[q.id] ?? -1]))
      const marked = await roadmapApi.checkGauntlet(attemptId!, picks).then(r => r.data)
      setChecked(prev => ({ ...prev, ...Object.fromEntries(marked.map(m => [m.id, m])) }))
    } catch (err: any) {
      toast('error', err?.response?.data?.message ?? 'Could not check those answers.')
    } finally {
      setChecking(false)
    }
  }

  const finish = () => {
    if (!attemptId) return
    submit.mutate(attemptId, {
      onSuccess: placed => { setResult(placed); setPhase('done') },
      onError: (err: any) =>
        toast('error', err?.response?.data?.message ?? 'Could not save your placement.'),
    })
  }

  const next = () => {
    if (tierPassed && tier < TIERS) {
      setTier(tier + 1)
      return
    }
    if (sectionIndex + 1 < sections.length) {
      setSectionIndex(sectionIndex + 1)
      setTier(1)
      return
    }
    finish()
  }

  if (isLoading || !paper) {
    return (
      <div className="flex items-center justify-center h-64">
        <Loader2 size={24} className="animate-spin text-indigo-400" />
      </div>
    )
  }

  // ── intro ────────────────────────────────────────────────────────────────
  if (phase === 'intro') {
    return (
      <div className="mx-auto max-w-2xl space-y-5">
        <div className="card space-y-4 p-6">
          <div className="flex items-center gap-3">
            <div className="flex h-10 w-10 items-center justify-center rounded-xl
                            bg-indigo-600/15 text-indigo-300">
              <Swords size={20} />
            </div>
            <div>
              <h1 className="text-xl font-semibold text-gray-50">The gauntlet</h1>
              <p className="text-sm text-gray-500">Find out where you stand — about ten minutes</p>
            </div>
          </div>

          <p className="text-sm leading-relaxed text-gray-300">
            Six areas, each a climb of up to four tiers — roughly 1200, 1600, 2000 and 2400.
            Every tier is two questions about which technique a problem needs. Get both right
            and you climb; miss one and that area stops there. Your roadmap is then set to
            match: skills below your level are marked done, and the next ones open up.
          </p>

          <ul className="grid gap-2 sm:grid-cols-2">
            {paper.sections.map(s => (
              <li key={s.id} className="rounded-lg border border-gray-800 bg-gray-900/50 px-3 py-2">
                <p className="text-sm font-medium text-gray-200">{s.title}</p>
                <p className="text-xs text-gray-500">{s.blurb}</p>
              </li>
            ))}
          </ul>

          <p className="text-xs leading-relaxed text-gray-500">
            &ldquo;Not sure yet&rdquo; is always an option, and it is the honest answer when it is
            true — a guess that lands puts you on skills you will then find too hard. Each
            answer is final once checked, and a retake opens a week later — retaking never
            undoes progress, only adds to it.
          </p>

          {paper.nextAttemptAt ? (
            // The answers are shown during an attempt, so a retake straight away would test
            // memory, not level.
            <p className="rounded-lg border border-gray-800 bg-gray-900/60 px-3 py-2 text-sm
              text-gray-400">
              You can retake it from{' '}
              <span className="text-gray-200">
                {new Date(paper.nextAttemptAt).toLocaleDateString(undefined,
                  { day: 'numeric', month: 'long' })}
              </span>
              . Solving problems moves your roadmap in the meantime.
            </p>
          ) : (
            <button onClick={restart} disabled={starting}
              className="btn-primary inline-flex items-center gap-2 disabled:opacity-40">
              {paper.lastResult ? 'Retake the gauntlet' : 'Start'}
              {starting ? <Loader2 size={14} className="animate-spin" /> : <ArrowRight size={14} />}
            </button>
          )}
        </div>

        {paper.lastResult && <ResultCard result={paper.lastResult} compact />}
      </div>
    )
  }

  // ── result ───────────────────────────────────────────────────────────────
  if (phase === 'done' && result) {
    return (
      <div className="mx-auto max-w-2xl space-y-4">
        <ResultCard result={result} />
        <div className="flex flex-wrap gap-2">
          <Link to="/roadmap" className="btn-primary inline-flex items-center gap-2">
            <Map size={14} /> Open your roadmap
          </Link>
        </div>
      </div>
    )
  }

  // ── running ──────────────────────────────────────────────────────────────
  return (
    <div className="mx-auto max-w-2xl space-y-4">
      {/* Where in the gauntlet: one step per area, with the tier inside the current one. */}
      <div className="flex items-center gap-1.5">
        {sections.map((s, i) => (
          <div key={s.id} className="flex-1">
            <div className={clsx('h-1.5 rounded-full',
              i < sectionIndex ? 'bg-indigo-500'
                : i === sectionIndex ? 'bg-indigo-500/40' : 'bg-gray-800')} />
            <p className={clsx('mt-1.5 truncate text-[11px]',
              i === sectionIndex ? 'text-gray-300' : 'text-gray-600')}>
              {s.title}
            </p>
          </div>
        ))}
      </div>

      <div className="card space-y-5 p-6">
        <div className="flex items-center justify-between">
          <div>
            <p className="text-xs uppercase tracking-wider text-gray-600">{section?.title}</p>
            <p className="text-sm text-gray-300">
              Tier {tier} of {TIERS}
              <span className="text-gray-600"> · around {paper.tierRatings[tier - 1]}</span>
            </p>
          </div>
          <div className="flex gap-1">
            {Array.from({ length: TIERS }, (_, i) => (
              <span key={i} className={clsx('h-2 w-6 rounded-full',
                i + 1 < tier ? 'bg-green-500/70' : i + 1 === tier ? 'bg-indigo-500' : 'bg-gray-800')} />
            ))}
          </div>
        </div>

        {questions.map((q, n) => (
          <QuestionBlock
            key={q.id}
            number={n + 1}
            question={q}
            picked={answers[q.id]}
            checked={checked[q.id]}
            onPick={index => setAnswers(prev => ({ ...prev, [q.id]: index }))}
          />
        ))}

        <div className="flex items-center justify-between gap-3 border-t border-gray-800 pt-4">
          <p className="text-xs text-gray-500">
            {!tierChecked
              ? 'Answer both, then check.'
              : tierPassed
                ? tier < TIERS ? 'Both right — on to the next tier.' : 'Top tier cleared.'
                : `That is where ${section?.title.toLowerCase()} stops for now.`}
          </p>
          {!tierChecked ? (
            <button
              onClick={check}
              disabled={!allAnswered || checking}
              className="btn-primary inline-flex items-center gap-2 disabled:opacity-40"
            >
              {checking ? <Loader2 size={14} className="animate-spin" /> : <Check size={14} />}
              Check
            </button>
          ) : (
            <button
              onClick={next}
              disabled={submit.isPending}
              className="btn-primary inline-flex items-center gap-2 disabled:opacity-40"
            >
              {submit.isPending ? <Loader2 size={14} className="animate-spin" />
                : tierPassed && tier < TIERS ? <ArrowRight size={14} />
                  : sectionIndex + 1 < sections.length ? <ArrowRight size={14} />
                    : <Flag size={14} />}
              {tierPassed && tier < TIERS ? 'Next tier'
                : sectionIndex + 1 < sections.length ? `On to ${sections[sectionIndex + 1].title}`
                  : 'See where you stand'}
            </button>
          )}
        </div>
      </div>

      <button
        onClick={finish}
        disabled={submit.isPending}
        className="text-xs text-gray-600 transition-colors hover:text-gray-400"
      >
        Stop here and place me on what I have answered
      </button>
    </div>
  )
}

function QuestionBlock({ number, question, picked, checked, onPick }: {
  number: number
  question: GauntletQuestion
  picked: number | undefined
  checked: GauntletChecked | undefined
  onPick: (index: number) => void
}) {
  const options = [...question.options.map((text, index) => ({ text, index })),
    { text: 'Not sure yet', index: -1 }]

  return (
    <div className="space-y-2.5">
      <p className="text-sm font-medium leading-relaxed text-gray-100">
        <span className="mr-2 text-gray-600">{number}.</span>{question.prompt}
      </p>
      {question.code && (
        <pre className="overflow-x-auto rounded-lg border border-gray-800 bg-gray-950 px-3 py-2
                        text-[13px] leading-relaxed text-gray-200">{question.code}</pre>
      )}
      <div className="grid gap-1.5">
        {options.map(({ text, index }) => {
          const chosen = picked === index
          const right = checked && index === checked.correctIndex
          const wrong = checked && chosen && !checked.correct
          return (
            <button
              key={index}
              onClick={() => !checked && onPick(index)}
              disabled={!!checked}
              className={clsx(
                'flex items-center gap-2 rounded-lg border px-3 py-2 text-left text-sm',
                'transition-colors disabled:cursor-default',
                right ? 'border-green-800 bg-green-950/40 text-green-200'
                  : wrong ? 'border-red-900 bg-red-950/40 text-red-200'
                    : chosen ? 'border-indigo-700 bg-indigo-600/15 text-indigo-200'
                      : 'border-gray-800 text-gray-300 hover:border-gray-700 hover:bg-gray-850',
                index === -1 && !chosen && !right && 'text-gray-500',
              )}
            >
              <span className="flex-1">{text}</span>
              {right && <CheckCircle2 size={14} className="flex-shrink-0" />}
              {wrong && <XCircle size={14} className="flex-shrink-0" />}
            </button>
          )
        })}
      </div>
      {checked && (
        <p className={clsx('flex items-start gap-1.5 text-xs leading-relaxed',
          checked.correct ? 'text-green-300/80' : 'text-gray-400')}>
          {checked.correct
            ? <Check size={12} className="mt-0.5 flex-shrink-0" />
            : <X size={12} className="mt-0.5 flex-shrink-0" />}
          {checked.explanation}
        </p>
      )}
    </div>
  )
}

function ResultCard({ result, compact }: { result: GauntletResult; compact?: boolean }) {
  return (
    <div className="card space-y-4 p-6">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <p className="text-xs uppercase tracking-wider text-gray-600">
            {compact ? 'Your last placement' : 'Where you stand'}
          </p>
          <p className={clsx('text-3xl font-bold tabular-nums', ratingTone(result.overallRating))}>
            ~{result.overallRating}
          </p>
          <p className="text-sm text-gray-400">{rank(result.overallRating)}</p>
        </div>
        <p className="text-xs text-gray-500">
          {new Date(result.takenAt).toLocaleDateString()}
          {!compact && result.nodesPlaced > 0 && (
            <> · {result.nodesPlaced} roadmap skill{result.nodesPlaced === 1 ? '' : 's'} marked done</>
          )}
        </p>
      </div>

      <div className="space-y-2.5">
        {result.sections.map(s => (
          <div key={s.section}>
            <div className="mb-1 flex items-center justify-between text-xs">
              <span className="text-gray-300">{s.title}</span>
              <span className={clsx('tabular-nums', ratingTone(s.rating))}>
                {s.tiersPassed === 0 ? 'from the start' : `~${s.rating}`}
              </span>
            </div>
            <div className="flex gap-1">
              {Array.from({ length: TIERS }, (_, i) => (
                <span key={i} className={clsx('h-1.5 flex-1 rounded-full',
                  i < s.tiersPassed ? 'bg-indigo-500' : 'bg-gray-800')} />
              ))}
            </div>
          </div>
        ))}
      </div>

      {!compact && (
        <p className="text-xs leading-relaxed text-gray-500">
          Your roadmap now starts here: skills at or below each area&rsquo;s level are marked
          done, and the band just above is open. Solving problems keeps moving it from there.
        </p>
      )}
    </div>
  )
}
