import { clsx } from 'clsx'
import {
  ChevronFirst, ChevronLast, Pause, Play, SkipBack, SkipForward, StepBack, StepForward,
} from 'lucide-react'
import type { Step } from '@/visualiser/trace'
import { brief } from '@/visualiser/trace'

export const SPEEDS = [0.5, 1, 2, 4, 8] as const

/**
 * The transport for a trace: first / back / play / forward / last, a scrubber, and the jumps a
 * teacher wants mid-lecture — to the previous or next breakpoint, or out of the current call.
 */
export function StepControls({
  step, count, current, playing, speed, onStep, onPlay, onSpeed, onBreakBack, onBreakNext,
  hasBreakpoints,
}: {
  step: number
  count: number
  current: Step | undefined
  playing: boolean
  speed: number
  onStep: (i: number) => void
  onPlay: (playing: boolean) => void
  onSpeed: (s: number) => void
  onBreakBack: () => void
  onBreakNext: () => void
  hasBreakpoints: boolean
}) {
  const last = count - 1
  const btn = 'flex h-8 w-8 items-center justify-center rounded-md text-gray-300 transition-colors '
    + 'hover:bg-gray-800 hover:text-gray-50 disabled:pointer-events-none disabled:opacity-35'
  return (
    <div className="flex shrink-0 flex-col gap-2 border-b border-gray-800 bg-gray-900 px-3 py-2">
      <div className="flex flex-wrap items-center gap-1">
        <button type="button" className={btn} onClick={() => onStep(0)} disabled={step <= 0}
          aria-label="First step" title="First step (Home)"><ChevronFirst size={16} /></button>
        <button type="button" className={btn} onClick={onBreakBack} disabled={step <= 0}
          aria-label={hasBreakpoints ? 'Previous breakpoint' : 'Back out of this call'}
          title={hasBreakpoints ? 'Previous breakpoint (Shift+←)' : 'Previous line in this call (Shift+←)'}>
          <SkipBack size={15} /></button>
        <button type="button" className={btn} onClick={() => onStep(step - 1)} disabled={step <= 0}
          aria-label="Step back" title="Step back (←)"><StepBack size={16} /></button>
        <button type="button" onClick={() => onPlay(!playing)} disabled={count === 0}
          className="mx-1 flex h-8 w-8 items-center justify-center rounded-full bg-accent-600 text-white transition-colors hover:bg-accent-500 disabled:opacity-40"
          aria-label={playing ? 'Pause' : 'Play'} title={playing ? 'Pause (Space)' : 'Play (Space)'}>
          {playing ? <Pause size={15} /> : <Play size={15} className="ml-0.5" />}
        </button>
        <button type="button" className={btn} onClick={() => onStep(step + 1)} disabled={step >= last}
          aria-label="Step forward" title="Step forward (→)"><StepForward size={16} /></button>
        <button type="button" className={btn} onClick={onBreakNext} disabled={step >= last}
          aria-label={hasBreakpoints ? 'Next breakpoint' : 'Step over'}
          title={hasBreakpoints ? 'Next breakpoint (Shift+→)' : 'Step over calls (Shift+→)'}>
          <SkipForward size={15} /></button>
        <button type="button" className={btn} onClick={() => onStep(last)} disabled={step >= last}
          aria-label="Last step" title="Last step (End)"><ChevronLast size={16} /></button>

        <label className="ml-2 flex items-center gap-1.5 text-[11px] text-gray-500">
          <span className="sr-only sm:not-sr-only">Speed</span>
          <select value={speed} onChange={e => onSpeed(Number(e.target.value))}
            className="rounded-md border border-gray-700 bg-gray-950 px-1.5 py-1 font-mono text-[11px] text-gray-300">
            {SPEEDS.map(s => <option key={s} value={s}>{s}×</option>)}
          </select>
        </label>

        <span className="ml-auto font-mono text-xs tabular-nums text-gray-400">
          Step <span className="text-gray-100">{count ? step + 1 : 0}</span> of {count}
        </span>
      </div>

      <div className="flex items-center gap-3">
        <input type="range" min={0} max={Math.max(0, last)} value={step}
          onChange={e => onStep(Number(e.target.value))} aria-label="Step"
          className="h-1.5 flex-1 cursor-pointer accent-[rgb(var(--c-accent-500))]" />
        {current && <EventBadge step={current} />}
      </div>
    </div>
  )
}

function EventBadge({ step }: { step: Step }) {
  const fn = step.frames[step.frames.length - 1]?.name ?? ''
  const text = step.event === 'call' ? `call ${fn}()`
    : step.event === 'return' ? `${fn}() returns ${step.ret ? brief(step.ret, step.heap) : ''}`
    : step.event === 'exception' ? step.error ?? 'exception'
    : `line ${step.line}`
  return (
    <span className={clsx('max-w-[55%] truncate rounded-sm border px-1.5 py-0.5 font-mono text-[11px]',
      step.event === 'call' && 'border-sky-800/70 bg-sky-950/60 text-sky-300',
      step.event === 'return' && 'border-green-800/70 bg-green-950/60 text-green-300',
      step.event === 'exception' && 'border-red-800/70 bg-red-950/60 text-red-300',
      step.event === 'line' && 'border-gray-700 bg-gray-850 text-gray-400')} title={text}>
      {text}
    </span>
  )
}
