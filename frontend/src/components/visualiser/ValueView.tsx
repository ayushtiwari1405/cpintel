import { clsx } from 'clsx'
import type { Compound, Val } from '@/visualiser/trace'

/**
 * How one value looks in the memory view.
 *
 * Primitives are written in place. A reference or pointer is a dot that an arrow leaves from:
 * the dot carries `data-arrow-to`, the thing it points at carries the matching `data-anchor`,
 * and <Arrows> joins them after layout. A compound held in place (a C++ vector local) is drawn
 * as the same strip of cells a heap list is, with its own anchors so a pointer into it lands on
 * the element.
 */

const PRIM_CLASS: Record<string, string> = {
  num: 'text-sky-300',
  str: 'text-amber-300',
  char: 'text-amber-300',
  bool: 'text-violet-300',
  none: 'text-violet-300',
}

export function Prim({ v }: { v: Extract<Val, { k: 'num' | 'str' | 'bool' | 'none' | 'char' }> }) {
  const text = v.k === 'str' ? JSON.stringify(v.v) : v.k === 'char' ? `'${v.v}'` : v.v
  return <span className={clsx('font-mono', PRIM_CLASS[v.k])}>{text}</span>
}

/** The target id an arrow for this pointer should land on: the element when it has one. */
export function arrowTarget(v: Extract<Val, { k: 'ref' | 'ptr' }>): string | null {
  if (v.k === 'ref') return v.id
  if (v.to == null) return null
  return v.idx != null ? `${v.to}#${v.idx}` : v.to
}

export function ValueView({ v, anchor, prev, changed }: {
  v: Val
  /** Anchor id for an inline compound, so pointers can land on it. */
  anchor?: string
  /** The same value one step earlier, to mark what changed inside it. */
  prev?: Val
  changed?: boolean
}) {
  switch (v.k) {
    case 'num': case 'str': case 'bool': case 'none': case 'char':
      return <span className={clsx(changed && 'viz-changed rounded-sm px-0.5')}><Prim v={v} /></span>
    case 'ref': case 'ptr': {
      const to = arrowTarget(v)
      if (v.k === 'ptr' && to == null) {
        const isNull = /^0x0+$/.test(v.addr) || v.addr === '0'
        return (
          <span className="font-mono text-xs text-gray-500"
            title={isNull ? 'nullptr' : `${v.addr}: not a live object (dangling or uninitialised)`}>
            {isNull ? 'nullptr' : <span className="text-red-300">{v.addr} ⚠</span>}
          </span>
        )
      }
      const ref = v.k === 'ptr' && v.t.endsWith('&')
      return (
        <span className="inline-flex items-center gap-1.5">
          {/* The label goes first: the arrow leaves the dot rightwards and must not cross it. */}
          {v.k === 'ptr' && (
            <span className="font-mono text-[10px] text-gray-500">{ref ? 'ref' : v.addr}</span>
          )}
          <span
            data-arrow-to={to ?? undefined}
            data-arrow-kind={ref ? 'ref' : 'ptr'}
            className={clsx('inline-block h-2.5 w-2.5 rounded-full',
              ref ? 'border-2 border-violet-400' : 'bg-accent-400',
              changed && 'ring-2 ring-accent-300/50')}
            aria-label={`points to ${to}`}
          />
        </span>
      )
    }
    case 'opaque':
      return (
        <span className="font-mono text-xs text-gray-400" title={v.t}>
          {v.t === 'function' ? `ƒ ${v.v}` : v.t === 'class' ? `class ${v.v}` : v.v}
        </span>
      )
    default:
      return <CompoundView c={v} anchor={anchor}
        prev={prev && prev.k === v.k ? prev as Compound : undefined} inline />
  }
}

