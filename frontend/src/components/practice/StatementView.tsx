import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { clsx } from 'clsx'
import {
  AlertTriangle, BookOpen, Check, Clock, Copy, Cpu, ExternalLink, FileText, Loader2, Star, Tag,
} from 'lucide-react'
import { openExternal } from '@/utils/desktopBridge'
import { cfRelay, openCodeforces } from '@/api/cfBrowser'
import type { ProblemDetail, ProblemSample } from '@/types'

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
      case 'BROWSER_CHECK':
        return (
          <p>
            Codeforces wants to check this browser before showing the problem.{' '}
            <button onClick={openCodeforces} className="underline hover:text-amber-200">
              Open codeforces.com
            </button>
            , let the check finish, then come back and reopen the problem.
          </p>
        )
      case 'SESSION_STALE':
      case 'NO_SESSION':
        // Without a browser route a hosted server cannot read Codeforces at all, whatever
        // session it holds; the extension is the fix, not reconnecting.
        if (!cfRelay()) {
          return (
            <p>
              Codeforces only shows its pages to a real browser. Install the CPIntel extension
              from{' '}
              <Link to="/platforms" className="underline hover:text-amber-200">Platforms</Link>
              {' '}to read problems here — or open this one on Codeforces meanwhile. (Running
              CPIntel on this computer? Reconnecting your Codeforces session there works too.)
            </p>
          )
        }
        return (
          <p>
            Codeforces did not return this problem to your browser. Open it on Codeforces, or
            try again in a moment.
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
  /**
   * The statement's text: what the judge served when it published plain text, or what was read
   * out of the PDF. Shown only when there is no PDF to embed.
   */
  pdfText?: string | null
  pdfError?: string | null
  /**
   * The examples to show, when the caller has better ones than the problem carries — the ones
   * parsed out of a PDF's text, which the judge's own sample list does not include.
   */
  samples?: ProblemSample[]
}

/** Codeforces' own rating colours, so a number reads the way it does on the site. */
function ratingTone(rating: number): string {
  if (rating < 1200) return 'text-gray-400 border-gray-700'
  if (rating < 1400) return 'text-green-400 border-green-900'
  if (rating < 1600) return 'text-cyan-400 border-cyan-900'
  if (rating < 1900) return 'text-blue-400 border-blue-900'
  if (rating < 2100) return 'text-violet-400 border-violet-900'
  if (rating < 2400) return 'text-orange-400 border-orange-900'
  return 'text-red-400 border-red-900'
}

/** Tags give the approach away, so they stay folded until asked for — and stay as left. */
const TAGS_KEY = 'cpintel.statement.showTags'

function readShowTags(): boolean {
  try { return localStorage.getItem(TAGS_KEY) === '1' } catch { return false }
}

