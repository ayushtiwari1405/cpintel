import { useState } from 'react'
import { AlertTriangle, ChevronDown, ChevronRight, Loader2, Scissors } from 'lucide-react'
import { clsx } from 'clsx'

import { useTestReport } from '@/hooks/useArchive'
import type { TestOutcome } from '@/types'

const VERDICTS: Record<string, string> = {
  OK: 'Passed',
  WRONG_ANSWER: 'Wrong answer',
  TIME_LIMIT_EXCEEDED: 'Time limit exceeded',
  MEMORY_LIMIT_EXCEEDED: 'Memory limit exceeded',
  RUNTIME_ERROR: 'Runtime error',
  IDLENESS_LIMIT_EXCEEDED: 'Idleness limit exceeded',
  PRESENTATION_ERROR: 'Presentation error',
  CHALLENGED: 'Hacked',
  SKIPPED: 'Skipped',
}

function passed(verdict: string | null): boolean {
  return verdict === 'OK'
}

function kb(bytes: number | null): string {
  if (bytes == null) return ''
  if (bytes < 1024) return `${bytes} B`
  return bytes < 1024 * 1024
    ? `${Math.round(bytes / 1024)} KB`
    : `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

/** One of the three data columns. Scrolls on its own — test data is often long. */
function Payload({ label, value }: { label: string; value: string | null }) {
  return (
    <div className="flex flex-col min-w-0">
      <span className="text-[10px] uppercase tracking-wide text-gray-600 mb-1">{label}</span>
      <pre className="flex-1 text-[11px] font-mono text-gray-300 bg-gray-950/60 rounded-lg
                      border border-gray-850 p-2 overflow-auto max-h-56 whitespace-pre">
        {value ?? <span className="text-gray-700 not-italic">—</span>}
      </pre>
    </div>
  )
}

function TestRow({ test, defaultOpen }: { test: TestOutcome; defaultOpen: boolean }) {
  const [open, setOpen] = useState(defaultOpen)
  const ok = passed(test.verdict)
  const hasData = !!(test.input || test.output || test.answer)

  return (
    <div className="border-b border-gray-850 last:border-0">
      <button
        onClick={() => setOpen(v => !v)}
        className="w-full flex items-center gap-2 px-3 py-2 hover:bg-gray-850/50
                   transition-colors text-left"
      >
        {open ? <ChevronDown size={12} className="text-gray-600 flex-shrink-0" />
              : <ChevronRight size={12} className="text-gray-600 flex-shrink-0" />}
        <span className="text-xs font-mono text-gray-400 w-14 flex-shrink-0">
          Test {test.index}
        </span>
        <span className={clsx('text-xs font-medium', ok ? 'text-green-400' : 'text-red-400')}>
          {VERDICTS[test.verdict ?? ''] ?? test.verdict?.replace(/_/g, ' ')}
        </span>
        <span className="ml-auto flex items-center gap-3 text-[10px] text-gray-600
                         tabular-nums flex-shrink-0">
          {test.timeMs != null && <span>{test.timeMs} ms</span>}
          {test.memoryBytes != null && <span>{kb(test.memoryBytes)}</span>}
        </span>
      </button>

      {open && (
        <div className="px-3 pb-3 space-y-2">
          {test.checkerMessage && (
            <p className={clsx('text-[11px] font-mono leading-relaxed',
              ok ? 'text-gray-500' : 'text-amber-300/90')}>
              {test.checkerMessage.trim()}
            </p>
          )}

          {hasData ? (
            <div className="grid grid-cols-3 gap-2">
              <Payload label="Input" value={test.input} />
              <Payload label="Your output" value={test.output} />
              <Payload label="Expected" value={test.answer} />
            </div>
          ) : (
            <p className="text-[11px] text-gray-600">
              Codeforces did not include the data for this test.
            </p>
          )}

          {test.truncated && (
            <p className="flex items-center gap-1.5 text-[10px] text-gray-600">
              <Scissors size={10} className="flex-shrink-0" />
              Codeforces clipped this to the first ~500 characters — it is not the whole test.
            </p>
          )}
          {test.exitCode != null && test.exitCode !== 0 && (
            <p className="text-[10px] text-gray-600">exit code {test.exitCode}</p>
          )}
        </div>
      )}
    </div>
  )
}

interface Props {
  /** Null when the submission was never archived, so there is nothing to look up. */
  archiveId: string | null
  /** Only fetches once the tab is actually opened — the first read can cost a scrape. */
  active: boolean
}

/**
 * What the judge did, test by test.
 *
 * The point of this panel is the case Codeforces makes you leave the app for: a wrong answer
 * on test 7 is not actionable, but the input that produced it is. During a live round the
 * platform gives only the index, and saying so plainly beats rendering an empty table.
 */
export function TestReportView({ archiveId, active }: Props) {
  const report = useTestReport(archiveId, active)

  if (!archiveId) {
    return (
      <p className="p-4 text-xs text-gray-600">
        This submission is not archived, so its results cannot be looked up.
      </p>
    )
  }

  if (report.isLoading) {
    return (
      <div className="flex items-center justify-center gap-2 p-6 text-xs text-gray-600">
        <Loader2 size={13} className="animate-spin" /> Reading the judge's results…
      </div>
    )
  }

  if (report.isError) {
    return (
      <p className="p-4 text-xs text-red-300">
        {(report.error as any)?.response?.data?.message
          ?? 'Could not read the results for this submission.'}
      </p>
    )
  }

  const data = report.data
  if (!data) return null

  return (
    <div className="flex flex-col min-h-0 overflow-y-auto">
      {(data.failedOnTest != null || data.testCount != null) && (
        <div className="flex items-center gap-2 px-3 py-2 border-b border-gray-800
                        text-[11px] text-gray-500">
          {data.failedOnTest != null ? (
            <span className="text-red-400 font-medium">
              Failed on test {data.failedOnTest}
            </span>
          ) : (
            <span className="text-green-400 font-medium">All tests passed</span>
          )}
          {data.testCount != null && data.testCount > 0 && (
            <span>· {data.testCount} test{data.testCount === 1 ? '' : 's'} shown</span>
          )}
        </div>
      )}

      {data.compilationError && (
        <div className="p-3 space-y-1.5">
          <span className="text-[10px] uppercase tracking-wide text-gray-600">
            Compilation error
          </span>
          <pre className="text-[11px] font-mono text-red-300/90 bg-gray-950/60 rounded-lg
                          border border-gray-850 p-2 overflow-auto max-h-72 whitespace-pre-wrap">
            {data.compilationError}
          </pre>
        </div>
      )}

      {data.notice && (
        <p className="flex items-start gap-2 px-3 py-3 text-[11px] text-gray-500
                      leading-relaxed">
          <AlertTriangle size={12} className="flex-shrink-0 mt-0.5 text-gray-600" />
          {data.notice}
        </p>
      )}

      {data.tests.map(t => (
        <TestRow
          key={t.index ?? Math.random()}
          test={t}
          // The failing test is the one being looked for; open it without a click.
          defaultOpen={!passed(t.verdict)}
        />
      ))}
    </div>
  )
}
