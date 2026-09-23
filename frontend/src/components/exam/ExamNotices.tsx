import { AlertTriangle, Info, X } from 'lucide-react'
import { clsx } from 'clsx'

import type { ExamNotice } from '@/hooks/useExamSession'

interface Props {
  notices: ExamNotice[]
  onDismiss: (id: string) => void
}

/**
 * What the examination is telling the candidate right now.
 *
 * <p>In the page rather than as toasts, deliberately. A toast that has faded is indistinguishable
 * from one that was never shown, and "you have been away longer than allowed" has to still be on
 * screen when somebody comes back to look at it. They can be dismissed, because a warning that
 * cannot be cleared eventually covers the problem it is warning about.
 */
export function ExamNotices({ notices, onDismiss }: Props) {
  if (notices.length === 0) return null

  return (
    <div className="flex flex-col gap-2">
      {notices.map(notice => (
        <div
          key={notice.id}
          role={notice.tone === 'info' ? 'status' : 'alert'}
          className={clsx(
            'flex items-start gap-2 rounded-lg border px-3 py-2 text-xs leading-relaxed',
            {
              'border-indigo-900 bg-indigo-950/40 text-indigo-200': notice.tone === 'info',
              'border-amber-900 bg-amber-950/40 text-amber-200':    notice.tone === 'warn',
              'border-red-900 bg-red-950/40 text-red-200':          notice.tone === 'danger',
            }
          )}
        >
          {notice.tone === 'info'
            ? <Info size={13} className="mt-0.5 flex-shrink-0" />
            : <AlertTriangle size={13} className="mt-0.5 flex-shrink-0" />}
          <div className="min-w-0 flex-1">
            <p className="font-medium">{notice.title}</p>
            <p className="mt-0.5 opacity-90">{notice.body}</p>
          </div>
          <button
            onClick={() => onDismiss(notice.id)}
            aria-label="Dismiss"
            className="flex-shrink-0 rounded p-0.5 opacity-60 transition-opacity hover:opacity-100"
          >
            <X size={12} />
          </button>
        </div>
      ))}
    </div>
  )
}