/** A list, map, object or opaque blob, on the heap or held inline. */
export function CompoundView({ c, anchor, prev, inline }: {
  c: Compound
  anchor?: string
  prev?: Compound
  inline?: boolean
}) {
  if (c.k === 'seq') {
    const prevItems = prev?.k === 'seq' ? prev.items : null
    const tupleLike = c.t === 'tuple' || c.t === 'pair'
    return (
      <div className="inline-flex flex-col gap-0.5">
        {!inline && <TypeLabel t={c.t} n={c.items.length + c.more} />}
        {c.items.length === 0 && c.more === 0 ? (
          <span className="rounded-sm border border-dashed border-gray-700 px-2 py-1 font-mono text-[11px] text-gray-500">
            empty
          </span>
        ) : (
          <div className="flex flex-wrap items-stretch">
            {c.items.map((item, i) => {
              const changed = prevItems != null
                && JSON.stringify(prevItems[i]) !== JSON.stringify(item)
              const nested = item.k === 'seq' || item.k === 'map' || item.k === 'obj'
              return (
                <div key={i} data-anchor={anchor ? `${anchor}#${i}` : undefined}
                  className={clsx('-ml-px flex flex-col items-center border border-gray-700 first:ml-0',
                    tupleLike ? 'bg-gray-850' : 'bg-gray-900',
                    changed && 'viz-changed')}>
                  {!tupleLike && (
                    <span className="w-full border-b border-gray-800 px-1.5 text-center font-mono text-[9px] leading-4 text-gray-500">
                      {i}
                    </span>
                  )}
                  <span className={clsx('flex min-h-7 min-w-7 items-center justify-center px-1.5 py-0.5 text-xs',
                    nested && 'p-1')}>
                    <ValueView v={item} anchor={anchor ? `${anchor}#${i}` : undefined}
                      prev={prevItems?.[i]} />
                  </span>
                </div>
              )
            })}
            {c.more > 0 && (
              <span className="flex items-center px-2 font-mono text-[11px] text-gray-500">
                +{c.more} more
              </span>
            )}
          </div>
        )}
      </div>
    )
  }
  if (c.k === 'map') {
    const before = new Map(prev?.k === 'map'
      ? prev.entries.map(([k, v]) => [JSON.stringify(k), JSON.stringify(v)]) : [])
    return (
      <div className="inline-flex flex-col gap-0.5">
        {!inline && <TypeLabel t={c.t} n={c.entries.length + c.more} />}
        {c.entries.length === 0 ? (
          <span className="rounded-sm border border-dashed border-gray-700 px-2 py-1 font-mono text-[11px] text-gray-500">
            empty
          </span>
        ) : (
          <table className="border-collapse text-xs">
            <tbody>
              {c.entries.map(([k, v], i) => {
                const was = before.get(JSON.stringify(k))
                const changed = prev != null && was !== JSON.stringify(v)
                return (
                  <tr key={i} className={clsx(changed && 'viz-changed')}>
                    <td className="border border-gray-700 bg-gray-850 px-1.5 py-0.5"><ValueView v={k} /></td>
                    <td className="border border-gray-700 px-1.5 py-0.5"><ValueView v={v} /></td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        )}
        {c.more > 0 && <span className="font-mono text-[11px] text-gray-500">+{c.more} more</span>}
      </div>
    )
  }
  if (c.k === 'obj') {
    const before = new Map(prev?.k === 'obj'
      ? prev.fields.map(([n, v]) => [n, JSON.stringify(v)]) : [])
    return (
      <div className="inline-flex flex-col gap-0.5">
        <TypeLabel t={c.t} />
        <table className="border-collapse text-xs">
          <tbody>
            {c.fields.map(([name, v]) => {
              const changed = prev != null && before.get(name) !== JSON.stringify(v)
              return (
                <tr key={name} className={clsx(changed && 'viz-changed')}>
                  <td className="border border-gray-700 bg-gray-850 px-1.5 py-0.5 font-mono text-gray-400">{name}</td>
                  <td className="border border-gray-700 px-1.5 py-0.5"><ValueView v={v} prev={undefined} /></td>
                </tr>
              )
            })}
            {c.fields.length === 0 && (
              <tr><td className="border border-dashed border-gray-700 px-2 py-0.5 text-gray-500">no fields</td></tr>
            )}
          </tbody>
        </table>
      </div>
    )
  }
  return (
    <div className="inline-flex flex-col gap-0.5">
      <TypeLabel t={c.t} />
      <span className="rounded-sm border border-gray-700 bg-gray-900 px-2 py-1 font-mono text-xs text-gray-300">{c.v}</span>
    </div>
  )
}

function TypeLabel({ t, n }: { t: string; n?: number }) {
  return (
    <span className="font-mono text-[10px] uppercase tracking-wide text-gray-500">
      {t}{n != null && <span className="ml-1 normal-case text-gray-600">· {n}</span>}
    </span>
  )
}
