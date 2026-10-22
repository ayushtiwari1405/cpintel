import { Link, useSearchParams } from 'react-router-dom'
import { format, formatDistanceToNow } from 'date-fns'
import { clsx } from 'clsx'
import {
  BookOpen, CalendarClock, Check, Clock, Code2, ExternalLink, Info, Lightbulb, RefreshCw,
  Target, Trophy,
} from 'lucide-react'
import {
  useMarkRevisionDone, usePlan, useRegeneratePlan, useRevisionQueue,
} from '@/hooks/useAnalytics'
import { PageHeader, Section } from '@/components/common/PageHeader'
import { Tabs, panelProps, type TabDef } from '@/components/common/Tabs'
import { EmptyState, ErrorState, LoadingState } from '@/components/common/States'
import type { PlanItem, PlanProblem, PlanType, PracticePlan, RevisionItem } from '@/types'

type TabKey = 'today' | 'week' | 'revision' | 'contest'
const TAB_KEYS: TabKey[] = ['today', 'week', 'revision', 'contest']
const PLAN_OF: Partial<Record<TabKey, PlanType>> = { today: 'daily', week: 'weekly', contest: 'contest-prep' }

/**
 * Practice plans: today's sheet, the week's sessions, revision and contest preparation.
 *
 * The page was parked because it could disagree with the roadmap about what to do next. The
 * plans now fill any gap in the user's measured skills from the roadmap's own next skills, with
 * the same problem picker and solved set, and say which items are measured and which inferred.
 */
export default function RecommendationsPage() {
  const [params, setParams] = useSearchParams()
  const requested = params.get('tab') as TabKey | null
  const tab: TabKey = requested && TAB_KEYS.includes(requested) ? requested : 'today'
  const setTab = (key: TabKey) => setParams(key === 'today' ? {} : { tab: key }, { replace: true })

  const daily = usePlan('daily')
  const revision = useRevisionQueue()
  const dueCount = revision.data?.filter(r => r.status === 'DUE').length ?? 0

  const tabs: TabDef<TabKey>[] = [
    {
      key: 'today', label: 'Today',
      badge: daily.data && daily.data.progress.total > 0
        ? `${daily.data.progress.solved}/${daily.data.progress.total}` : undefined,
    },
    { key: 'week', label: 'This week' },
    { key: 'revision', label: 'Revision', badge: dueCount > 0 ? dueCount : undefined },
    { key: 'contest', label: 'Contest prep' },
  ]

  const planType = PLAN_OF[tab]

  return (
    <div className="space-y-6">
      <PageHeader
        title="Practice plan"
        description="What to solve next, from your measured skills and your roadmap"
        actions={planType && <RegenerateButton type={planType} />}
      />

      <Tabs id="plan" label="Practice plans" tabs={tabs} value={tab} onChange={setTab} />

      <div {...panelProps('plan', tab)} className="focus:outline-none">
        {tab === 'today' && <DailyPanel />}
        {tab === 'week' && <WeeklyPanel />}
        {tab === 'revision' && <RevisionPanel />}
        {tab === 'contest' && <ContestPanel />}
      </div>
    </div>
  )
}

function RegenerateButton({ type }: { type: PlanType }) {
  const regenerate = useRegeneratePlan(type)
  return (
    <button type="button" className="btn-secondary" disabled={regenerate.isPending}
      onClick={() => regenerate.mutate()}>
      <RefreshCw size={14} className={regenerate.isPending ? 'animate-spin' : ''} aria-hidden="true" />
      {regenerate.isPending ? 'Building…' : 'New plan'}
    </button>
  )
}

// -- the three sheets ---------------------------------------------------------------------------

function DailyPanel() {
  const query = usePlan('daily')
  return (
    <PlanQuery query={query} loading="Building today's sheet…" errorTitle="Could not load today's sheet">
      {plan => (
        <div className="space-y-4">
          <ProgressBar plan={plan} label="solved today" icon={<Lightbulb size={13} className="text-amber-400" />} />
          <Notes plan={plan} />
          {plan.items.length === 0
            ? <NoPlan plan={plan} />
            : <ItemList items={plan.items} />}
          <Finished plan={plan} type="daily" />
        </div>
      )}
    </PlanQuery>
  )
}

