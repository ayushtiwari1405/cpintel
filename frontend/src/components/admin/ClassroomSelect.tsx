import { useEffect, useMemo } from 'react'
import { Link } from 'react-router-dom'
import { useAdminClassrooms } from '@/hooks/useClassrooms'
import { useIsSuperAdmin } from '@/hooks/useAdmin'

interface Props {
  value: number | null
  onChange: (classroomId: number | null) => void
  /** Offer "every classroom" as a choice, for filters rather than for creating things. */
  allowAll?: boolean
  className?: string
}

/**
 * Picks one of the classrooms this admin runs.
 *
 * When they run exactly one and "all" is not on offer, it is chosen for them — the common case
 * of a single course should not ask a question with one answer.
 */
export function ClassroomSelect({ value, onChange, allowAll, className }: Props) {
  const { data: classrooms, isLoading } = useAdminClassrooms()
  const isSuper = useIsSuperAdmin()
  const active = useMemo(() => classrooms?.filter(c => c.active) ?? [], [classrooms])

  useEffect(() => {
    if (!allowAll && value == null && active.length === 1) onChange(active[0].classroomId)
  }, [allowAll, value, active, onChange])

  if (!isLoading && active.length === 0 && !allowAll) {
    return (
      <span className="text-xs text-amber-400">
        {isSuper
          ? <>No classroom yet — <Link to="/admin/classrooms" className="underline">create one</Link> first.</>
          : 'You are not in a classroom yet. Ask a superadmin to add you to one.'}
      </span>
    )
  }

  return (
    <select
      value={value ?? ''}
      onChange={e => onChange(e.target.value === '' ? null : Number(e.target.value))}
      disabled={isLoading}
      className={className ?? `rounded-lg border border-gray-800 bg-gray-900 px-3 py-2 text-sm
                               text-gray-200 outline-none focus:border-indigo-600`}
    >
      <option value="">{allowAll ? 'Every classroom' : 'Pick a classroom…'}</option>
      {active.map(c => (
        <option key={c.classroomId} value={c.classroomId}>{c.name}</option>
      ))}
    </select>
  )
}
