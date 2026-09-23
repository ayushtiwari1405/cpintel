import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { ExternalLink, Copy, Check, Clock, Cpu, Loader2, AlertTriangle } from 'lucide-react'
import { openExternal } from '@/utils/desktopBridge'
import type { ProblemDetail } from '@/types'

declare global {
  interface Window {
    MathJax?: {
      typesetPromise?: (els?: HTMLElement[]) => Promise<void>
      typesetClear?: (els?: HTMLElement[]) => void
      startup?: { promise?: Promise<void> }
    }
  }
}

/**
 * Why there is no statement, said precisely enough to act on.
 *
 * <p>This used to be one sentence for every cause — "gym-only, or the site is rate-limiting
 * us" — and that sentence was usually wrong. Codeforces serves its pages only to a client that
 * has passed its browser check, and the cookie recording that the user's own browser passed it
 * expires within hours, while the CPIntel session carrying it lasts a fortnight. So the everyday
 * failure is a connected-looking session that no longer clears the check, and it is a minute's
 * work to fix once you know. A dead end and a next step read the same until the text says which
 * one this is.
 */
function MissingStatement({ issue }: { issue?: string | null }) {
  const body = (() => {
    switch (issue) {
      case 'SESSION_STALE':
        return (
          <p>
            Codeforces turned this away at its browser check. Your connected session passed
            that check when you linked it, and that clearance has since expired — it lives
            hours, not weeks.{' '}
            <Link to="/platforms" className="underline hover:text-amber-200">
              Reconnect your Codeforces session
            </Link>{' '}
            and statements load again. Submitting needs the same fresh session.
          </p>
        )
      case 'NO_SESSION':
        return (
          <p>
            Codeforces shows its pages only to a browser that has passed its check, so
            statements cannot be read without a session.{' '}
            <Link to="/platforms" className="underline hover:text-amber-200">
              Connect your Codeforces session
            </Link>{' '}
            to read them here. You can still open the problem on Codeforces meanwhile.
          </p>
        )
      case 'NOT_FOUND':
        return (
          <p>
            Codeforces has no problemset page for this one — gym and private-contest problems
            are not published there. Open it on Codeforces to read it; you can still write and
            submit from here.
          </p>
        )
      default:
        return (
          <p>
            Codeforces did not return a readable statement for this problem. Open it on
            Codeforces to read it; you can still write and submit from here.
          </p>
        )
    }
  })()

  return (
    <div className="flex gap-2 p-3 rounded-lg bg-amber-950/40 border border-amber-900
                    text-amber-300 text-xs mb-4">
      <AlertTriangle size={14} className="flex-shrink-0 mt-0.5" />
      {body}
    </div>
  )
}

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
function typesetWhenReady(el: HTMLElement, isCancelled: () => boolean) {
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

interface Props {
  problem?: ProblemDetail
  isLoading?: boolean
  /**
   * An object URL for a PDF statement, where the judge publishes one instead of HTML.
   *
   * DOMjudge ships the problem package's PDF, so there is nothing to parse into sections —
   * the document is shown as the judge built it, above the samples the package carries. Null
   * while it is still being fetched.
   */
  pdfUrl?: string | null
  /** A statement the judge served as plain text rather than as a PDF. */
  pdfText?: string | null
  pdfError?: string | null
}

export function StatementView({ problem, isLoading, pdfUrl, pdfText, pdfError }: Props) {
  if (isLoading) {
    return (
      <div className="flex items-center justify-center h-full text-gray-600">
        <Loader2 className="animate-spin" size={22} />
      </div>
    )
  }

  if (!problem) {
    return (
      <div className="flex items-center justify-center h-full">
        <p className="text-sm text-gray-500">Pick a problem to get started.</p>
      </div>
    )
  }

  return (
    <div className="h-full overflow-y-auto pr-1">
      {/* Header */}
      <div className="pb-4 mb-4 border-b border-gray-800">
        <div className="flex items-start justify-between gap-3">
          <div className="min-w-0">
            <p className="text-xs font-mono text-gray-500">
              {problem.contestId}{problem.index}
            </p>
            <h2 className="text-lg font-semibold text-white mt-0.5">{problem.name}</h2>
          </div>
          <button
            // Marked as allowed during a contest: reading the problem on the judge's own site
            // is something a contestant is entitled to do, so the lock lets this one through.
            onClick={() => openExternal(problem.url, true)}
            data-lockdown-allow
            className="flex-shrink-0 flex items-center gap-1.5 text-xs text-gray-500
                       hover:text-indigo-400 transition-colors"
          >
            <ExternalLink size={13} /> {problem.statementPdfUrl ? 'Judge' : 'Codeforces'}
          </button>
        </div>

        <div className="flex flex-wrap items-center gap-x-4 gap-y-1 mt-2 text-xs text-gray-500">
          {problem.timeLimit && (
            <span className="flex items-center gap-1">
              <Clock size={11} /> {problem.timeLimit}
            </span>
          )}
          {problem.memoryLimit && (
            <span className="flex items-center gap-1">
              <Cpu size={11} /> {problem.memoryLimit}
            </span>
          )}
          {problem.rating && <span className="badge-gray">{problem.rating}</span>}
        </div>

        {problem.tags.length > 0 && (
          <div className="flex flex-wrap gap-1 mt-2">
            {problem.tags.map(t => (
              <span key={t} className="badge-gray text-[11px]">{t}</span>
            ))}
          </div>
        )}
      </div>

      {problem.statementPdfUrl ? (
        <PdfStatement url={pdfUrl ?? null} text={pdfText ?? null} error={pdfError ?? null} />
      ) : !problem.statementAvailable && (
        <MissingStatement issue={problem.statementIssue} />
      )}

      {!problem.statementPdfUrl && (
        <>
          <Section html={problem.legendHtml} />
          <Section title="Input" html={problem.inputSpecHtml} />
          <Section title="Output" html={problem.outputSpecHtml} />
        </>
      )}

      {problem.samples.length > 0 && (
        <div className="mt-5">
          <h3 className="text-sm font-semibold text-gray-200 mb-2">Examples</h3>
          <div className="space-y-3">
            {problem.samples.map((s, i) => (
              <div key={i} className="grid md:grid-cols-2 gap-3">
                <SampleBlock label={`Input ${problem.samples.length > 1 ? i + 1 : ''}`}
                  value={s.input} />
                <SampleBlock label={`Output ${problem.samples.length > 1 ? i + 1 : ''}`}
                  value={s.output} />
              </div>
            ))}
          </div>
        </div>
      )}

      {!problem.statementPdfUrl && <Section title="Note" html={problem.noteHtml} />}
      <div className="h-8" />
    </div>
  )
}

/**
 * A statement the judge published as a document.
 *
 * <p>Two shapes, because DOMjudge has two. A PDF is embedded; a plain-text statement is
 * rendered as preformatted text. Handing the text one to the embed — which is what happened
 * until the types were carried through — produces an empty white rectangle that looks exactly
 * like a PDF that failed to load, and sends people to debug the wrong thing.
 *
 * <p>The embed gets a generous fixed height rather than the pane's full height: the samples
 * and the problem metadata sit below it and have to stay reachable by scrolling, which they
 * would not be if the document filled the pane exactly. A contestant who wants it full-size
 * opens it on the judge with the link in the header.
 */
function PdfStatement({ url, text, error }: {
  url: string | null
  text: string | null
  error: string | null
}) {
  if (error) {
    return (
      <div className="flex gap-2 p-3 rounded-lg bg-amber-950/40 border border-amber-900
                      text-amber-300 text-xs mb-4">
        <AlertTriangle size={14} className="flex-shrink-0 mt-0.5" />
        <p>{error} You can still write and submit from here.</p>
      </div>
    )
  }

  if (text !== null) {
    return (
      <pre className="mb-4 max-h-[70vh] overflow-auto whitespace-pre-wrap break-words
                      rounded-lg border border-gray-800 bg-gray-950 p-4 text-xs
                      leading-relaxed text-gray-200">
        {text}
      </pre>
    )
  }

  if (!url) {
    return (
      <div className="flex items-center justify-center gap-2 h-64 text-gray-600">
        <Loader2 className="animate-spin" size={18} />
        <span className="text-xs">Fetching the statement…</span>
      </div>
    )
  }

  return (
    <iframe
      src={url}
      title="Problem statement"
      className="w-full h-[70vh] rounded-lg border border-gray-800 bg-white mb-4"
    />
  )
}

function Section({ title, html }: { title?: string; html?: string }) {
  const ref = useRef<HTMLDivElement>(null)

  // The statement is written into the DOM here rather than through
  // dangerouslySetInnerHTML, because MathJax replaces the $$$...$$$ text nodes with its own
  // elements and React would undo that. Any re-render of this component re-applies
  // dangerouslySetInnerHTML to the very same node, restoring the raw source and wiping the
  // typeset output — and since the typeset ran once, keyed on the problem, it never came
  // back. That is invisible on Practice, which renders once and sits still, and constant on
  // Compete, where useTicker re-renders the page every second for the countdown.
  //
  // Leaving the div empty in JSX means React has no children for it and will never touch its
  // contents; this effect owns them. If the node is ever remounted the effect re-runs and
  // repopulates it, so both failure modes are covered.
  useEffect(() => {
    const el = ref.current
    if (!el || !html) return
    let cancelled = false
    el.innerHTML = html
    typesetWhenReady(el, () => cancelled)
    return () => { cancelled = true }
  }, [html])

  if (!html) return null

  return (
    <div className="mt-4">
      {title && <h3 className="text-sm font-semibold text-gray-200 mb-1.5">{title}</h3>}
      {/* Sanitised server-side with a jsoup safelist before it ever reaches the client. */}
      <div ref={ref} className="cf-statement text-sm text-gray-300 leading-relaxed" />
    </div>
  )
}

function SampleBlock({ label, value }: { label: string; value: string }) {
  const [copied, setCopied] = useState(false)

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(value)
      setCopied(true)
      setTimeout(() => setCopied(false), 1500)
    } catch {
      /* clipboard can be denied — the text is still selectable */
    }
  }

  return (
    <div className="rounded-lg border border-gray-800 bg-gray-950 overflow-hidden">
      <div className="flex items-center justify-between px-3 py-1.5 bg-gray-900
                      border-b border-gray-800">
        <span className="text-[11px] font-medium text-gray-400">{label.trim()}</span>
        <button onClick={copy} data-clipboard-allow
          className="text-gray-500 hover:text-gray-200 transition-colors">
          {copied ? <Check size={12} className="text-green-400" /> : <Copy size={12} />}
        </button>
      </div>
      <pre className="px-3 py-2 text-xs font-mono text-gray-300 overflow-x-auto
                      whitespace-pre">{value}</pre>
    </div>
  )
}
