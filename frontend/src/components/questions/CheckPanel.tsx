import { useState } from 'react'
import { formatDistanceToNow } from 'date-fns'
import { CheckCircle2, Loader2, PlayCircle, XCircle } from 'lucide-react'
import { Panel, Pill } from '@/components/admin/AdminUi'
import { useQuestionCheck, useStartCheck } from '@/hooks/useQuestionBank'
import type { CheckVerdict, QuestionDetail } from '@/types'

const VERDICT: Record<CheckVerdict, string> = {
  OK: 'Passed',
  WRONG_ANSWER: 'Wrong answer',
  TIME_LIMIT: 'Too slow',
  CLOSE_TO_LIMIT: 'Close to the limit',
  RUNTIME_ERROR: 'Crashed',
  MEMORY_LIMIT: 'Out of memory',
  OUTPUT_TOO_LARGE: 'Output too large',
  NO_ANSWER: 'No answer',
  NOT_RUN: 'Not run',
}

/**
 * Runs the model solution against every test, the way the judge will, and shows the latest
 * result. A pass is what makes a question ready for an examination.
 */
export function CheckPanel({ question, readOnly, unsaved }: {
  question: QuestionDetail
  readOnly: boolean
  /** The editor has changes not saved yet; a check would test the saved question instead. */
  unsaved: boolean
}) {
  const { data: check } = useQuestionCheck(question.questionId)
  const start = useStartCheck(question.questionId)
  const missing = question.tests.filter(t => t.answerBytes == null).length
  const [generate, setGenerate] = useState(false)
  const active = check?.status === 'QUEUED' || check?.status === 'RUNNING'
  const stale = check != null && !active && check.version !== question.version
  const failing = check?.results.filter(r => r.verdict !== 'OK') ?? []
  const blocked = !question.modelSource ? 'Add a model solution and save it first.'
    : question.tests.length === 0 ? 'Add tests first.'
    : unsaved ? 'Save your changes first: the check runs the saved question.'
    : null

  return (
    <Panel title="Check" description="Optional. Runs the model solution on every test, as the judge will.">
      <div className="space-y-3 p-4">
        <div className="flex flex-wrap items-center gap-3">
          {!readOnly && (
            <button type="button" className="btn-primary btn-sm" disabled={active || start.isPending || blocked != null}
              title={blocked ?? undefined}
              onClick={() => start.mutate(generate && missing > 0)}>
              {active || start.isPending
                ? <Loader2 size={13} className="animate-spin" />
                : <PlayCircle size={13} aria-hidden="true" />}
              {active ? 'Checking…' : 'Check against all tests'}
            </button>
          )}
          {!readOnly && missing > 0 && (
            <label className="flex items-center gap-1.5 text-xs text-gray-300">
              <input type="checkbox" checked={generate} onChange={e => setGenerate(e.target.checked)} disabled={active} />
              Fill in the {missing} missing answer{missing === 1 ? '' : 's'} from the model solution first
            </label>
          )}
          {blocked && !readOnly && <span className="text-xs text-gray-500">{blocked}</span>}
        </div>

        {check == null ? (
          <p className="text-xs text-gray-500">Not checked yet.</p>
        ) : (
          <div className="space-y-2" aria-live="polite">
            <p className="flex flex-wrap items-center gap-2 text-sm">
              {check.status === 'PASSED' && <Pill tone="green"><CheckCircle2 size={11} aria-hidden="true" /> passed</Pill>}
              {check.status === 'FAILED' && <Pill tone="red"><XCircle size={11} aria-hidden="true" /> failed</Pill>}
              {check.status === 'ERROR' && <Pill tone="amber">could not finish</Pill>}
              {active && <Pill tone="accent">{check.status === 'QUEUED' ? 'waiting for its turn' : 'running'}</Pill>}
              <span className="text-xs text-gray-500">
                version {check.version}
                {check.startedBy && <> · started by {check.startedBy}</>}
                {' '}{formatDistanceToNow(new Date(check.finishedAt ?? check.startedAt), { addSuffix: true })}
              </span>
            </p>
            {stale && (
              <p className="text-xs text-amber-300">
                The question has changed since (it is now version {question.version}). Check it again.
              </p>
            )}
            {check.summary && <p className="whitespace-pre-wrap text-sm text-gray-300">{check.summary}</p>}
            {failing.length > 0 && (
              <div className="overflow-x-auto">
                <table className="w-full text-sm">
                  <thead>
                    <tr className="border-b border-gray-800 text-left text-xs text-gray-500">
                      <th className="px-2 py-1.5 font-medium">Test</th>
                      <th className="px-2 py-1.5 font-medium">Result</th>
                      <th className="px-2 py-1.5 font-medium">Time</th>
                      <th className="px-2 py-1.5 font-medium">Details</th>
                    </tr>
                  </thead>
                  <tbody>
                    {failing.slice(0, 50).map(r => (
                      <tr key={r.testId} className="border-b border-gray-800/60 last:border-0">
                        <td className="px-2 py-1.5 text-xs text-gray-300 whitespace-nowrap">
                          {r.sample ? 'Sample' : 'Secret test'} {r.ordinal}
                        </td>
                        <td className="px-2 py-1.5 text-xs whitespace-nowrap">{VERDICT[r.verdict] ?? r.verdict}</td>
                        <td className="px-2 py-1.5 text-xs text-gray-400 tabular-nums whitespace-nowrap">
                          {r.timeMs != null ? `${r.timeMs} ms` : '—'}
                        </td>
                        <td className="px-2 py-1.5 text-xs text-gray-400 break-words">{r.message}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
                {failing.length > 50 && <p className="mt-1 text-xs text-gray-500">and {failing.length - 50} more.</p>}
              </div>
            )}
          </div>
        )}
      </div>
    </Panel>
  )
}
