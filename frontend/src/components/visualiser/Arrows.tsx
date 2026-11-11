import { useLayoutEffect, useState, type RefObject } from 'react'

interface Path { d: string; kind: string; key: string }

/**
 * Draws an arrow from every `[data-arrow-to]` dot inside `container` to the element whose
 * `data-anchor` matches, after the browser has laid both out. Re-measures when the content
 * changes (`version`) or the container resizes; the SVG sits inside the scrolling content, so
 * scrolling moves the arrows with everything else and needs no work.
 */
export function Arrows({ container, version }: {
  container: RefObject<HTMLElement | null>
  version: unknown
}) {
  const [paths, setPaths] = useState<Path[]>([])
  const [size, setSize] = useState({ w: 0, h: 0 })

  useLayoutEffect(() => {
    let observer: ResizeObserver | null = null
    let frame = 0

    const measure = () => {
      const root = container.current
      if (!root) return
      const base = root.getBoundingClientRect()
      const at = (r: DOMRect) => ({
        left: r.left - base.left + root.scrollLeft,
        top: r.top - base.top + root.scrollTop,
        width: r.width, height: r.height,
      })
      const anchors = new Map<string, HTMLElement>()
      root.querySelectorAll<HTMLElement>('[data-anchor]').forEach(el => {
        anchors.set(el.dataset.anchor!, el)
      })
      const next: Path[] = []
      root.querySelectorAll<HTMLElement>('[data-arrow-to]').forEach((dot, i) => {
        const id = dot.dataset.arrowTo!
        // An element index that is not drawn (past the shown items) falls back to the whole.
        const target = anchors.get(id) ?? anchors.get(id.split('#')[0])
        if (!target) return
        const s = at(dot.getBoundingClientRect())
        const t = at(target.getBoundingClientRect())
        const x1 = s.left + s.width / 2
        const y1 = s.top + s.height / 2
        // Land on the near side of the target, a little below its top edge.
        const leftward = t.left + t.width / 2 < x1
        const x2 = leftward ? t.left + t.width : t.left
        const y2 = t.top + Math.min(12, t.height / 2)
        const dx = Math.max(40, Math.abs(x2 - x1) / 2)
        const c1 = x1 + dx
        const c2 = leftward ? x2 + dx : x2 - dx
        next.push({
          d: `M${x1},${y1} C${c1},${y1} ${c2},${y2} ${x2},${y2}`,
          kind: dot.dataset.arrowKind ?? 'ptr',
          key: `${i}-${id}`,
        })
      })
      setPaths(next)
      setSize({ w: root.scrollWidth, h: root.scrollHeight })
    }

    const start = () => {
      const root = container.current
      if (!root) return
      measure()
      observer = new ResizeObserver(measure)
      observer.observe(root)
      for (const child of Array.from(root.children)) observer.observe(child)
    }
    // React attaches a parent's ref after its children's layout effects, so on the view's first
    // render the container is not there yet: start on the next frame instead.
    if (container.current) start()
    else frame = requestAnimationFrame(start)
    return () => { cancelAnimationFrame(frame); observer?.disconnect() }
  }, [container, version])

  return (
    <svg aria-hidden="true" className="pointer-events-none absolute left-0 top-0 overflow-visible"
      width={size.w} height={size.h}>
      <defs>
        <marker id="viz-arrow" viewBox="0 0 8 8" refX="7" refY="4" markerWidth="7" markerHeight="7"
          orient="auto-start-reverse">
          <path d="M0,0 L8,4 L0,8 z" className="fill-accent-400" />
        </marker>
        <marker id="viz-arrow-ref" viewBox="0 0 8 8" refX="7" refY="4" markerWidth="7" markerHeight="7"
          orient="auto-start-reverse">
          <path d="M0,0 L8,4 L0,8 z" className="fill-violet-400" />
        </marker>
      </defs>
      {paths.map(p => (
        <path key={p.key} d={p.d} fill="none" strokeWidth={1.5}
          className={p.kind === 'ref' ? 'stroke-violet-400' : 'stroke-accent-400'}
          strokeDasharray={p.kind === 'ref' ? '4 3' : undefined}
          markerEnd={`url(#${p.kind === 'ref' ? 'viz-arrow-ref' : 'viz-arrow'})`} />
      ))}
    </svg>
  )
}
