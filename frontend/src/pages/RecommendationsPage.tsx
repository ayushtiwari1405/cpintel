import { useState } from 'react'
import {
  useDailyRecs, useWeeklyRecs,
  useRevisionQueue, useMarkRevisionDone
} from '@/hooks/useAnalytics'
import { Lightbulb, BookOpen, RefreshCcw, Check, Loader2, Clock } from 'lucide-react'
import { formatDistanceToNow } from 'date-fns'
import { clsx } from 'clsx'

const TABS = ['Daily', 'Weekly', 'Revision'] as const
type Tab = typeof TABS[number]

export default function RecommendationsPage() {
  const [tab, setTab] = useState<Tab>('Daily')

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold text-white">Recommendations</h1>
        <p className="text-gray-400 text-sm mt-0.5">
          AI-powered practice plans built from your mastery data
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

  const items: any[] = data?.items ?? []
  const generated = data?.generatedAt

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <p className="text-sm text-gray-400 flex items-center gap-1.5">
          <Lightbulb size={13} className="text-amber-400" />
          Today's focus areas
          {generated && (
            <span className="text-gray-600 ml-2">
              · {formatDistanceToNow(new Date(generated), { addSuffix: true })}
            </span>
          )}
        </p>
      </div>

      {items.length === 0 ? (
        <EmptyState
          icon={<Lightbulb size={24} className="text-amber-400" />}
          title="No daily sheet yet"
          desc="Sync a platform and refresh analytics to generate your first daily sheet"
        />
      ) : (
        <div className="grid gap-3">
          {items.map((item: any, i: number) => (
            <TopicCard key={i} item={item} index={i + 1} />
          ))}
        </div>
      )}
    </div>
  )
}

function WeeklyTab() {
  const { data, isLoading } = useWeeklyRecs()
  if (isLoading) return <Spinner />

  const items: any[] = data?.items ?? []

  const priorityColor: Record<string, string> = {
    REVISION: 'badge-red',
    NEW:      'badge-blue',
    PRACTICE: 'badge-green',
  }

  return (
    <div className="space-y-4">
      <p className="text-sm text-gray-400 flex items-center gap-1.5">
        <BookOpen size={13} className="text-indigo-400" />
        This week's structured plan — 7 topic areas
      </p>

      {items.length === 0 ? (
        <EmptyState
          icon={<BookOpen size={24} className="text-indigo-400" />}
          title="Weekly plan not generated yet"
          desc="Trigger a refresh in Analytics to build your first weekly plan"
        />
      ) : (
        <div className="grid gap-3 lg:grid-cols-2">
          {items.map((item: any, i: number) => (
            <div key={i} className="card flex items-start gap-3">
              <div className="w-7 h-7 rounded-lg bg-gray-800 flex items-center
                justify-center text-xs font-medium text-gray-400 flex-shrink-0 mt-0.5">
                {i + 1}
              </div>
              <div className="flex-1 min-w-0">
                <div className="flex items-center gap-2 mb-1">
                  <span className="text-sm font-medium text-gray-200">{item.topic}</span>
                  <span className={`badge ${priorityColor[item.priority] ?? 'badge-gray'}`}>
                    {item.priority}
                  </span>
                </div>
                <div className="flex gap-4 text-xs text-gray-500">
                  <span>Mastery {item.masteryScore ?? 0}%</span>
                  {(item.decayScore ?? 0) > 5 && (
                    <span className="text-amber-500">Decay {item.decayScore}%</span>
                  )}
                </div>
              </div>
            </div>
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
      <div className="flex items-center justify-between">
        <p className="text-sm text-gray-400 flex items-center gap-1.5">
          <Clock size={13} className="text-teal-400" />
          {items.length} topic{items.length !== 1 ? 's' : ''} due for revision
        </p>
      </div>

      {items.length === 0 ? (
        <EmptyState
          icon={<Check size={24} className="text-green-400" />}
          title="All caught up!"
          desc="No revisions due right now. Topics appear here based on spaced repetition decay scores."
        />
      ) : (
        <div className="space-y-3">
          {items.map((item: any) => (
            <div key={item.revisionId}
              className="card flex items-center justify-between gap-4">
              <div className="flex-1 min-w-0">
                <p className="text-sm font-medium text-gray-200">{item.topic}</p>
                <div className="flex gap-4 mt-1 text-xs text-gray-500">
                  <span>Priority {item.revisionPriority ?? 0}</span>
                  <span className="text-amber-500">Decay {(item.decayScore ?? 0).toFixed(0)}%</span>
                  <span>Interval {item.intervalDays}d</span>
                  <span>Rep #{item.repetitionCount}</span>
                </div>
              </div>
              <button
                onClick={() => markDone.mutate(item.revisionId)}
                disabled={markDone.isPending}
                className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs
                           bg-green-900/30 text-green-400 border border-green-800
                           hover:bg-green-900/50 transition-colors flex-shrink-0"
              >
                <Check size={12} /> Done
              </button>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}

function TopicCard({ item, index }: { item: any; index: number }) {
  return (
    <div className="card flex items-start gap-3">
      <div className="w-7 h-7 rounded-lg bg-indigo-900/50 border border-indigo-800
        flex items-center justify-center text-xs font-medium text-indigo-400 flex-shrink-0 mt-0.5">
        {index}
      </div>
      <div className="flex-1">
        <div className="flex items-center gap-2 mb-1">
          <span className="text-sm font-medium text-gray-200">{item.topic}</span>
          {item.targetDifficulty && (
            <span className="badge badge-blue">~{item.targetDifficulty} diff</span>
          )}
        </div>
        {item.reason && (
          <p className="text-xs text-gray-500">{item.reason}</p>
        )}
      </div>
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
      <p className="text-gray-500 text-sm max-w-sm mx-auto">{desc}</p>
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
