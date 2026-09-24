import { useState } from 'react'
import { clsx } from 'clsx'
import { Globe, Loader2, School, Swords } from 'lucide-react'
import { DomjudgeContestPicker } from './DomjudgeContestPicker'
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

  const submit = () => {
    if (url.trim()) onLoad(platform, url.trim())
  }

  return (
    <div className="card max-w-2xl mx-auto mt-10 flex flex-col gap-4">
      <div className="flex items-center gap-2">
        <Swords size={18} className="text-indigo-400" />
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
                ? 'border-indigo-600 bg-indigo-600/10'
                : 'border-gray-800 bg-gray-900 hover:border-gray-700 hover:bg-gray-800/60'
            )}
          >
            <Icon size={18} className={clsx('mt-0.5 flex-shrink-0',
              platform === id ? 'text-indigo-400' : 'text-gray-500')} />
            <span className="min-w-0">
              <span className={clsx('block text-sm font-medium',
                platform === id ? 'text-indigo-200' : 'text-gray-200')}>
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
            Pick the contest you are sitting. Your DOMjudge account is attached by whoever set
            the round up, so there is nothing to connect — the statements, an editor, your
            submissions, your live rank and the board all appear here once it starts.
          </>
        ) : (
          <>
            Register for the contest on the platform itself, then paste its link here. Once the
            contest starts you get the statements, an editor, your submissions and your live
            rank on this one page. Nothing here registers you or affects your rating — that
            stays entirely between you and the platform.
          </>
        )}
      </p>


      {platform === 'DOMJUDGE' ? (
        <DomjudgeContestPicker onPick={id => onLoad('DOMJUDGE', id)} loading={loading} />
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
          className="btn-primary py-2 px-5 text-sm flex items-center gap-2 flex-shrink-0"
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
