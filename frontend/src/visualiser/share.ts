import type { VizLanguage } from './trace'

/**
 * A visualisation packed into a link: code, input, the teacher's notes and breakpoints, and the
 * step to open at. It lives in the URL fragment, which browsers never send to the server, so a
 * shared link needs no storage and leaks nothing into logs. Opening it re-runs the trace.
 */
export interface Shared {
  language: VizLanguage
  code: string
  stdin: string
  /** Teaching notes, by step index. */
  notes: Record<number, string>
  breakpoints: number[]
  step: number
  /** Recording starts at the first breakpoint. */
  fromBreakpoint: boolean
}

const PREFIX = 'v1.'
/** Links past this length start failing in chat apps and some browsers. */
export const MAX_LINK_CHARS = 60_000

async function pipe(bytes: Uint8Array, stream: CompressionStream | DecompressionStream) {
  const out = new Response(bytes as BodyInit).body!.pipeThrough(stream)
  return new Uint8Array(await new Response(out).arrayBuffer())
}

const toB64Url = (bytes: Uint8Array) => {
  let s = ''
  for (let i = 0; i < bytes.length; i += 0x8000) {
    s += String.fromCharCode(...bytes.subarray(i, i + 0x8000))
  }
  return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}

const fromB64Url = (text: string) => {
  const b = atob(text.replace(/-/g, '+').replace(/_/g, '/'))
  return Uint8Array.from(b, ch => ch.charCodeAt(0))
}

export async function encodeShare(s: Shared): Promise<string> {
  const json = JSON.stringify({
    l: s.language, c: s.code, i: s.stdin, n: s.notes, b: s.breakpoints, s: s.step, f: s.fromBreakpoint ? 1 : 0,
  })
  const packed = await pipe(new TextEncoder().encode(json), new CompressionStream('deflate-raw'))
  return PREFIX + toB64Url(packed)
}

/** The shared visualisation in a fragment, or null when it is not one or is damaged. */
export async function decodeShare(fragment: string): Promise<Shared | null> {
  const text = fragment.replace(/^#/, '').replace(/^v=/, '')
  if (!text.startsWith(PREFIX)) return null
  try {
    const bytes = await pipe(fromB64Url(text.slice(PREFIX.length)), new DecompressionStream('deflate-raw'))
    const o = JSON.parse(new TextDecoder().decode(bytes))
    if (!['python', 'cpp', 'c'].includes(o.l) || typeof o.c !== 'string') return null
    const notes: Record<number, string> = {}
    for (const [k, v] of Object.entries(o.n ?? {})) {
      if (Number.isInteger(Number(k)) && typeof v === 'string') notes[Number(k)] = v.slice(0, 2000)
    }
    return {
      language: o.l,
      code: o.c,
      stdin: typeof o.i === 'string' ? o.i : '',
      notes,
      breakpoints: Array.isArray(o.b) ? o.b.filter((x: unknown) => Number.isInteger(x)) : [],
      step: Number.isInteger(o.s) ? o.s : 0,
      fromBreakpoint: o.f === 1,
    }
  } catch {
    return null
  }
}
