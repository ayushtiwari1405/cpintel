import { useState } from 'react'
import { clsx } from 'clsx'
import { Globe, Loader2, School, Swords } from 'lucide-react'
import { DomjudgeContestPicker } from './DomjudgeContestPicker'
import { ClassroomSwitcher } from '@/components/common/ClassroomSwitcher'
import { useSelectedClassroom } from '@/hooks/useClassrooms'
import type { CompetePlatform } from '@/types'

/** The two judges, as tiles rather than a dropdown: the choice is the first thing on the page. */
const JUDGES = [
  {
    id: 'CODEFORCES',
    label: 'Codeforces',
    blurb: 'A public round you have registered for',
    icon: Globe,
  },
  {
    id: 'DOMJUDGE',
    label: 'DOMjudge',
    blurb: 'A contest set up for your class',
    icon: School,
  },
] as const

interface Props {
  onLoad: (platform: CompetePlatform, url: string) => void
  loading: boolean
}

export function ContestLoader({ onLoad, loading }: Props) {
  const [platform, setPlatform] = useState<CompetePlatform>('CODEFORCES')
  const [url, setUrl] = useState('')
  const [classroomId, setClassroomId] = useSelectedClassroom()

  const submit = () => {
    if (url.trim()) onLoad(platform, url.trim())
  }

  return (
    <div className="card max-w-2xl mx-auto mt-10 flex flex-col gap-4">
      <div className="flex items-center gap-2">
        <Swords size={18} className="text-accent-400" />
        <span className="text-base font-medium text-gray-100">Compete</span>
      </div>

      <div role="radiogroup" aria-label="Judge" className="grid gap-3 sm:grid-cols-2">
        {JUDGES.map(({ id, label, blurb, icon: Icon }) => (
          <button
            key={id}
            role="radio"
            aria-checked={platform === id}
            onClick={() => setPlatform(id)}
            className={clsx(
              'flex items-start gap-3 rounded-xl border px-4 py-3 text-left transition-colors',
              platform === id
                ? 'border-accent-600 bg-accent-600/10'
                : 'border-gray-800 bg-gray-900 hover:border-gray-700 hover:bg-gray-800/60'
            )}
          >
            <Icon size={18} className={clsx('mt-0.5 shrink-0',
              platform === id ? 'text-accent-400' : 'text-gray-500')} />
            <span className="min-w-0">
              <span className={clsx('block text-sm font-medium',
                platform === id ? 'text-accent-200' : 'text-gray-200')}>
                {label}
              </span>
              <span className="block text-xs text-gray-500">{blurb}</span>
            </span>
          </button>
        ))}
      </div>

      <p className="text-sm text-gray-500 leading-relaxed">
        {platform === 'DOMJUDGE' ? (
          <>
            Pick the contest you are sitting. Your DOMjudge account is already attached.
          </>
        ) : (
          <>
            Register on the platform, then paste the contest link. CPIntel does not register you
            or affect your rating.
          </>
        )}
      </p>


      {platform === 'DOMJUDGE' ? (
        <div className="flex flex-col gap-3">
          <ClassroomSwitcher value={classroomId} onChange={setClassroomId} />
          <DomjudgeContestPicker onPick={id => onLoad('DOMJUDGE', id)} loading={loading}
            classroomId={classroomId} />
        </div>
      ) : (
      <>
      <div className="flex gap-2">
        <input
          value={url}
          onChange={e => setUrl(e.target.value)}
          onKeyDown={e => { if (e.key === 'Enter') submit() }}
          placeholder="https://codeforces.com/contest/2259"
          className="input py-2 text-sm flex-1"
        />

        <button
          onClick={submit}
          disabled={loading || !url.trim()}
          className="btn-primary py-2 px-5 text-sm flex items-center gap-2 shrink-0"
        >
          {loading && <Loader2 size={14} className="animate-spin" />}
          Load
        </button>
      </div>

      <p className="text-[11px] text-gray-600">
        A contest id works too, as does a link to a problem inside the contest.
      </p>
      </>
      )}
    </div>
  )
}
