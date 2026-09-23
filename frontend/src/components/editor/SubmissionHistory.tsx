import { useEffect, useMemo, useState } from 'react'
import {
  Check, Clipboard, Download, ExternalLink, HardDrive, History, Loader2, Cloud, X,
} from 'lucide-react'
import { clsx } from 'clsx'

import { CodeEditor } from '@/components/editor/CodeEditor'
import { TestReportView } from '@/components/editor/TestReportView'
import { detectLanguage } from '@/components/editor/languages'
import { useAttemptSource, useProblemAttempts, useRecentAttempts } from '@/hooks/useArchive'
import type { ArchiveAttempt, CompetePlatform } from '@/types'

const VERDICTS: Record<string, string> = {
  OK: 'Accepted',
  WRONG_ANSWER: 'Wrong answer',
  TIME_LIMIT_EXCEEDED: 'TLE',
  MEMORY_LIMIT_EXCEEDED: 'MLE',
  RUNTIME_ERROR: 'Runtime error',
  COMPILATION_ERROR: 'Compile error',
  IDLENESS_LIMIT_EXCEEDED: 'Idleness',
  CHALLENGED: 'Hacked',
  SKIPPED: 'Skipped',
  TESTING: 'Judging',
  SUBMITTED: 'Queued',
  SUBMITTING: 'Sending…',
  // Set when Codeforces refused the submission. The code is still here, which is the point.
  NOT_SUBMITTED: 'Never sent',
}

function verdictTone(verdict: string | null): string {
  if (!verdict) return 'text-gray-500'
  if (verdict === 'OK') return 'text-green-400'
  if (verdict === 'TESTING' || verdict === 'SUBMITTED' || verdict === 'SUBMITTING') {
    return 'text-gray-400'
  }
  if (verdict === 'NOT_SUBMITTED') return 'text-amber-400'
  return 'text-red-400'
}

function ago(iso: string | null): string {
  if (!iso) return ''
  const seconds = (Date.now() - new Date(iso).getTime()) / 1000
  if (seconds < 60) return 'just now'
  if (seconds < 3600) return `${Math.floor(seconds / 60)}m ago`
  if (seconds < 86_400) return `${Math.floor(seconds / 3600)}h ago`
  if (seconds < 86_400 * 30) return `${Math.floor(seconds / 86_400)}d ago`
  return new Date(iso).toLocaleDateString()
}

function sizeOf(bytes: number | null): string {
  if (bytes == null) return ''
  return bytes < 1024 ? `${bytes} B` : `${(bytes / 1024).toFixed(1)} KB`
}

type Scope = 'problem' | 'all'

interface Props {
  open: boolean
  onClose: () => void
  platform: CompetePlatform
  contestId?: string
  problemIndex?: string
  /**
   * Drops a source back into the editor. Given the platform language id too, when the archive
   * has one, so a resubmission goes out under the compiler it was written for.
   */
  onLoad: (source: string, languageId: string | null) => void
  /** Used only to warn before overwriting work in progress. */
  currentSource: string
  /**
   * Open straight onto this platform submission rather than an empty pane.
   *
   * The verdict badge uses it: "wrong answer on test 3" is where the user actually is when
   * they want the test, so that badge opens this already pointed at the right attempt.
   */
  focusExternalId?: number | null
  focusTab?: 'code' | 'tests'
}

/**
 * Your own previous submissions, in a window rather than on Codeforces.
 *
 * The whole feature exists because a contest that locks the app down cannot also expect the
 * user to open another tab to read code they already wrote. So this shows both what CPIntel
 * archived and what Codeforces knows about, and never makes the user care which is which
 * beyond the one thing that actually differs: whether opening it needs the network.
 */
