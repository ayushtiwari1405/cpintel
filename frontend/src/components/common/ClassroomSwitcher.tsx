import { clsx } from 'clsx'
import { School } from 'lucide-react'
import { useMyClassrooms } from '@/hooks/useClassrooms'

interface Props {
  value: number | null
  onChange: (classroomId: number | null) => void
}

/**
 * Which of a student's classrooms they are looking at. Hidden for someone in one classroom or
 * none, for whom the question has only one answer.
 */
export function ClassroomSwitcher({ value, onChange }: Props) {
  const { data: classrooms } = useMyClassrooms()
  if (!classrooms || classrooms.length < 2) return null

  const options: { id: number | null; label: string }[] = [
    { id: null, label: 'All classrooms' },
    ...classrooms.map(c => ({ id: c.classroomId, label: c.name })),
  ]

  return (
    <div className="flex flex-wrap items-center gap-1">
      <School size={13} className="mr-1 text-gray-600" />
      {options.map(option => (
        <button
          key={option.id ?? 'all'}
          onClick={() => onChange(option.id)}
          className={clsx(
            'rounded-md px-2.5 py-1 text-xs transition-colors',
            value === option.id
              ? 'bg-indigo-600/20 text-indigo-300'
              : 'text-gray-500 hover:bg-gray-800/60 hover:text-gray-300'
          )}
        >
          {option.label}
        </button>
      ))}
    </div>
  )
}
