import { useMemo, useState } from 'react'
import { clsx } from 'clsx'
import { Loader2 } from 'lucide-react'

import { StatementView } from '@/components/practice/StatementView'
import { useContest, useContestStatement, useStatementPdf } from '@/hooks/useCompete'
import { parseSamples } from '@/utils/parseSamples'
import type { CompetePlatform, ContestRef, MyExam } from '@/types'

/**
 * The questions of a paper that is over, with their samples.
 *
 * <p>Read from the judge through the ordinary compete routes, which an ordinary session may
 * reach again once the paper has ended. The problem list is the judge's too, rather than the
 * exam's own, because an admin may have set a paper without listing its problems here.
 */
export function PastExamProblems({ exam }: { exam: MyExam }) {
  const ref = useMemo<ContestRef>(() => ({
    platform: exam.event.platform as CompetePlatform,
    id: exam.event.externalId,
  }), [exam.event.platform, exam.event.externalId])

  const { data: contest, isLoading: contestLoading } = useContest(ref)
  const [picked, setPicked] = useState<string | null>(null)
  const selected = picked ?? contest?.problems[0]?.index ?? null

  const { data: statement, isLoading: statementLoading } =
    useContestStatement(ref, selected ?? undefined, !!selected)
  const { url: pdfUrl, text: pdfText, error: pdfError } =
    useStatementPdf(ref, selected ?? undefined, !!selected && !!statement?.statementPdfUrl)
  // As in the live workspace: a text statement's own examples stand in for sample files the
  // judge does not hand a team account.
  const samples = useMemo(
    () => (statement?.samples?.length ? statement.samples : parseSamples(pdfText)),
    [statement?.samples, pdfText])

  if (contestLoading) {
    return (
      <div className="flex items-center justify-center gap-2 py-10 text-sm text-gray-500">
        <Loader2 size={14} className="animate-spin" /> Loading the questions…
      </div>
    )
  }

  if (!contest || contest.problems.length === 0) {
    return (
      <p className="rounded-xl border border-gray-800 bg-gray-900/60 px-5 py-6 text-center
        text-sm text-gray-500">
        The judge is no longer serving this paper's questions.
      </p>
    )
  }

  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-wrap gap-1.5">
        {contest.problems.map(problem => (
          <button
            key={problem.index}
            onClick={() => setPicked(problem.index)}
            className={clsx(
              'rounded-lg border px-3 py-1.5 text-xs transition-colors',
              selected === problem.index
                ? 'border-indigo-700 bg-indigo-950/40 text-indigo-200'
                : 'border-gray-800 bg-gray-900 text-gray-400 hover:bg-gray-800/60'
            )}
          >
            <span className="font-medium">{problem.index}</span>
            {problem.name && <span className="ml-1.5">{problem.name}</span>}
          </button>
        ))}
      </div>

      <div className="h-[70vh] rounded-xl border border-gray-800 bg-gray-950 px-4 py-3">
        <StatementView
          problem={statement}
          isLoading={statementLoading}
          pdfUrl={pdfUrl}
          pdfText={pdfText}
          pdfError={pdfError}
          samples={samples}
        />
      </div>
    </div>
  )
}