export function StatementView({
  problem, isLoading, pdfUrl, pdfText, pdfError, samples: samplesOverride,
}: Props) {
  const [showTags, setShowTags] = useState(readShowTags)

  if (isLoading) {
    return (
      <div className="flex items-center justify-center h-full text-gray-600">
        <Loader2 className="animate-spin" size={22} />
      </div>
    )
  }

  if (!problem) {
    return (
      <div className="flex flex-col items-center justify-center gap-2 h-full text-center">
        <BookOpen size={22} className="text-gray-700" />
        <p className="text-sm text-gray-500">Pick a problem to get started.</p>
      </div>
    )
  }

  const samples = samplesOverride?.length ? samplesOverride : problem.samples
  const isDocument = !!problem.statementPdfUrl
  const io = problem.inputFile && problem.outputFile
    && !/standard/i.test(problem.inputFile + problem.outputFile)
    ? `${problem.inputFile} → ${problem.outputFile}` : null

  const toggleTags = () => {
    const next = !showTags
    setShowTags(next)
    try { localStorage.setItem(TAGS_KEY, next ? '1' : '0') } catch { /* preference only */ }
  }

  return (
    <div className="h-full overflow-y-auto pr-1">
      <article className="mx-auto max-w-3xl">
        {/* Header */}
        <header className="pb-5 mb-5 border-b border-gray-800">
          <div className="flex items-start gap-3">
            <div className="flex h-11 w-11 flex-shrink-0 items-center justify-center rounded-xl
                            border border-indigo-900 bg-indigo-600/10 font-mono text-base
                            font-semibold text-indigo-300">
              {problem.index}
            </div>

            <div className="min-w-0 flex-1">
              <p className="text-[11px] font-mono uppercase tracking-wider text-gray-600">
                {isDocument ? `Problem ${problem.index}` : `Contest ${problem.contestId}`}
              </p>
              <h2 className="mt-0.5 text-xl font-semibold leading-snug text-gray-50">
                {problem.name}
              </h2>
            </div>

            <button
              // Marked as allowed during a contest: reading the problem on the judge's own site
              // is something a contestant is entitled to do, so the lock lets this one through.
              onClick={() => openExternal(problem.url, true)}
              data-lockdown-allow
              className="flex-shrink-0 flex items-center gap-1.5 rounded-lg border border-gray-800
                         px-2.5 py-1.5 text-xs text-gray-500 transition-colors
                         hover:border-indigo-900 hover:text-indigo-400"
            >
              <ExternalLink size={12} /> {isDocument ? 'Judge' : 'Codeforces'}
            </button>
          </div>

          <div className="mt-4 flex flex-wrap items-center gap-2 text-xs">
            {problem.timeLimit && (
              <Meta icon={<Clock size={12} />} label="Time" value={problem.timeLimit} />
            )}
            {problem.memoryLimit && (
              <Meta icon={<Cpu size={12} />} label="Memory" value={problem.memoryLimit} />
            )}
            {io && <Meta icon={<FileText size={12} />} label="Files" value={io} />}
            {problem.rating && (
              <span className={clsx('flex items-center gap-1 rounded-full border px-2.5 py-1',
                'font-medium tabular-nums', ratingTone(problem.rating))}>
                <Star size={11} /> {problem.rating}
              </span>
            )}
            {problem.tags.length > 0 && (
              <button
                onClick={toggleTags}
                className="flex items-center gap-1 rounded-full border border-gray-800 px-2.5
                           py-1 text-gray-500 transition-colors hover:text-gray-300"
              >
                <Tag size={11} />
                {showTags ? 'Hide tags' : `Show ${problem.tags.length} tag${
                  problem.tags.length === 1 ? '' : 's'}`}
              </button>
            )}
          </div>

          {showTags && problem.tags.length > 0 && (
            <div className="flex flex-wrap gap-1.5 mt-3">
              {problem.tags.map(t => (
                <span key={t} className="rounded-full bg-gray-800/70 px-2.5 py-0.5 text-[11px]
                                         text-gray-400">
                  {t}
                </span>
              ))}
            </div>
          )}
        </header>

        {isDocument ? (
          <PdfStatement url={pdfUrl ?? null} text={pdfText ?? null} error={pdfError ?? null} />
        ) : !problem.statementAvailable && (
          <MissingStatement issue={problem.statementIssue} />
        )}

        {!isDocument && (
          <>
            <Section html={problem.legendHtml} />
            <Section title="Input" html={problem.inputSpecHtml} />
            <Section title="Output" html={problem.outputSpecHtml} />
          </>
        )}

        {samples.length > 0 && (
          <section className="mt-7">
            <SectionTitle>{samples.length > 1 ? 'Examples' : 'Example'}</SectionTitle>
            <div className="space-y-3">
              {samples.map((s, i) => (
                <div key={i} className="overflow-hidden rounded-xl border border-gray-800
                                        bg-gray-900/40">
                  {samples.length > 1 && (
                    <div className="border-b border-gray-800 px-3 py-1.5 text-[11px]
                                    font-medium text-gray-500">
                      Example {i + 1}
                    </div>
                  )}
                  <div className="grid md:grid-cols-2 md:divide-x divide-y md:divide-y-0
                                  divide-gray-800">
                    <SampleBlock label="Input" value={s.input} />
                    <SampleBlock label="Output" value={s.output} />
                  </div>
                </div>
              ))}
            </div>
          </section>
        )}

        {!isDocument && problem.noteHtml && (
          <div className="mt-7 rounded-xl border border-gray-800 border-l-2 border-l-indigo-500/60
                          bg-gray-900/40 px-4 py-3">
            <Section title="Note" html={problem.noteHtml} flush />
          </div>
        )}
        <div className="h-8" />
      </article>
    </div>
  )
}