function WeeklyPanel() {
  const query = usePlan('weekly')
  return (
    <PlanQuery query={query} loading="Building this week's plan…" errorTitle="Could not load the weekly plan">
      {plan => (
        <div className="space-y-4">
          <ProgressBar plan={plan} goal={plan.summary.goalProblems ?? undefined}
            label="solved this week" icon={<BookOpen size={13} className="text-accent-400" />} />
          <Notes plan={plan} />
          {plan.items.length === 0
            ? <NoPlan plan={plan} />
            : <ItemList items={plan.items} sessions />}
          <Finished plan={plan} type="weekly" />
        </div>
      )}
    </PlanQuery>
  )
}

function ContestPanel() {
  const query = usePlan('contest-prep')
  return (
    <PlanQuery query={query} loading="Looking for your next round…" errorTitle="Could not load contest preparation">
      {plan => {
        const { contest, record, rating, ratingBasis } = plan.summary
        return (
          <div className="space-y-4">
            <div className="grid grid-cols-1 gap-3 md:grid-cols-[minmax(0,2fr)_minmax(0,1fr)]">
              <div className="card">
                <p className="flex items-center gap-1.5 text-xs text-gray-500">
                  <CalendarClock size={13} className="text-accent-400" aria-hidden="true" /> Next rated round
                </p>
                {contest ? (
                  <>
                    <p className="mt-1.5 text-sm font-medium text-gray-100">{contest.name}</p>
                    <div className="mt-1 flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-gray-400">
                      <span className="badge badge-blue">{contest.division}</span>
                      <time dateTime={contest.startsAt}>
                        {format(new Date(contest.startsAt), 'EEE d MMM, HH:mm')}
                        {' · '}{formatDistanceToNow(new Date(contest.startsAt), { addSuffix: true })}
                      </time>
                      <a href={contest.url} target="_blank" rel="noopener noreferrer"
                        className="inline-flex items-center gap-1 text-accent-300 hover:text-accent-200">
                        Register on Codeforces <ExternalLink size={11} aria-hidden="true" />
                      </a>
                    </div>
                  </>
                ) : (
                  <p className="mt-1.5 text-sm text-gray-400">
                    No rated round for your level in the next two weeks — this is general preparation.
                  </p>
                )}
              </div>
              <div className="card">
                <p className="flex items-center gap-1.5 text-xs text-gray-500">
                  <Trophy size={13} className="text-amber-400" aria-hidden="true" /> Aimed at
                </p>
                <p className="mt-1.5 font-mono text-lg tabular-nums text-gray-50">{rating ?? '—'}</p>
                <p className="text-xs text-gray-500">
                  {ratingBasis === 'INFERRED' ? 'estimated from what you have solved' : 'your Codeforces rating'}
                </p>
                {record && record.contests > 0 && (
                  <p className="mt-2 text-xs text-gray-400">
                    Last {record.contests} rounds: <span className="tabular-nums">{record.avgSolved.toFixed(1)}</span> solved,{' '}
                    <span className={clsx('tabular-nums', record.avgRatingChange >= 0 ? 'text-green-400' : 'text-red-400')}>
                      {record.avgRatingChange >= 0 ? '+' : ''}{Math.round(record.avgRatingChange)}
                    </span> on average
                  </p>
                )}
              </div>
            </div>
            <ProgressBar plan={plan} label="solved" icon={<Target size={13} className="text-accent-400" />} />
            <Notes plan={plan} hide={n => n.includes('general preparation') || n.includes('estimated')} />
            {plan.items.length === 0 ? <NoPlan plan={plan} /> : <ItemList items={plan.items} />}
          </div>
        )
      }}
    </PlanQuery>
  )
}

/** Loading, failure and the plan, in one place for the three plan tabs. */
function PlanQuery({ query, loading, errorTitle, children }: {
  query: ReturnType<typeof usePlan>
  loading: string
  errorTitle: string
  children: (plan: PracticePlan) => React.ReactNode
}) {
  if (query.isLoading) return <LoadingState label={loading} />
  if (query.isError || !query.data) {
    return <ErrorState title={errorTitle} error={query.error} onRetry={() => query.refetch()} />
  }
  return <>{children(query.data)}</>
}

// -- pieces -----------------------------------------------------------------------------------

