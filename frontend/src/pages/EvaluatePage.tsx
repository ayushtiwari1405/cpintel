import { useMemo, useState } from 'react'
import { ArrowLeft, ClipboardCheck, Loader2 } from 'lucide-react'
import { format } from 'date-fns'
import { clsx } from 'clsx'

import { evaluationApi } from '@/api/evaluationApi'
import { EvaluationSheet } from '@/components/evaluation/EvaluationSheet'
import { useMyEvaluationExams } from '@/hooks/useEvaluation'
import type { EvaluationState, TaExam } from '@/types'

const STATE: Record<EvaluationState, { label: string; tone: string }> = {
  NOT_ENDED: { label: 'not over yet', tone: 'bg-gray-800 text-gray-400' },
  OPEN:      { label: 'open for marking', tone: 'bg-indigo-900/40 text-indigo-300' },
  DONE:      { label: 'final', tone: 'bg-green-900/40 text-green-400' },
}

/**
 * A teaching assistant's marking: the examinations they were given work on, and the sheet for
 * one of them. Marking opens when the paper ends and closes when an admin marks it done.
 */
export default function EvaluatePage() {
  const { data: exams, isLoading } = useMyEvaluationExams()
  const [openId, setOpenId] = useState<number | null>(null)
  const open = exams?.find(e => e.eventId === openId) ?? null
  const openEventId = open?.eventId ?? null
  const source = useMemo(
    () => openEventId != null ? evaluationApi.source(openEventId) : null, [openEventId])

  if (isLoading) {
    return (
      <div className="flex items-center justify-center gap-2 py-20 text-sm text-gray-500">
        <Loader2 size={16} className="animate-spin" /> Loading…
      </div>
    )
  }

  if (open && source) {
    return (
      <div className="space-y-4">
        <button onClick={() => setOpenId(null)}
          className="inline-flex items-center gap-1 text-xs text-gray-500 hover:text-gray-300">
          <ArrowLeft size={12} /> All examinations
        </button>
        <div>
          <h1 className="text-lg font-semibold text-gray-100">{open.name}</h1>
          <p className="text-xs text-gray-500">
            {open.classroomName}
            {open.endsAt && <> · ended {format(new Date(open.endsAt), 'd MMM yyyy, HH:mm')}</>}
          </p>
        </div>
        <EvaluationSheet source={source} />
      </div>
    )
  }

  if (!exams || exams.length === 0) {
    return (
      <div className="rounded-xl border border-gray-800 bg-gray-900 px-5 py-12 text-center">
        <ClipboardCheck size={22} className="mx-auto text-gray-700" />
        <p className="mt-3 text-sm text-gray-400">Nothing to mark.</p>
        <p className="mt-1 text-xs text-gray-600">
          Examinations appear here once an admin gives you questions or students to mark.
        </p>
      </div>
    )
  }

  return (
    <div className="space-y-2">
      {exams.map(exam => <ExamRow key={exam.eventId} exam={exam}
        onOpen={() => setOpenId(exam.eventId)} />)}
    </div>
  )
}

function ExamRow({ exam, onOpen }: { exam: TaExam; onOpen: () => void }) {
  const state = STATE[exam.state]
  return (
    <button onClick={onOpen}
      className="flex w-full flex-wrap items-center justify-between gap-3 rounded-xl border
                 border-gray-800 bg-gray-900 px-4 py-3 text-left hover:border-gray-700">
      <div className="min-w-0">
        <div className="flex items-center gap-2">
          <span className="text-sm font-medium text-gray-200">{exam.name}</span>
          <span className={clsx('rounded-full px-2 py-0.5 text-[11px] font-medium', state.tone)}>
            {state.label}
          </span>
        </div>
        <p className="mt-0.5 text-xs text-gray-500">
          {exam.classroomName}
          {exam.endsAt && <> · ends {format(new Date(exam.endsAt), 'd MMM yyyy, HH:mm')}</>}
        </p>
      </div>
      <span className="text-xs tabular-nums text-gray-400">
        {exam.marked} of {exam.cells} marked
      </span>
    </button>
  )
}
