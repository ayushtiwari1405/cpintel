import { clsx } from 'clsx'
import { Check, Circle, X } from 'lucide-react'
import type { ContestProblem, ContestSubmission } from '@/types'

interface Props {
  problems: ContestProblem[]
  selected: string | null
  onSelect: (index: string) => void
  submissions: ContestSubmission[]
}

type State = 'solved' | 'failed' | 'none'

/** Solved beats failed: one accepted submission settles the problem regardless of the rest. */
function stateOf(index: string, submissions: ContestSubmission[]): State {
  let failed = false
  for (const s of submissions) {
    if (s.index !== index) continue
    if (s.verdict === 'OK') return 'solved'
    if (s.finished) failed = true
  }
  return failed ? 'failed' : 'none'
}

export function ProblemNav({ problems, selected, onSelect, submissions }: Props) {
  if (problems.length === 0) {
    return (
      <div className="text-xs text-gray-600 p-2 leading-relaxed">
        Codeforces has not published the problem list yet. It appears when the contest starts.
      </div>
    )
  }

  return (
    <nav className="flex flex-col gap-1 overflow-y-auto">
      {problems.map(p => {
        const state = stateOf(p.index, submissions)
        const active = selected === p.index
        return (
          <button
            key={p.index}
            onClick={() => onSelect(p.index)}
            title={p.name ?? p.index}
            className={clsx(
              'flex items-center gap-2 px-2 py-2 rounded-lg text-left transition-colors',
              active ? 'bg-indigo-600/20 border border-indigo-700'
                : 'border border-transparent hover:bg-gray-800'
            )}
          >
            <span className={clsx(
              'font-mono text-sm w-5 flex-shrink-0',
              active ? 'text-indigo-300' : 'text-gray-300'
            )}>
              {p.index}
            </span>

            <span className="flex-1 min-w-0 truncate text-xs text-gray-500">
              {p.name ?? ''}
            </span>

            {state === 'solved' && <Check size={13} className="text-green-400 flex-shrink-0" />}
            {state === 'failed' && <X size={13} className="text-red-400 flex-shrink-0" />}
            {state === 'none' && (
              <Circle size={7} className="text-gray-700 flex-shrink-0" />
            )}
          </button>
        )
      })}
    </nav>
  )
}
