/**
 * Whether a Codeforces window CPIntel itself opened is up: the sign-in popup, or the submit page
 * for Codeforces' anti-bot check. Focus going to it is not leaving the examination, so the away
 * tracking leaves it out. Those windows hold to Codeforces' sign-in, check and submit pages and
 * close themselves, so this cannot be used to go anywhere else.
 */
let open = 0
const listeners = new Set<() => void>()

export function cfWindowOpen(): boolean {
  return open > 0
}

export function onCfWindowChange(listener: () => void): () => void {
  listeners.add(listener)
  return () => { listeners.delete(listener) }
}

/** Runs `work` — which opens such a window — with focus leaving for it excused. */
export async function whileCfWindowOpen<T>(work: () => Promise<T>): Promise<T> {
  open++
  listeners.forEach(l => l())
  try {
    return await work()
  } finally {
    open--
    listeners.forEach(l => l())
  }
}
