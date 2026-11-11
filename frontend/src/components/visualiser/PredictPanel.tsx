import { useEffect, useRef, useState } from 'react'
import { clsx } from 'clsx'
import { Check, HelpCircle, X } from 'lucide-react'
import { brief } from '@/visualiser/trace'
import { isRight, type Question } from '@/visualiser/predict'

export interface Score { asked: number; right: number }

/**
 * The quiz shown before a step runs in predict mode: one box per value the line is about to
 * change. Check reveals the answers and scores them; Continue (or → again) runs the step.
 */
export function PredictPanel({ line, questions, score, onScored, onContinue, onSkip }: {
  line: number
  questions: Question[]
  score: Score
  onScored: (right: number, asked: number) => void
  onContinue: () => void
  onSkip: () => void
}) {
  const [guesses, setGuesses] = useState<string[]>(() => questions.map(() => ''))
  const [revealed, setRevealed] = useState(false)
  const first = useRef<HTMLInputElement>(null)
  useEffect(() => { first.current?.focus() }, [])

  const check = () => {
    if (revealed) { onContinue(); return }
    const right = questions.filter((q, i) => isRight(guesses[i], q.answer)).length
    onScored(right, questions.length)
    setRevealed(true)
  }

  return (
    <form className="shrink-0 border-b border-sky-800/60 bg-sky-950/30 px-3 py-2.5"
      onSubmit={e => { e.preventDefault(); check() }} aria-label="Predict the next step">
      <div className="mb-2 flex items-center gap-2">
        <HelpCircle size={14} className="text-sky-300" aria-hidden="true" />
        <p className="flex-1 text-sm text-sky-100">
          Line <span className="font-mono">{line}</span> is about to run. What will these be afterwards?
        </p>
        <span className="font-mono text-[11px] text-sky-300/80" title="Right so far">
          {score.right}/{score.asked} right
        </span>
      </div>
      <div className="flex flex-wrap items-start gap-x-4 gap-y-2">
        {questions.map((q, i) => {
          const ok = revealed && isRight(guesses[i], q.answer)
          return (
            <label key={q.label} className="flex flex-col gap-1">
              <span className="font-mono text-xs text-gray-300">
                {q.label}
                {q.before && <span className="ml-1.5 text-[10px] text-gray-500">was {brief(q.before)}</span>}
              </span>
              <span className="flex items-center gap-1.5">
                <input ref={i === 0 ? first : undefined} value={guesses[i]} readOnly={revealed}
                  onChange={e => setGuesses(g => g.map((x, k) => (k === i ? e.target.value : x)))}
                  className={clsx('input w-28 py-1 font-mono text-xs',
                    revealed && (ok ? 'border-green-600' : 'border-red-600'))}
                  aria-label={`Predicted ${q.label}`} autoComplete="off" spellCheck={false} />
                {revealed && (ok
                  ? <Check size={15} className="text-green-400" aria-label="right" />
                  : (
                    <span className="flex items-center gap-1 font-mono text-xs text-red-300">
                      <X size={14} aria-hidden="true" /> {brief(q.answer)}
                    </span>
                  ))}
              </span>
            </label>
          )
        })}
        <div className="ml-auto flex items-center gap-2 self-end">
          {!revealed && (
            <button type="button" className="btn-ghost btn-sm" onClick={onSkip}>Skip</button>
          )}
          <button type="submit" className="btn-primary btn-sm">{revealed ? 'Continue' : 'Check'}</button>
        </div>
      </div>
    </form>
  )
}
