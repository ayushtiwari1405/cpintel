import { useMemo, useState } from 'react'
import { ArrowLeft, ClipboardCheck, ChevronRight } from 'lucide-react'
import { format } from 'date-fns'
import { clsx } from 'clsx'

import { evaluationApi } from '@/api/evaluationApi'
import { EvaluationSheet } from '@/components/evaluation/EvaluationSheet'
import { useMyEvaluationExams } from '@/hooks/useEvaluation'
import type { EvaluationState, TaExam } from '@/types'
import { PageHeader } from '@/components/common/PageHeader'
import { EmptyState, ErrorState, LoadingState } from '@/components/common/States'

const STATE: Record<EvaluationState, { label: string; tone: string }> = {
  NOT_ENDED: { label: 'Not over yet',     tone: 'badge-gray' },
  OPEN:      { label: 'Open for marking', tone: 'badge-blue' },
  DONE:      { label: 'Final',            tone: 'badge-green' },
}

/**
 * A teaching assistant's marking: the examinations they were given work on, and the sheet for
 * one of them. Marking opens when the paper ends and closes when an admin marks it done.
 */
export default function EvaluatePage() {
  const { data: exams, isLoading, isError, error, refetch } = useMyEvaluationExams()
  const [openId, setOpenId] = useState<number | null>(null)
  const open = exams?.find(e => e.eventId === openId) ?? null
  const openEventId = open?.eventId ?? null
  const source = useMemo(
    () => openEventId != null ? evaluationApi.source(openEventId) : null, [openEventId])

  const header = <PageHeader title="Evaluate"
    description="Marking opens when a paper ends and closes when an admin marks it done." />

  if (isLoading) return <>{header}<LoadingState label="Loading your marking…" /></>
  if (isError) return <>{header}<ErrorState title="Could not load your marking" error={error} onRetry={() => refetch()} /></>

  if (open && source) {
    return (
      <div className="space-y-4">
        <button type="button" onClick={() => setOpenId(null)}
          className="inline-flex items-center gap-1 rounded-md px-1.5 py-1 text-xs text-gray-400
                     hover:bg-gray-850 hover:text-gray-200">
          <ArrowLeft size={12} aria-hidden="true" /> All examinations
        </button>
        <PageHeader
          eyebrow="Marking"
          title={open.name}
          description={<>
            {open.classroomName}
            {open.endsAt && <> · ended {format(new Date(open.endsAt), 'd MMM yyyy, HH:mm')}</>}
          </>}
          className="mb-4!"
        />
        <EvaluationSheet source={source} />
      </div>
    )
  }

  if (!exams || exams.length === 0) {
    return (
      <>
        {header}
        <div className="rounded-xl border border-gray-800 bg-gray-900">
          <EmptyState icon={<ClipboardCheck size={18} />} title="Nothing to mark">
            Examinations appear here when an admin assigns you marking.
          </EmptyState>
        </div>
      </>
    )
  }

  return (
    <>
      {header}
      <ul className="divide-y divide-gray-800 overflow-hidden rounded-xl border border-gray-800 bg-gray-900">
        {exams.map(exam => <li key={exam.eventId}><ExamRow exam={exam}
          onOpen={() => setOpenId(exam.eventId)} /></li>)}
      </ul>
    </>
  )
}

function ExamRow({ exam, onOpen }: { exam: TaExam; onOpen: () => void }) {
  const state = STATE[exam.state]
  return (
    <button type="button" onClick={onOpen}
      className="group flex w-full flex-wrap items-center justify-between gap-3 px-4 py-3 text-left
                 transition-colors hover:bg-gray-850/60">
      <div className="min-w-0">
        <div className="flex flex-wrap items-center gap-2">
          <span className="text-sm font-medium text-gray-100">{exam.name}</span>
          <span className={state.tone}>{state.label}</span>
          {exam.frozenAt && exam.state === 'OPEN' && (
            <span className="badge-gray">Frozen</span>
          )}
        </div>
        <p className="mt-0.5 text-xs text-gray-500">
          {exam.classroomName}
          {exam.endsAt && <> · ends {format(new Date(exam.endsAt), 'd MMM yyyy, HH:mm')}</>}
        </p>
      </div>
      <span className="flex items-center gap-3">
        <span className={clsx('font-mono text-xs tabular-nums',
          exam.cells > 0 && exam.marked >= exam.cells ? 'text-green-300' : 'text-gray-400')}>
          {exam.marked}/{exam.cells} marked
        </span>
        <ChevronRight size={15} className="text-gray-600 group-hover:text-gray-300" aria-hidden="true" />
      </span>
    </button>
  )
}
