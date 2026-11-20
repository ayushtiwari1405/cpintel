import { useState } from 'react'
import { Loader2, Swords } from 'lucide-react'
import type { CompetePlatform } from '@/types'

interface Props {
  onLoad: (platform: CompetePlatform, url: string) => void
  loading: boolean
}

/**
 * Opens a Codeforces round. Class contests on DOMjudge are no longer picked here: students
 * reach their class work through Labs, and examinations through Exams.
 */
export function ContestLoader({ onLoad, loading }: Props) {
  const [url, setUrl] = useState('')

  const submit = () => {
    if (url.trim()) onLoad('CODEFORCES', url.trim())
  }

  return (
    <div className="card max-w-2xl mx-auto mt-10 flex flex-col gap-4">
      <div className="flex items-center gap-2">
        <Swords size={18} className="text-accent-400" />
        <span className="text-base font-medium text-gray-100">Compete on Codeforces</span>
      </div>

      <p className="text-sm text-gray-500">Paste a contest link or id you have registered for.</p>

      <div className="flex gap-2">
        <input
          value={url}
          onChange={e => setUrl(e.target.value)}
          onKeyDown={e => { if (e.key === 'Enter') submit() }}
          placeholder="https://codeforces.com/contest/2259"
          aria-label="Contest link or id"
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
    </div>
  )
}