function ProgressBar({ plan, label, icon, goal }: {
  plan: PracticePlan
  label: string
  icon: React.ReactNode
  /** Weekly plans: how many make a good week, marked on the bar. */
  goal?: number
}) {
  const { solved, total } = plan.progress
  if (total === 0) return null
  const pct = Math.round((solved / total) * 100)
  const target = goal ?? total
  return (
    <div className="space-y-1.5">
      <div className="flex flex-wrap items-center justify-between gap-2 text-sm text-gray-400">
        <span className="flex items-center gap-1.5">
          <span aria-hidden="true">{icon}</span>
          <span className="tabular-nums text-gray-200">{solved}</span> of{' '}
          <span className="tabular-nums">{total}</span> {label}
          {goal != null && (
            <span className={clsx('ml-1', solved >= goal ? 'text-green-400' : 'text-gray-500')}>
              · goal {goal}{solved >= goal ? ' reached' : ''}
            </span>
          )}
        </span>
        {plan.expiresAt && (
          <span className="text-xs text-gray-500">
            New plan {formatDistanceToNow(new Date(plan.expiresAt), { addSuffix: true })}
          </span>
        )}
      </div>
      <div role="progressbar" aria-label={`${solved} of ${total} ${label}`} aria-valuemin={0}
        aria-valuemax={total} aria-valuenow={solved}
        className="relative h-1.5 overflow-hidden rounded-full bg-gray-800">
        <div className={clsx('h-full rounded-full transition-[width]', solved >= target ? 'bg-green-500' : 'bg-accent-500')}
          style={{ width: `${pct}%` }} />
        {goal != null && goal < total && (
          <span aria-hidden="true" className="absolute inset-y-0 w-px bg-gray-400"
            style={{ left: `${(goal / total) * 100}%` }} />
        )}
      </div>
    </div>
  )
}

/** What the plan rests on, when it is not all measured or something was missing. */
function Notes({ plan, hide }: { plan: PracticePlan; hide?: (note: string) => boolean }) {
  const notes = plan.summary.notes.filter(n => !hide?.(n))
  if (plan.items.length === 0 || notes.length === 0) return null
  return (
    <div className="flex gap-2.5 rounded-lg border border-gray-800 bg-gray-900/60 px-3 py-2.5 text-xs text-gray-400">
      <Info size={14} className="mt-px shrink-0 text-accent-400" aria-hidden="true" />
      <div className="space-y-1">{notes.map(n => <p key={n}>{n}</p>)}</div>
    </div>
  )
}

function NoPlan({ plan }: { plan: PracticePlan }) {
  return (
    <div className="card">
      <EmptyState icon={<Lightbulb size={18} />} title="Nothing to plan from yet"
        action={<Link to="/platforms" className="btn-secondary btn-sm">Link Codeforces</Link>}>
        {plan.summary.notes[0] ?? 'Link and sync your Codeforces account, and your roadmap\'s first skills will appear here.'}
      </EmptyState>
    </div>
  )
}

function Finished({ plan, type }: { plan: PracticePlan; type: PlanType }) {
  const regenerate = useRegeneratePlan(type)
  const { solved, total } = plan.progress
  if (total === 0 || solved < total) return null
  return (
    <div className="flex flex-wrap items-center justify-between gap-3 rounded-lg border border-green-900/60
      bg-green-950/20 px-4 py-3 text-sm text-green-300">
      <span className="flex items-center gap-2"><Check size={14} aria-hidden="true" /> Everything on this plan is solved.</span>
      <button type="button" className="btn-secondary btn-sm" disabled={regenerate.isPending}
        onClick={() => regenerate.mutate()}>
        {regenerate.isPending ? 'Building…' : 'Give me another'}
      </button>
    </div>
  )
}

function ItemList({ items, sessions }: { items: PlanItem[]; sessions?: boolean }) {
  return (
    // grid-cols-1 is minmax(0, 1fr): an implicit auto column grows to the unbroken width of the
    // truncated problem names and pushes the cards off a phone screen.
    <ol className="grid grid-cols-1 gap-3">
      {items.map((item, i) => (
        <li key={item.nodeKey ?? i}>
          <SkillCard item={item} marker={sessions && item.session ? `S${item.session}` : String(i + 1)}
            markerLabel={sessions && item.session ? `Session ${item.session}` : undefined} />
        </li>
      ))}
    </ol>
  )
}

const REASON_LABEL: Record<string, { text: string; className: string }> = {
  WEAK_TOPIC:      { text: 'Weak area',     className: 'badge-red' },
  REVISION_DUE:    { text: 'Revision',      className: 'badge-yellow' },
  MOMENTUM:        { text: 'Keep going',    className: 'badge-green' },
  CONSOLIDATE:     { text: 'Consolidate',   className: 'badge-gray' },
  PROGRESSION:     { text: 'Step up',       className: 'badge-blue' },
  NEXT_ON_ROADMAP: { text: 'From your roadmap', className: 'badge-gray' },
  CONTEST_STAPLE:  { text: 'Contest staple', className: 'badge-blue' },
}

