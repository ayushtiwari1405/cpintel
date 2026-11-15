import { useState, type ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { clsx } from 'clsx'
import { format } from 'date-fns'
import { Award, CalendarClock, CheckCircle2, CircleDashed, Clock, FileText, ListOrdered } from 'lucide-react'

import { Meta } from '@/components/practice/StatementView'
import { SplitPane } from '@/components/workspace/SplitPane'
import { TabStrip } from '@/components/workspace/TabStrip'
import { useMyLabs } from '@/hooks/useLabs'
import { solvePath } from './solvePath'
import type { LabSolveStatus } from '@/types'

const PLATFORM_NAMES = { CODEFORCES: 'Codeforces', LEETCODE: 'LeetCode', CSES: 'CSES' } as const

interface Props {
  labId: number
  problemId: number
  /** Something the student has to do before Run or Submit work. Hidden when there is nothing. */
  notice?: ReactNode
  /** The statement: header and body, scrolled inside the left pane. */
  statement: ReactNode
  /** The editor and console. */
  editor: ReactNode
  storageKey: string
}

/**
 * A lab problem's workspace, laid out exactly like Practice: the problem on the left with a
 * Description / Lab tab strip, the code on the right, one draggable divider between them, and
 * any connection notice in a strip above both.
 */
export function LabWorkspace({ labId, problemId, notice, statement, editor, storageKey }: Props) {
  const [tab, setTab] = useState<'description' | 'lab'>('description')

  const leftPane = (
    <div className="flex flex-col flex-1 min-w-0 min-h-0 bg-gray-950">
      <TabStrip
        tabs={[
          { id: 'description', label: 'Description', icon: FileText },
          { id: 'lab', label: 'Lab', icon: ListOrdered },
        ]}
        active={tab}
        onChange={id => setTab(id as 'description' | 'lab')}
      />
      <div className="flex-1 min-h-0">
        {tab === 'description'
          ? <div className="h-full overflow-y-auto px-4 py-3">{statement}</div>
          : <LabProblems labId={labId} currentId={problemId} />}
      </div>
    </div>
  )

  return (
    <div className="flex flex-col flex-1 min-h-0">
      {notice && <div className="px-4 pt-3 shrink-0">{notice}</div>}
      <SplitPane
        className={clsx('flex-1', notice && 'mt-3 border-t border-gray-800')}
        storageKey={storageKey}
        initial={45}
        min={25}
        max={70}
        first={leftPane}
        second={editor}
      />
    </div>
  )
}

/** The rest of the lab, so the next problem is a click away — Practice's Problems tab, for a lab. */
function LabProblems({ labId, currentId }: { labId: number; currentId: number }) {
  const { data, isLoading } = useMyLabs()
  const lab = data?.labs.find(l => l.labId === labId)

  if (isLoading) return <p className="p-4 text-xs text-gray-500">Loading the lab…</p>
  if (!lab) return <p className="p-4 text-xs text-gray-500">This lab is no longer open to you.</p>

  return (
    <div className="h-full overflow-y-auto px-3 py-3">
      <div className="mb-3 flex items-baseline justify-between gap-2 px-1">
        <div className="min-w-0">
          <p className="text-[11px] font-mono uppercase tracking-wider text-gray-600">
            {lab.classroomName}
          </p>
          <p className="truncate text-sm font-semibold text-gray-100">{lab.title}</p>
        </div>
        <span className="shrink-0 font-mono text-xs text-gray-400">
          {Number(lab.marks)} / {Number(lab.totalMarks)}
        </span>
      </div>
      <ul className="space-y-1">
        {lab.problems.map(({ problem, status }, i) => {
          const to = solvePath(problem)
          const current = problem.problemId === currentId
          const row = (
            <>
              <StatusIcon status={status} />
              <span className="min-w-0 flex-1">
                <span className="block truncate text-sm text-gray-200">{problem.title}</span>
                <span className="block text-[11px] text-gray-500">
                  {PLATFORM_NAMES[problem.platform]}
                  {problem.difficulty && ` · ${problem.difficulty}`}
                </span>
              </span>
              <span className="shrink-0 font-mono text-[11px] text-gray-500">
                {Number(problem.marks)} {Number(problem.marks) === 1 ? 'mark' : 'marks'}
              </span>
            </>
          )
          const cls = clsx(
            'flex items-center gap-2.5 rounded-lg border px-3 py-2',
            current ? 'border-accent-900 bg-accent-600/10' : 'border-transparent',
          )
          return (
            <li key={problem.problemId ?? i}>
              {to && !current
                ? <Link to={to} className={clsx(cls, 'hover:bg-gray-900')}>{row}</Link>
                : (
                  <div className={cls} aria-current={current ? 'page' : undefined}>{row}</div>
                )}
            </li>
          )
        })}
      </ul>
      <p className="mt-3 px-1 text-[11px] text-gray-600">
        <Link to="/labs" className="hover:text-gray-300 underline underline-offset-2">All labs</Link>
      </p>
    </div>
  )
}

function StatusIcon({ status }: { status: LabSolveStatus }) {
  if (status === 'SOLVED') {
    return <CheckCircle2 size={16} className="shrink-0 text-green-400" aria-label="Solved in time" />
  }
  if (status === 'LATE') {
    return <Clock size={16} className="shrink-0 text-amber-400" aria-label="Solved late" />
  }
  return <CircleDashed size={16} className="shrink-0 text-gray-600" aria-label="Not solved" />
}

/**
 * The strip above the panes saying what to do before Run or Submit work, in the same card
 * Practice uses for connecting Codeforces.
 */
export function WorkspaceNotice({ icon, tone = 'plain', children, action }: {
  icon: ReactNode
  tone?: 'plain' | 'warn'
  children: ReactNode
  action?: ReactNode
}) {
  return (
    <div className="card flex flex-wrap items-center gap-x-3 gap-y-2">
      <span className={clsx('shrink-0', tone === 'warn' ? 'text-amber-400' : 'text-accent-400')}
        aria-hidden="true">
        {icon}
      </span>
      <span className="min-w-0 flex-1 text-sm text-gray-200">{children}</span>
      {action}
    </div>
  )
}

/** Marks, deadline and whether it is solved, as chips beside the time and memory ones. */
export function LabMetaChips({ marks, dueAt, status }: {
  marks: number
  dueAt: string
  status: LabSolveStatus
}) {
  return (
    <>
      <Meta icon={<Award size={12} />} label="Marks" value={String(Number(marks))} />
      <Meta icon={<CalendarClock size={12} />} label="Due" value={format(new Date(dueAt), 'EEE d MMM, HH:mm')} />
      {status === 'SOLVED' && (
        <span className="flex items-center gap-1 rounded-full border border-green-900 px-2.5 py-1 font-medium text-green-400">
          <CheckCircle2 size={11} /> Solved
        </span>
      )}
      {status === 'LATE' && (
        <span className="flex items-center gap-1 rounded-full border border-amber-900 px-2.5 py-1 font-medium text-amber-400">
          <Clock size={11} /> Solved late
        </span>
      )}
    </>
  )
}
