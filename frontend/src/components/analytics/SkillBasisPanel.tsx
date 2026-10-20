import { Link } from 'react-router-dom'
import { Scale } from 'lucide-react'
import { clsx } from 'clsx'
import { useSetSkillBasis, useSkillBasis } from '@/hooks/useAnalytics'
import type { SkillBasis } from '@/types'
import { BASIS_LABEL } from './skill'
import { ErrorState, LoadingState } from '@/components/common/States'

const OPTIONS: { value: SkillBasis['requested']; label: string; hint: string }[] = [
  { value: 'AUTO', label: 'Automatic',
    hint: 'Rated rounds when you have any, otherwise the gauntlet.' },
  { value: 'CODEFORCES', label: 'Codeforces',
    hint: 'Your rating, moved per topic by how you did on its problems in rated rounds.' },
  { value: 'GAUNTLET', label: 'Gauntlet',
    hint: 'Your placement test. Choose this if your Codeforces rating does not reflect you — '
      + 'rounds sat with an AI\'s or someone else\'s help, or an old account. Rated rounds are '
      + 'then ignored entirely.' },
]

/** What skill ratings are based on, and the switch between the two sources. */
export function SkillBasisPanel() {
  const { data, isLoading, isError, error, refetch } = useSkillBasis()
  const set = useSetSkillBasis()

  if (isError) return <ErrorState title="Could not load what your ratings are based on" error={error} onRetry={() => refetch()} />
  if (isLoading || !data) return <div className="card"><LoadingState className="py-6" /></div>

  const unavailable = (v: SkillBasis['requested']) =>
    (v === 'CODEFORCES' && !data.codeforcesAvailable)
    || (v === 'GAUNTLET' && !data.gauntletAvailable)

  return (
    <div className="card space-y-3">
      <div className="flex items-start justify-between gap-4 flex-wrap">
        <div>
          <p className="text-sm font-medium text-gray-300 flex items-center gap-1.5">
            <Scale size={14} className="text-accent-400" /> What your ratings are based on
          </p>
          <p className="text-xs text-gray-500 mt-1">
            {data.basis
              ? <>Based on {BASIS_LABEL[data.basis]}, starting from <span
                  className="text-gray-300 tabular-nums">{data.rating}</span>.</>
              : data.requested === 'GAUNTLET'
                ? 'You chose the gauntlet but have not taken it yet, so this shows what you have practised instead.'
                : 'No rated rounds and no gauntlet yet, so this shows what you have practised instead of a rating.'}
          </p>
        </div>
        <div className="flex gap-1 bg-gray-900 border border-gray-800 rounded-lg p-1">
          {OPTIONS.map(o => (
            <button key={o.value}
              title={unavailable(o.value)
                ? `${o.hint} (Not available yet: ${o.value === 'CODEFORCES'
                  ? 'no rated rounds synced' : 'gauntlet not taken'}.)`
                : o.hint}
              disabled={set.isPending || data.requested === o.value}
              onClick={() => set.mutate(o.value)}
              className={clsx(
                'px-3 py-1 rounded-md text-xs font-medium transition-colors',
                data.requested === o.value
                  ? 'bg-accent-600 text-white'
                  : 'text-gray-400 hover:text-gray-200',
                unavailable(o.value) && data.requested !== o.value && 'opacity-50',
              )}>
              {o.label}
            </button>
          ))}
        </div>
      </div>
      {!data.gauntletAvailable && (
        <p className="text-xs text-gray-500">
          The <Link to="/roadmap/gauntlet" className="text-accent-400 hover:underline">placement
          gauntlet</Link> gives a rating per area that does not depend on Codeforces. Like any
          test taken alone it proves nothing, but it is your own work.
        </p>
      )}
    </div>
  )
}
