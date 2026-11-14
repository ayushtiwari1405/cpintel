/** MathJax, loaded asynchronously by the page; see public/mathjax-config.js. */

/** Serialises every typeset call on the page — MathJax corrupts output if they overlap. */
let typesetQueue: Promise<void> = Promise.resolve()

/**
 * Wait for MathJax, then typeset one element.
 *
 * MathJax is loaded async, so it may not exist yet on first paint. Two things this has to
 * get right, both of which leave raw $$$ on screen when missed:
 *  - Waiting long enough. The bundle is ~1.2 MB; a slow first load can outlast a short
 *    retry window, and once we gave up we never tried again. Wait on MathJax's own startup
 *    promise instead.
 *  - Not overlapping calls. typesetPromise is documented as needing to be serialised;
 *    firing a second one while the first is in flight duplicates the rendered output.
 */
export function typesetWhenReady(el: HTMLElement, isCancelled: () => boolean) {
  let attempts = 0

  const run = () => {
    const mj = window.MathJax
    if (isCancelled() || !mj?.typesetPromise) return
    mj.typesetClear?.([el])
    typesetQueue = typesetQueue
      .then(() => (isCancelled() ? undefined : window.MathJax?.typesetPromise?.([el])))
      .catch(() => {})
  }

  const wait = () => {
    if (isCancelled()) return
    const mj = window.MathJax
    if (mj?.startup?.promise) {
      mj.startup.promise.then(run).catch(() => {})
    } else if (mj?.typesetPromise) {
      run()
    } else if (attempts++ < 120) {
      // 120 x 250ms = 30s, enough for a cold cache on a slow connection.
      setTimeout(wait, 250)
    }
  }

  wait()
}