export function SubmissionHistory({
  open, onClose, platform, contestId, problemIndex, onLoad, currentSource,
  focusExternalId, focusTab,
}: Props) {
  // Opens on the current problem when there is one, and on the whole archive when there is
  // not — a "this problem" list with no problem behind it is an empty panel that looks like
  // a bug.
  const [scope, setScope] = useState<Scope>(contestId ? 'problem' : 'all')
  const [selected, setSelected] = useState<ArchiveAttempt | null>(null)
  const [copied, setCopied] = useState(false)
  /** Code or the judge's results. Code first — it is why most people open this. */
  const [tab, setTab] = useState<'code' | 'tests'>('code')

  const problemQuery = useProblemAttempts(platform, contestId, problemIndex,
    open && scope === 'problem')
  const recentQuery = useRecentAttempts(open && scope === 'all')
  const source = useAttemptSource(selected)

  const attempts = useMemo<ArchiveAttempt[]>(
    () => (scope === 'problem' ? problemQuery.data?.attempts ?? [] : recentQuery.data ?? []),
    [scope, problemQuery.data, recentQuery.data])

  const loading = scope === 'problem' ? problemQuery.isLoading : recentQuery.isLoading

  // The source query is cached forever, so its verdict is whatever it was when first opened.
  // The list refetches, so the header reads the verdict from there and follows judging.
  const liveVerdict = (selected && attempts.find(a => selected.id
    ? a.id === selected.id
    : a.externalId === selected.externalId)?.verdict)
    ?? source.data?.verdict ?? null

  // Reset the selection whenever the list underneath it changes, so the viewer can never show
  // one problem's code under another problem's heading.
  useEffect(() => { setSelected(null) }, [scope, contestId, problemIndex])
  // A new submission's results belong behind a fresh click, not carried over — unless the
  // caller opened this pointing at a specific tab.
  useEffect(() => {
    if (selected && focusExternalId && selected.externalId === focusExternalId && focusTab) {
      setTab(focusTab)
      return
    }
    setTab('code')
  }, [selected, focusExternalId, focusTab])
  useEffect(() => {
    if (!open) { setSelected(null); return }
    // Reopening after picking a problem should land on that problem, not on wherever the
    // last visit left the toggle.
    setScope(contestId ? 'problem' : 'all')
  }, [open, contestId])

  // Land on the attempt the caller pointed at, once the list it lives in has arrived.
  useEffect(() => {
    if (!open || !focusExternalId || selected) return
    const hit = attempts.find(a => a.externalId === focusExternalId)
    if (hit) {
      setSelected(hit)
      if (focusTab) setTab(focusTab)
    }
  }, [open, focusExternalId, focusTab, attempts, selected])

  // Esc closes, as in every other dialog the user has ever used.
  useEffect(() => {
    if (!open) return
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose() }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [open, onClose])

  if (!open) return null

  const code = source.data?.source ?? ''
  const viewerLanguage = detectLanguage(source.data?.languageLabel).monaco

  const handleLoad = () => {
    if (!source.data) return
    const dirty = currentSource.trim() && currentSource.trim() !== code.trim()
    if (dirty && !window.confirm(
      'Replace what is currently in the editor with this submission?')) return
    onLoad(source.data.source, source.data.languageId)
    onClose()
  }

  const handleCopy = async () => {
    if (!source.data) return
    try {
      await navigator.clipboard.writeText(source.data.source)
      setCopied(true)
      setTimeout(() => setCopied(false), 1500)
    } catch {
      // Clipboard is permission-gated and can simply say no; the code is on screen either way.
    }
  }

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-gray-950/80 p-4"
      onMouseDown={e => { if (e.target === e.currentTarget) onClose() }}
    >
      <div className="w-full max-w-5xl h-[80vh] flex flex-col rounded-xl border
                      border-gray-800 bg-gray-900 shadow-2xl overflow-hidden">

        <div className="flex items-center gap-3 px-4 py-3 border-b border-gray-800">
          <History size={15} className="text-indigo-400" />
          <span className="text-sm font-medium text-gray-200">Your previous code</span>

          <div className="flex rounded-lg border border-gray-800 overflow-hidden ml-2">
            {(['problem', 'all'] as Scope[]).map(s => (
              <button
                key={s}
                onClick={() => setScope(s)}
                disabled={s === 'problem' && !contestId}
                className={clsx(
                  'px-2.5 py-1 text-[11px] transition-colors disabled:opacity-40',
                  scope === s
                    ? 'bg-indigo-600/20 text-indigo-300'
                    : 'text-gray-500 hover:text-gray-300'
                )}
              >
                {s === 'problem'
                  ? `This problem${problemIndex ? ` (${problemIndex})` : ''}`
                  : 'Everything I have written'}
              </button>
            ))}
          </div>

          <button
            onClick={onClose}
            className="ml-auto text-gray-600 hover:text-gray-300 transition-colors"
            title="Close  (Esc)"
          >
            <X size={16} />
          </button>
        </div>

        <div className="flex flex-1 min-h-0">
          {/* ── the list ─────────────────────────────────────────────── */}
          <div className="w-72 flex-shrink-0 border-r border-gray-800 flex flex-col min-h-0">
            {scope === 'problem' && problemQuery.data?.notice && (
              <p className="px-3 py-2 text-[11px] text-gray-600 border-b border-gray-850
                            leading-relaxed">
                {problemQuery.data.notice}
              </p>
            )}

            <div className="flex-1 overflow-y-auto">
              {loading && (
                <div className="flex items-center gap-2 p-3 text-xs text-gray-600">
                  <Loader2 size={12} className="animate-spin" /> Loading…
                </div>
              )}

              {!loading && attempts.length === 0 && (
                <p className="p-3 text-xs text-gray-600 leading-relaxed">
                  {scope === 'problem'
                    ? 'Nothing submitted to this problem yet. Everything you send from here '
                      + 'is kept, so it will be readable without opening Codeforces.'
                    : 'Nothing archived yet. Anything you submit through CPIntel lands here.'}
                </p>
              )}

              {attempts.map(a => {
                const key = a.id ?? `cf-${a.externalId}`
                const active = selected
                  ? (selected.id ?? `cf-${selected.externalId}`) === key
                  : false
                return (
                  <button
                    key={key}
                    onClick={() => setSelected(a)}
                    className={clsx(
                      'w-full text-left px-3 py-2 border-b border-gray-850 transition-colors',
                      active ? 'bg-indigo-600/10' : 'hover:bg-gray-850/60'
                    )}
                  >
                    <div className="flex items-center gap-2">
                      {scope === 'all' && (
                        <span className="font-mono text-[11px] text-gray-400 flex-shrink-0">
                          {a.contestId}{a.problemIndex}
                        </span>
                      )}
                      <span className={clsx('text-xs font-medium truncate',
                        verdictTone(a.verdict))}>
                        {VERDICTS[a.verdict ?? ''] ?? a.verdict?.replace(/_/g, ' ')}
                      </span>
                      <span
                        title={a.stored
                          ? 'Stored in CPIntel — opens instantly, works offline'
                          : 'Only Codeforces has this one; opening it fetches the source'}
                        className="ml-auto flex-shrink-0 text-gray-700"
                      >
                        {a.stored ? <HardDrive size={11} /> : <Cloud size={11} />}
                      </span>
                    </div>

                    <div className="flex items-center gap-2 mt-0.5 text-[10px] text-gray-600">
                      <span title={a.submittedAt ?? ''}>{ago(a.submittedAt)}</span>
                      {a.languageLabel && (
                        <span className="truncate">{a.languageLabel}</span>
                      )}
                      {a.sourceBytes != null && (
                        <span className="ml-auto flex-shrink-0">{sizeOf(a.sourceBytes)}</span>
                      )}
                    </div>

                    {a.sameAsPrevious && (
                      <div className="text-[10px] text-gray-700 mt-0.5">
                        identical to the one below
                      </div>
                    )}
                  </button>
                )
              })}
            </div>
          </div>

          {/* ── the code ─────────────────────────────────────────────── */}
          <div className="flex-1 min-w-0 flex flex-col">
            {!selected && (
              <div className="flex-1 flex items-center justify-center text-xs text-gray-600">
                Pick a submission to read its code.
              </div>
            )}

            {selected && source.isLoading && (
              <div className="flex-1 flex items-center justify-center gap-2 text-xs
                              text-gray-600">
                <Loader2 size={13} className="animate-spin" />
                {selected.stored ? 'Opening…' : 'Fetching from Codeforces…'}
              </div>
            )}

            {selected && source.isError && (
              <div className="flex-1 flex items-center justify-center p-6">
                <p className="text-xs text-red-300 text-center leading-relaxed max-w-sm">
                  {(source.error as any)?.response?.data?.message
                    ?? 'Could not read that submission.'}
                </p>
              </div>
            )}

            {selected && source.data && (
              <>
                <div className="flex items-center gap-2 px-3 py-2 border-b border-gray-800">
                  <span className="text-xs text-gray-300 font-mono">
                    {source.data.contestId}{source.data.problemIndex}
                  </span>
                  {source.data.problemName && (
                    <span className="text-xs text-gray-600 truncate max-w-[14rem]">
                      {source.data.problemName}
                    </span>
                  )}
                  <span className={clsx('text-[11px]', verdictTone(liveVerdict))}>
                    {VERDICTS[liveVerdict ?? ''] ?? liveVerdict}
                  </span>

                  <div className="ml-auto flex items-center gap-1.5">
                    <button
                      onClick={handleCopy}
                      title="Copy to clipboard"
                      className="flex items-center gap-1 text-[11px] px-2 py-1 rounded-lg
                                 border border-gray-700 bg-gray-800 text-gray-300
                                 hover:bg-gray-700 transition-colors"
                    >
                      {copied ? <Check size={11} /> : <Clipboard size={11} />}
                      {copied ? 'Copied' : 'Copy'}
                    </button>
                    <button
                      onClick={handleLoad}
                      title="Replace the editor's contents with this"
                      className="flex items-center gap-1 text-[11px] px-2 py-1 rounded-lg
                                 bg-indigo-600 text-white hover:bg-indigo-500
                                 transition-colors"
                    >
                      <Download size={11} /> Load into editor
                    </button>
                    {selected.url && (
                      <a
                        href={selected.url}
                        target="_blank"
                        rel="noreferrer"
                        title="Open on Codeforces"
                        className="text-gray-700 hover:text-gray-400 transition-colors p-1"
                      >
                        <ExternalLink size={11} />
                      </a>
                    )}
                  </div>
                </div>

                <div className="flex items-center gap-1 px-3 pt-2 border-b border-gray-800">
                  {(['code', 'tests'] as const).map(t => (
                    <button
                      key={t}
                      onClick={() => setTab(t)}
                      className={clsx(
                        'px-3 py-1.5 text-[11px] rounded-t-lg border-b-2 -mb-px transition-colors',
                        tab === t
                          ? 'border-indigo-500 text-indigo-300'
                          : 'border-transparent text-gray-500 hover:text-gray-300'
                      )}
                    >
                      {t === 'code' ? 'Code' : 'Test results'}
                    </button>
                  ))}
                </div>

                <div className="flex-1 min-h-0">
                  {/* Both stay mounted: flipping to the results and back must not throw away
                      the editor's scroll position, nor refetch what was already loaded. */}
                  <div className={clsx('h-full', tab === 'code' ? '' : 'hidden')}>
                    <CodeEditor
                      value={code}
                      onChange={() => {}}
                      language={viewerLanguage}
                      readOnly
                    />
                  </div>
                  <div className={clsx('h-full flex flex-col min-h-0',
                    tab === 'tests' ? '' : 'hidden')}>
                    <TestReportView
                      archiveId={source.data.id}
                      active={tab === 'tests'}
                    />
                  </div>
                </div>

                <div className="px-3 py-1.5 border-t border-gray-800 text-[10px] text-gray-600
                                flex items-center gap-2">
                  {source.data.retrievedFrom === 'ARCHIVE'
                    ? <><HardDrive size={10} /> Read from CPIntel's archive</>
                    : <><Cloud size={10} /> Fetched from Codeforces and archived — the next
                        time you open it, it will be local</>}
                  <span className="ml-auto">{ago(source.data.submittedAt)}</span>
                </div>
              </>
            )}
          </div>
        </div>
      </div>
    </div>
  )
}