function Meta({ icon, label, value }: { icon: React.ReactNode; label: string; value: string }) {
  return (
    <span className="flex items-center gap-1.5 rounded-full border border-gray-800 bg-gray-900/60
                     px-2.5 py-1 text-gray-400">
      <span className="text-gray-600">{icon}</span>
      <span className="text-gray-600">{label}</span>
      <span className="font-medium text-gray-300">{value}</span>
    </span>
  )
}

function SectionTitle({ children }: { children: React.ReactNode }) {
  return (
    <h3 className="mb-2.5 flex items-center gap-2 text-[11px] font-semibold uppercase
                   tracking-wider text-gray-500">
      <span className="h-3 w-0.5 rounded-full bg-indigo-500/70" />
      {children}
    </h3>
  )
}

/**
 * A statement the judge published as a document.
 *
 * <p>Two shapes, because DOMjudge has two. A PDF is embedded; a plain-text statement is
 * rendered as preformatted text. Handing the text one to the embed — which is what happened
 * until the types were carried through — produces an empty white rectangle that looks exactly
 * like a PDF that failed to load, and sends people to debug the wrong thing. The PDF wins when
 * both are present: its text is fetched only so the examples can be read out of it.
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

  if (url) {
    return (
      <iframe
        // The thumbnail rail and a fit-to-page zoom leave the page itself a postage stamp in
        // a half-width pane; fit to width with the rail closed is what reads.
        src={`${url}#navpanes=0&view=FitH`}
        title="Problem statement"
        className="w-full h-[75vh] rounded-xl border border-gray-800 bg-white"
      />
    )
  }

  if (text !== null) {
    return (
      <pre className="max-h-[70vh] overflow-auto whitespace-pre-wrap break-words
                      rounded-xl border border-gray-800 bg-gray-900/40 p-4 text-[13px]
                      leading-relaxed text-gray-200">
        {text}
      </pre>
    )
  }

  return (
    <div className="flex items-center justify-center gap-2 h-64 text-gray-600">
      <Loader2 className="animate-spin" size={18} />
      <span className="text-xs">Fetching the statement…</span>
    </div>
  )
}

function Section({ title, html, flush }: { title?: string; html?: string; flush?: boolean }) {
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
    <section className={flush ? '' : 'mt-6 first:mt-0'}>
      {title && <SectionTitle>{title}</SectionTitle>}
      {/* Sanitised server-side with a jsoup safelist before it ever reaches the client. */}
      <div ref={ref} className="cf-statement text-[14.5px] text-gray-300 leading-7" />
    </section>
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
    <div className="min-w-0">
      <div className="flex items-center justify-between px-3 pt-2">
        <span className="text-[11px] font-medium uppercase tracking-wider text-gray-600">
          {label}
        </span>
        <button onClick={copy} data-clipboard-allow title={`Copy ${label.toLowerCase()}`}
          className="flex items-center gap-1 rounded-md px-1.5 py-0.5 text-[11px] text-gray-500
                     transition-colors hover:bg-gray-800 hover:text-gray-200">
          {copied ? <Check size={11} className="text-green-400" /> : <Copy size={11} />}
          {copied ? 'Copied' : 'Copy'}
        </button>
      </div>
      <pre className="px-3 pb-3 pt-1.5 text-[13px] font-mono leading-relaxed text-gray-200
                      overflow-x-auto whitespace-pre">{value}</pre>
    </div>
  )
}
