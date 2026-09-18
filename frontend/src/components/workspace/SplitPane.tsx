import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react'
import { clsx } from 'clsx'

const clamp = (value: number, lo: number, hi: number) => Math.min(hi, Math.max(lo, value))

interface Props {
  /** `horizontal` puts the panes side by side; `vertical` stacks them. */
  direction?: 'horizontal' | 'vertical'
  /** Remembers where the divider was left, per workspace. */
  storageKey?: string
  /** Size of the first pane, as a percentage of the container. */
  initial?: number
  min?: number
  max?: number
  first: ReactNode
  second: ReactNode
  className?: string
}

/**
 * A draggable divider between two panes.
 *
 * Every problem-solving site is built on this one gesture — widen the statement to read it,
 * widen the editor to write — so it has to be reliable rather than clever. Three details do
 * most of that work:
 *
 * Pointer capture. Monaco and scraped statement HTML both swallow mouse events, so a drag
 * that started on the divider would die the moment the cursor crossed into a pane. Capturing
 * the pointer keeps every move coming back here until the button is released.
 *
 * Panes go inert mid-drag. Without it, dragging across the editor leaves a trail of text
 * selection behind the cursor.
 *
 * Percentages, not pixels. The split then survives a window resize, a collapsed sidebar, and
 * the difference between a laptop and an external monitor, which a stored pixel width does not.
 */
export function SplitPane({
  direction = 'horizontal', storageKey, initial = 50, min = 20, max = 80,
  first, second, className,
}: Props) {
  const horizontal = direction === 'horizontal'
  const containerRef = useRef<HTMLDivElement>(null)
  const [dragging, setDragging] = useState(false)

  const [size, setSize] = useState(() => {
    if (storageKey) {
      try {
        const saved = Number(localStorage.getItem(storageKey))
        if (Number.isFinite(saved) && saved > 0) return clamp(saved, min, max)
      } catch { /* private mode, or storage disabled */ }
    }
    return clamp(initial, min, max)
  })

  useEffect(() => {
    if (!storageKey) return
    try { localStorage.setItem(storageKey, size.toFixed(1)) } catch { /* not important enough to fail on */ }
  }, [size, storageKey])

  const moveTo = useCallback((clientX: number, clientY: number) => {
    const rect = containerRef.current?.getBoundingClientRect()
    if (!rect) return
    const pct = horizontal
      ? ((clientX - rect.left) / rect.width) * 100
      : ((clientY - rect.top) / rect.height) * 100
    setSize(clamp(pct, min, max))
  }, [horizontal, min, max])

  return (
    <div
      ref={containerRef}
      className={clsx('flex min-h-0 min-w-0', horizontal ? 'flex-row' : 'flex-col', className)}
    >
      <div
        className={clsx('min-h-0 min-w-0 flex', dragging && 'pointer-events-none select-none')}
        style={{ flexBasis: `${size}%`, flexGrow: 0, flexShrink: 0 }}
      >
        {first}
      </div>

      <div
        role="separator"
        aria-orientation={horizontal ? 'vertical' : 'horizontal'}
        aria-valuenow={Math.round(size)}
        aria-valuemin={min}
        aria-valuemax={max}
        aria-label="Resize panels"
        tabIndex={0}
        onPointerDown={e => {
          e.preventDefault()
          e.currentTarget.setPointerCapture(e.pointerId)
          setDragging(true)
        }}
        onPointerMove={e => { if (dragging) moveTo(e.clientX, e.clientY) }}
        onPointerUp={e => {
          e.currentTarget.releasePointerCapture(e.pointerId)
          setDragging(false)
        }}
        onPointerCancel={() => setDragging(false)}
        // Reaching the divider by keyboard is the only way to resize without a mouse, and
        // double-click is the shortcut everyone tries when a pane has been dragged too far.
        onKeyDown={e => {
          const back = horizontal ? 'ArrowLeft' : 'ArrowUp'
          const forward = horizontal ? 'ArrowRight' : 'ArrowDown'
          if (e.key === back) { e.preventDefault(); setSize(s => clamp(s - 2, min, max)) }
          if (e.key === forward) { e.preventDefault(); setSize(s => clamp(s + 2, min, max)) }
          if (e.key === 'Home') { e.preventDefault(); setSize(min) }
          if (e.key === 'End') { e.preventDefault(); setSize(max) }
        }}
        onDoubleClick={() => setSize(clamp(initial, min, max))}
        title="Drag to resize · double-click to reset"
        className={clsx(
          'group relative flex-shrink-0 z-10 transition-colors',
          'focus:outline-none focus-visible:bg-indigo-500',
          horizontal ? 'w-1.5 cursor-col-resize' : 'h-1.5 cursor-row-resize',
          dragging ? 'bg-indigo-500' : 'bg-gray-900 hover:bg-indigo-600/60'
        )}
      >
        {/* A 6px target is hard to hit; this widens the grab area without widening the line. */}
        <span
          className={clsx(
            'absolute',
            horizontal ? '-left-1.5 -right-1.5 inset-y-0' : '-top-1.5 -bottom-1.5 inset-x-0'
          )}
        />
      </div>

      <div className={clsx(
        'flex-1 min-h-0 min-w-0 flex',
        dragging && 'pointer-events-none select-none'
      )}>
        {second}
      </div>
    </div>
  )
}
