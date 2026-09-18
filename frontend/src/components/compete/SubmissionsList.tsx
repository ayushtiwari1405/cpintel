import { clsx } from 'clsx'
import { ExternalLink, Loader2 } from 'lucide-react'
import type { ContestSubmission } from '@/types'

const SHORT_VERDICTS: Record<string, string> = {
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
}

function timeOf(iso: string | null): string {
  if (!iso) return ''
  return new Date(iso).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
}

interface Props {
  submissions: ContestSubmission[]
  isLoading?: boolean
}

/**
 * Everything sent to the judge this contest, newest first.
 *
 * Lives as a tab in the workspace's left pane, so it fills the pane rather than carrying a
 * card and a heading of its own — the tab it sits behind already says what this is.
 */
export function SubmissionsList({ submissions, isLoading }: Props) {
  if (submissions.length === 0) {
    return (
      <div className="flex h-full flex-col items-center justify-center gap-2 px-6 text-center">
        {isLoading ? (
          <Loader2 size={16} className="animate-spin text-gray-600" />
        ) : (
          <p className="max-w-xs text-xs leading-relaxed text-gray-600">
            Nothing submitted yet. Anything you send from here goes to Codeforces as a real
            contest submission.
          </p>
        )}
      </div>
    )
  }

  return (
    <div className="h-full overflow-y-auto">
      <table className="w-full text-xs">
        <thead className="sticky top-0 bg-gray-950">
          <tr className="text-left text-[11px] text-gray-600">
            <th className="px-3 py-2 font-medium">#</th>
            <th className="px-2 py-2 font-medium">Time</th>
            <th className="px-2 py-2 font-medium">Verdict</th>
            <th className="px-2 py-2 text-right font-medium">Took</th>
            <th className="w-8 px-2 py-2" />
          </tr>
        </thead>
        <tbody className="divide-y divide-gray-800/70">
          {submissions.map(s => {
            const accepted = s.verdict === 'OK'
            const pending = !s.finished
            return (
              <tr key={s.id} className="transition-colors hover:bg-gray-900/60">
                <td className="px-3 py-2 font-mono text-gray-300">{s.index ?? '?'}</td>
                <td className="px-2 py-2 tabular-nums text-gray-600">{timeOf(s.createdAt)}</td>
                <td className={clsx(
                  'px-2 py-2 font-medium',
                  pending ? 'text-gray-400' : accepted ? 'text-green-400' : 'text-red-400'
                )}>
                  <span className="flex items-center gap-1.5">
                    {pending && <Loader2 size={10} className="animate-spin flex-shrink-0" />}
                    {SHORT_VERDICTS[s.verdict] ?? s.verdict.replace(/_/g, ' ')}
                    {!accepted && !pending && s.passedTestCount != null && (
                      <span className="font-normal text-gray-600">
                        on {s.passedTestCount + 1}
                      </span>
                    )}
                  </span>
                </td>
                <td className="px-2 py-2 text-right tabular-nums text-gray-600">
                  {s.timeConsumedMillis != null && s.finished ? `${s.timeConsumedMillis} ms` : ''}
                </td>
                <td className="px-2 py-2">
                  <a
                    href={s.url}
                    target="_blank"
                    rel="noreferrer"
                    title="Open on Codeforces"
                    className="text-gray-700 transition-colors hover:text-gray-400"
                  >
                    <ExternalLink size={11} />
                  </a>
                </td>
              </tr>
            )
          })}
        </tbody>
      </table>
    </div>
  )
}
