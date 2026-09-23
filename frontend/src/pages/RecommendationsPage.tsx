import { useState } from 'react'
import { Link } from 'react-router-dom'
import {
  useDailyRecs, useWeeklyRecs,
  useRevisionQueue, useMarkRevisionDone
} from '@/hooks/useAnalytics'
import {
  Lightbulb, BookOpen, Check, Loader2, Clock, Code2, ExternalLink, Target,
} from 'lucide-react'
import { formatDistanceToNow } from 'date-fns'
import { clsx } from 'clsx'

const TABS = ['Daily', 'Weekly', 'Revision'] as const
type Tab = typeof TABS[number]

/** One problem on a sheet. */
interface SheetProblem {
  contestId: number
  index: string
  name: string
  rating: number | null
  solved: boolean
  fit: number
  practicePath: string | null
  judgeUrl: string | null
}

/** One skill on a sheet. */
interface SheetItem {
  nodeKey: string
  title: string
  track?: string
  topic?: string
  blurb?: string
  masteryScore?: number
  decayScore?: number
  targetRating?: number
  reason?: string
  priority?: string
  problems?: SheetProblem[]
}

function ratingColor(rating?: number | null) {
  if (!rating) return 'text-gray-500'
  if (rating < 1200) return 'text-gray-400'
  if (rating < 1400) return 'text-green-400'
  if (rating < 1600) return 'text-cyan-400'
  if (rating < 1900) return 'text-blue-400'
  if (rating < 2100) return 'text-purple-400'
  if (rating < 2400) return 'text-amber-400'
  return 'text-red-400'
}

export default function RecommendationsPage() {
  const [tab, setTab] = useState<Tab>('Daily')

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold text-gray-50">Recommendations</h1>
        <p className="text-gray-400 text-sm mt-0.5">
          Practice plans built from your per-skill mastery, with the problems to solve
        </p>
      </div>

      <div className="flex gap-1 bg-gray-900 border border-gray-800 rounded-xl p-1 w-fit">
        {TABS.map(t => (
          <button key={t} onClick={() => setTab(t)}
            className={clsx(
              'px-4 py-1.5 rounded-lg text-sm font-medium transition-colors',
              tab === t ? 'bg-indigo-600 text-white' : 'text-gray-400 hover:text-gray-200'
            )}>
            {t}
          </button>
        ))}
      </div>

      {tab === 'Daily'    && <DailyTab />}
      {tab === 'Weekly'   && <WeeklyTab />}
      {tab === 'Revision' && <RevisionTab />}
    </div>
  )
}

function DailyTab() {
  const { data, isLoading } = useDailyRecs()
  if (isLoading) return <Spinner />

  const items: SheetItem[] = data?.items ?? []
  const generated = data?.generatedAt

  return (
    <div className="space-y-4">
      <p className="text-sm text-gray-400 flex items-center gap-1.5">
        <Lightbulb size={13} className="text-amber-400" />
        Today's focus
        {generated && (
          <span className="text-gray-600 ml-2">
            · {formatDistanceToNow(new Date(generated), { addSuffix: true })}
          </span>
        )}
      </p>

      {items.length === 0 ? (
        <EmptyState
          icon={<Lightbulb size={24} className="text-amber-400" />}
          title="No daily sheet yet"
          desc="Sync a platform and refresh analytics to generate your first daily sheet. Sheets are built from skills you have actually started, so a little history has to land first."
        />
      ) : (
        <div className="grid gap-3">
          {items.map((item, i) => (
            <SkillCard key={item.nodeKey ?? i} item={item} index={i + 1} />
          ))}
        </div>
      )}
    </div>
  )
}

function WeeklyTab() {
  const { data, isLoading } = useWeeklyRecs()
  if (isLoading) return <Spinner />

  const items: SheetItem[] = data?.items ?? []

  return (
    <div className="space-y-4">
      <p className="text-sm text-gray-400 flex items-center gap-1.5">
        <BookOpen size={13} className="text-indigo-400" />
        This week's plan — ranked by what you have retained, so a strong skill gone stale
        surfaces alongside a weak one
      </p>

      {items.length === 0 ? (
        <EmptyState
          icon={<BookOpen size={24} className="text-indigo-400" />}
          title="Weekly plan not generated yet"
          desc="Trigger a refresh in Analytics to build your first weekly plan"
        />
      ) : (
        <div className="grid gap-3">
          {items.map((item, i) => (
            <SkillCard key={item.nodeKey ?? i} item={item} index={i + 1} />
          ))}
        </div>
      )}
    </div>
  )
}

