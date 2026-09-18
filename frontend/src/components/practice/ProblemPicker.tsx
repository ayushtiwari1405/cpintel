import { useEffect, useMemo, useState } from 'react'
import { Search, Loader2, Filter, X } from 'lucide-react'
import { clsx } from 'clsx'
import { useProblemSearch, useProblemTags } from '@/hooks/usePractice'
import type { ProblemSummary } from '@/types'

interface Props {
  selected?: { contestId: number; index: string }
  onSelect: (p: ProblemSummary) => void
  disabled?: boolean
  /**
   * Rating window to start from - the difficulty band of the skill the user arrived under.
   * Searching the whole problemset from inside a skill returns mostly problems that are not
   * practice for it.
   */
  initialMinRating?: number
  initialMaxRating?: number
}

function ratingColor(rating?: number) {
  if (!rating) return 'text-gray-500'
  if (rating < 1200) return 'text-gray-400'
  if (rating < 1400) return 'text-green-400'
  if (rating < 1600) return 'text-cyan-400'
  if (rating < 1900) return 'text-blue-400'
  if (rating < 2100) return 'text-purple-400'
  if (rating < 2400) return 'text-amber-400'
  return 'text-red-400'
}

export function ProblemPicker({
  selected, onSelect, disabled, initialMinRating, initialMaxRating,
}: Props) {
  const [q, setQ] = useState('')
  const [minRating, setMinRating] = useState<string>(
    initialMinRating ? String(initialMinRating) : '')
  const [maxRating, setMaxRating] = useState<string>(
    initialMaxRating ? String(initialMaxRating) : '')
  const [tag, setTag] = useState('')
  const [showFilters, setShowFilters] = useState(false)

  // Moving between skills has to move the band with it. Initial state alone would leave the
  // second skill searching inside the first one's range, which looks like a broken filter.
  useEffect(() => {
    setMinRating(initialMinRating ? String(initialMinRating) : '')
    setMaxRating(initialMaxRating ? String(initialMaxRating) : '')
  }, [initialMinRating, initialMaxRating])

  const query = useMemo(() => ({
    q: q.trim() || undefined,
    minRating: minRating ? Number(minRating) : undefined,
    maxRating: maxRating ? Number(maxRating) : undefined,
    tag: tag || undefined,
    limit: 60,
  }), [q, minRating, maxRating, tag])

  const { data: problems, isLoading, isFetching } = useProblemSearch(query)
  const { data: tags } = useProblemTags()

  const hasFilters = !!(minRating || maxRating || tag)

  return (
    <div className="flex flex-col h-full min-h-0">
      <div className="space-y-2 pb-3">
        <div className="flex gap-2">
          <div className="relative flex-1">
            <Search size={14}
              className="absolute left-3 top-1/2 -translate-y-1/2 text-gray-500" />
            <input
              value={q}
              onChange={e => setQ(e.target.value)}
              disabled={disabled}
              placeholder="1900C or problem name…"
              className="input pl-9 py-1.5 text-sm"
            />
          </div>
          <button
            onClick={() => setShowFilters(v => !v)}
            disabled={disabled}
            className={clsx(
              'px-2.5 rounded-lg border transition-colors',
              hasFilters || showFilters
                ? 'border-indigo-700 bg-indigo-600/20 text-indigo-400'
                : 'border-gray-700 bg-gray-800 text-gray-400 hover:text-gray-200'
            )}
          >
            <Filter size={14} />
          </button>
        </div>

        {showFilters && (
          <div className="space-y-2 p-3 rounded-lg bg-gray-900/60 border border-gray-800">
            <div className="flex items-center gap-2">
              <input
                type="number" step={100} value={minRating}
                onChange={e => setMinRating(e.target.value)}
                placeholder="min" className="input py-1 text-xs" />
              <span className="text-gray-600 text-xs">–</span>
              <input
                type="number" step={100} value={maxRating}
                onChange={e => setMaxRating(e.target.value)}
                placeholder="max" className="input py-1 text-xs" />
            </div>
            <select
              value={tag} onChange={e => setTag(e.target.value)}
              className="input py-1 text-xs">
              <option value="">All tags</option>
              {tags?.map(t => <option key={t} value={t}>{t}</option>)}
            </select>
            {hasFilters && (
              <button
                onClick={() => { setMinRating(''); setMaxRating(''); setTag('') }}
                className="flex items-center gap-1 text-xs text-gray-500 hover:text-gray-300">
                <X size={11} /> Clear filters
              </button>
            )}
          </div>
        )}
      </div>

      {/* A dense list rather than a stack of cards. This is a table of problems you scan and
          then leave alone, so fitting more of it on screen beats giving each row a border. */}
      <div className="flex-1 min-h-0 overflow-y-auto -mx-1">
        {isLoading ? (
          <div className="flex justify-center py-8">
            <Loader2 className="animate-spin text-gray-600" size={20} />
          </div>
        ) : !problems?.length ? (
          <p className="text-xs text-gray-500 text-center py-8">
            No problems match that search.
          </p>
        ) : (
          <div className="divide-y divide-gray-800/70">
            {problems.map(p => {
              const isActive = selected?.contestId === p.contestId
                && selected?.index === p.index
              return (
                <button
                  key={`${p.contestId}${p.index}`}
                  onClick={() => onSelect(p)}
                  disabled={disabled}
                  className={clsx(
                    'w-full flex items-baseline gap-3 px-3 py-2 text-left transition-colors',
                    'disabled:opacity-40 disabled:cursor-not-allowed',
                    isActive
                      ? 'bg-indigo-600/15 border-l-2 border-l-indigo-500 pl-[10px]'
                      : 'border-l-2 border-l-transparent hover:bg-gray-900'
                  )}
                >
                  <span className="w-14 flex-shrink-0 font-mono text-[11px] text-gray-600">
                    {p.contestId}{p.index}
                  </span>

                  <span className="min-w-0 flex-1">
                    <span className={clsx(
                      'block truncate text-sm',
                      isActive ? 'text-indigo-200' : 'text-gray-200'
                    )}>
                      {p.name}
                    </span>
                    {p.tags.length > 0 && (
                      <span className="block truncate text-[11px] text-gray-600">
                        {p.tags.slice(0, 3).join(' · ')}
                      </span>
                    )}
                  </span>

                  <span className={clsx(
                    'flex-shrink-0 text-xs font-medium tabular-nums',
                    ratingColor(p.rating)
                  )}>
                    {p.rating ?? '—'}
                  </span>
                </button>
              )
            })}
          </div>
        )}
      </div>

      {isFetching && !isLoading && (
        <p className="text-[11px] text-gray-600 pt-2">Refreshing…</p>
      )}
    </div>
  )
}