/** One skill and the problems to solve in it, easiest first. */
function SkillCard({ item, marker, markerLabel }: { item: PlanItem; marker: string; markerLabel?: string }) {
  const problems = item.problems ?? []
  const reason = item.reasonCode ? REASON_LABEL[item.reasonCode] : undefined

  return (
    <div className="card">
      <div className="flex items-start gap-3">
        <div aria-label={markerLabel} title={markerLabel}
          className="mt-0.5 flex h-7 min-w-7 shrink-0 items-center justify-center rounded-lg border
            border-accent-800 bg-accent-900/50 px-1 font-mono text-xs font-medium text-accent-400">
          {marker}
        </div>

        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-2">
            <span className="text-sm font-medium text-gray-200">{item.title}</span>
            {reason && <span className={`badge ${reason.className}`}>{reason.text}</span>}
            {item.targetRating != null && <span className="badge badge-blue">aim ~{item.targetRating}</span>}
          </div>
          {item.reason && <p className="mt-1 text-xs text-gray-500">{item.reason}</p>}
          {item.blurb && <p className="mt-1 text-xs text-gray-600">{item.blurb}</p>}
        </div>

        <Link to={`/practice?node=${encodeURIComponent(item.nodeKey)}`}
          className="flex shrink-0 items-center gap-1 text-xs text-gray-500 hover:text-accent-300">
          <Target size={11} aria-hidden="true" /> Open skill
        </Link>
      </div>

      {problems.length > 0
        ? <ProblemList problems={problems} />
        : (
          <p className="mt-3 pl-10 text-xs text-gray-500">
            No unsolved problems to suggest here right now — open the skill to browse its problem list.
          </p>
        )}
    </div>
  )
}