function RevisionTab() {
  const { data: queue, isLoading } = useRevisionQueue()
  const markDone = useMarkRevisionDone()

  if (isLoading) return <Spinner />

  const items: any[] = Array.isArray(queue) ? queue : []

  return (
    <div className="space-y-4">
      <p className="text-sm text-gray-400 flex items-center gap-1.5">
        <Clock size={13} className="text-teal-400" />
        {items.length} skill{items.length !== 1 ? 's' : ''} due for revision
      </p>

      {items.length === 0 ? (
        <EmptyState
          icon={<Check size={24} className="text-green-400" />}
          title="Nothing due"
          desc="Skills appear here once they have gone long enough without practice to have measurably faded."
        />
      ) : (
        <div className="space-y-3">
          {items.map((item: any) => (
            <div key={item.revisionId} className="card flex items-center justify-between gap-4">
              <div className="flex-1 min-w-0">
                <div className="flex items-center gap-2">
                  <p className="text-sm font-medium text-gray-200 truncate">{item.title}</p>
                  {item.track && (
                    <span className="text-[11px] text-gray-600 flex-shrink-0">{item.track}</span>
                  )}
                </div>
                <div className="flex gap-4 mt-1 text-xs text-gray-500">
                  <span className="text-amber-500">
                    {(item.decayScore ?? 0).toFixed(0)}% faded
                  </span>
                  <span>Next in {item.intervalDays}d</span>
                  <span>Rep #{item.repetitionCount}</span>
                </div>
              </div>

              <div className="flex items-center gap-2 flex-shrink-0">
                {item.practicePath && (
                  <Link
                    to={item.practicePath}
                    className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs
                               bg-indigo-900/30 text-indigo-300 border border-indigo-800
                               hover:bg-indigo-900/50 transition-colors"
                  >
                    <Code2 size={12} /> Practise
                  </Link>
                )}
                <button
                  onClick={() => markDone.mutate(item.revisionId)}
                  disabled={markDone.isPending}
                  className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs
                             bg-green-900/30 text-green-400 border border-green-800
                             hover:bg-green-900/50 transition-colors"
                >
                  <Check size={12} /> Done
                </button>
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}

const PRIORITY_CLASS: Record<string, string> = {
  REVISION: 'badge-red',
  NEW: 'badge-blue',
  PRACTICE: 'badge-green',
}

/**
 * One skill and the problems to solve in it.
 *
 * Sheets used to render a topic name, a "reason" line, and a target difficulty of around 250 -
 * a number that is not a Codeforces rating and matched no problem that exists. There was
 * nothing to click and nothing to solve.
 */
function SkillCard({ item, index }: { item: SheetItem; index: number }) {
  const problems = item.problems ?? []
  const unsolved = problems.filter(p => !p.solved)

  return (
    <div className="card">
      <div className="flex items-start gap-3">
        <div className="w-7 h-7 rounded-lg bg-indigo-900/50 border border-indigo-800
          flex items-center justify-center text-xs font-medium text-indigo-400
          flex-shrink-0 mt-0.5">
          {index}
        </div>

        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-2 flex-wrap">
            <span className="text-sm font-medium text-gray-200">{item.title}</span>
            {item.priority && (
              <span className={`badge ${PRIORITY_CLASS[item.priority] ?? 'badge-gray'}`}>
                {item.priority}
              </span>
            )}
            {item.targetRating && (
              <span className="badge badge-blue">aim ~{item.targetRating}</span>
            )}
          </div>

          {item.reason && <p className="text-xs text-gray-500 mt-1">{item.reason}</p>}
          {item.blurb && <p className="text-xs text-gray-600 mt-1">{item.blurb}</p>}
        </div>

        {item.nodeKey && (
          <Link
            to={`/practice?node=${encodeURIComponent(item.nodeKey)}`}
            className="flex-shrink-0 flex items-center gap-1 text-xs text-gray-500
                       hover:text-indigo-300"
          >
            <Target size={11} /> Open skill
          </Link>
        )}
      </div>

      {problems.length > 0 && (
        <div className="mt-3 pl-10 space-y-1">
          {problems.map(p => (
            <div
              key={`${p.contestId}${p.index}`}
              className="group flex items-center gap-2 rounded-md px-2 py-1.5
                         bg-gray-900/60 hover:bg-gray-800/80 transition-colors"
            >
              <Link
                to={p.practicePath ?? '/practice'}
                className="flex items-center gap-2 min-w-0 flex-1"
                title={`Open ${p.name} in the practice workspace`}
              >
                {p.solved
                  ? <Check size={11} className="flex-shrink-0 text-green-500" />
                  : <Code2 size={11} className="flex-shrink-0 text-gray-600
                                                group-hover:text-indigo-400" />}
                <span className={clsx(
                  'truncate text-xs',
                  p.solved ? 'text-gray-500 line-through' : 'text-gray-300'
                )}>
                  {p.name}
                </span>
              </Link>
              <span className={clsx(
                'text-[11px] tabular-nums flex-shrink-0', ratingColor(p.rating)
              )}>
                {p.rating ?? '—'}
              </span>
              {p.judgeUrl && (
                <a
                  href={p.judgeUrl}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="flex-shrink-0 text-gray-600 hover:text-gray-300"
                  title="Open on codeforces.com instead"
                >
                  <ExternalLink size={10} />
                </a>
              )}
            </div>
          ))}
          {unsolved.length === 0 && (
            <p className="text-[11px] text-green-500 pt-1">
              Everything suggested here is already solved — refresh analytics for a new set.
            </p>
          )}
        </div>
      )}
    </div>
  )
}

function EmptyState({ icon, title, desc }: {
  icon: React.ReactNode; title: string; desc: string
}) {
  return (
    <div className="card text-center py-12">
      <div className="flex justify-center mb-3">{icon}</div>
      <p className="text-gray-300 font-medium mb-1">{title}</p>
      <p className="text-gray-500 text-sm max-w-md mx-auto">{desc}</p>
    </div>
  )
}

function Spinner() {
  return (
    <div className="flex items-center justify-center h-48">
      <Loader2 size={24} className="animate-spin text-indigo-400" />
    </div>
  )
}