const ROLE_LABEL: Record<string, string> = {
  WARM_UP: 'warm-up', CORE: 'core', STRETCH: 'stretch', RESOLVE: 'solve again',
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

function ProblemList({ problems }: { problems: PlanProblem[] }) {
  return (
    <ul className="mt-3 space-y-1 pl-10">
      {problems.map(p => {
        // A problem to solve again is solved by definition; only new ones are struck through.
        const done = p.solved && p.role !== 'RESOLVE'
        return (
          <li key={`${p.contestId}${p.index}`}
            className="group flex items-center gap-2 rounded-md bg-gray-900/60 px-2 py-1.5 transition-colors
              hover:bg-gray-800/80">
            <Link to={p.practicePath ?? '/practice'} className="flex min-w-0 flex-1 items-center gap-2"
              title={`Open ${p.name} in the practice workspace`}>
              {done
                ? <Check size={11} className="shrink-0 text-green-500" aria-label="solved" />
                : <Code2 size={11} className="shrink-0 text-gray-600 group-hover:text-accent-400" aria-hidden="true" />}
              <span className={clsx('truncate text-xs', done ? 'text-gray-500 line-through' : 'text-gray-300')}>
                {p.name}
              </span>
            </Link>
            {p.role && (
              <span className={clsx('shrink-0 text-[11px]',
                p.role === 'STRETCH' ? 'text-amber-400/80' : p.role === 'RESOLVE' ? 'text-teal-400/80' : 'text-gray-500')}>
                {ROLE_LABEL[p.role]}
              </span>
            )}
            <span className={clsx('w-10 shrink-0 text-right text-[11px] tabular-nums', ratingColor(p.rating))}>
              {p.rating ?? '—'}
            </span>
            {p.judgeUrl && (
              <a href={p.judgeUrl} target="_blank" rel="noopener noreferrer"
                className="shrink-0 text-gray-600 hover:text-gray-300" title="Open on codeforces.com instead"
                aria-label={`Open ${p.name} on codeforces.com`}>
                <ExternalLink size={10} aria-hidden="true" />
              </a>
            )}
          </li>
        )
      })}
    </ul>
  )
}

// -- revision ---------------------------------------------------------------------------------

function RevisionPanel() {
  const { data, isLoading, isError, error, refetch } = useRevisionQueue()
  const markDone = useMarkRevisionDone()

  if (isLoading) return <LoadingState label="Loading your revision queue…" />
  if (isError || !data) return <ErrorState title="Could not load the revision queue" error={error} onRetry={() => refetch()} />

  const due = data.filter(r => r.status === 'DUE')
  const queued = data.filter(r => r.status === 'QUEUED')
  const upcoming = data.filter(r => r.status === 'UPCOMING')

  if (data.length === 0) {
    return (
      <div className="card">
        <EmptyState icon={<Check size={18} />} title="Nothing to revise">
          Skills appear here once they have gone long enough without practice to have measurably faded.
        </EmptyState>
      </div>
    )
  }

  return (
    <div className="space-y-6">
      <section aria-labelledby="revision-due" className="space-y-3">
        <h2 id="revision-due" className="flex items-center gap-1.5 text-sm text-gray-400">
          <Clock size={13} className="text-teal-400" aria-hidden="true" />
          {due.length > 0 ? `${due.length} skill${due.length === 1 ? '' : 's'} to revise today`
            : queued.length > 0 ? 'Today\'s revision is done' : 'Nothing due right now'}
        </h2>
        {due.map(item => (
          <RevisionCard key={item.revisionId} item={item}
            onDone={() => markDone.mutate(item.revisionId)} pending={markDone.isPending} />
        ))}
      </section>

      {queued.length > 0 && (
        <Section title={`Waiting their turn (${queued.length})`} icon={<Clock size={14} />} bodyClassName="p-0">
          <p className="border-b border-gray-800 px-4 py-2.5 text-xs text-gray-500">
            Also faded, and not forgotten: a few come up each day, longest-waiting and most faded first.
            Practising one of them now counts too.
          </p>
          <ol className="divide-y divide-gray-800">
            {queued.map(item => (
              <li key={item.revisionId} className="flex items-center justify-between gap-3 px-4 py-2.5 text-sm">
                <span className="flex min-w-0 items-center gap-2">
                  <span className="w-6 shrink-0 text-right font-mono text-xs tabular-nums text-gray-600">
                    {item.queuePosition}
                  </span>
                  {item.practicePath
                    ? <Link to={item.practicePath} className="truncate text-gray-300 hover:text-accent-300">{item.title}</Link>
                    : <span className="truncate text-gray-300">{item.title}</span>}
                </span>
                <span className="shrink-0 text-xs text-amber-500/80">
                  {Math.round(item.decayScore ?? 0)}% faded
                </span>
              </li>
            ))}
          </ol>
        </Section>
      )}

      {upcoming.length > 0 && (
        <Section title="Coming up" icon={<CalendarClock size={14} />} bodyClassName="p-0">
          <ul className="divide-y divide-gray-800">
            {upcoming.map(item => (
              <li key={item.revisionId} className="flex items-center justify-between gap-3 px-4 py-2.5 text-sm">
                <span className="min-w-0 truncate text-gray-300">{item.title}</span>
                <time dateTime={item.nextRevisionAt} className="shrink-0 text-xs text-gray-500">
                  {formatDistanceToNow(new Date(item.nextRevisionAt), { addSuffix: true })}
                </time>
              </li>
            ))}
          </ul>
        </Section>
      )}
    </div>
  )
}

function RevisionCard({ item, onDone, pending }: { item: RevisionItem; onDone: () => void; pending: boolean }) {
  return (
    <div className="card">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-2">
            <p className="text-sm font-medium text-gray-200">{item.title}</p>
            <span className="badge badge-yellow">Due</span>
            {item.track && <span className="text-[11px] text-gray-600">{item.track}</span>}
          </div>
          <p className="mt-1 text-xs text-gray-500">{item.reason}</p>
          <p className="mt-0.5 text-[11px] text-gray-600">
            Revised {item.repetitionCount ?? 0} time{item.repetitionCount === 1 ? '' : 's'}
            {item.intervalDays != null && <> · next gap {item.intervalDays} day{item.intervalDays === 1 ? '' : 's'}</>}
          </p>
        </div>
        <div className="flex shrink-0 items-center gap-2">
          {item.practicePath && (
            <Link to={item.practicePath} className="btn-secondary btn-sm">
              <Code2 size={12} aria-hidden="true" /> Practise
            </Link>
          )}
          <button type="button" onClick={onDone} disabled={pending} className="btn-secondary btn-sm text-green-400">
            <Check size={12} aria-hidden="true" /> Done
          </button>
        </div>
      </div>
      {item.problems.length > 0 && <ProblemList problems={item.problems} />}
    </div>
  )
}
