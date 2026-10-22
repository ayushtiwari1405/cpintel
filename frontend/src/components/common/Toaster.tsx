import { create } from 'zustand'
import { X, CheckCircle, AlertCircle, Info } from 'lucide-react'

type ToastType = 'success' | 'error' | 'info'

interface Toast {
  id: string
  type: ToastType
  message: string
}

interface ToastStore {
  toasts: Toast[]
  push: (type: ToastType, message: string) => void
  dismiss: (id: string) => void
}

export const useToast = create<ToastStore>((set) => ({
  toasts: [],
  push: (type, message) => {
    const id = Math.random().toString(36).slice(2)
    set((s) => ({ toasts: [...s.toasts, { id, type, message }] }))
    setTimeout(() => set((s) => ({ toasts: s.toasts.filter((t) => t.id !== id) })), 4000)
  },
  dismiss: (id) => set((s) => ({ toasts: s.toasts.filter((t) => t.id !== id) })),
}))

const icons: Record<ToastType, React.ReactNode> = {
  success: <CheckCircle size={16} className="text-green-400" />,
  error:   <AlertCircle size={16} className="text-red-400" />,
  info:    <Info size={16} className="text-blue-400" />,
}

const colors: Record<ToastType, string> = {
  success: 'border-green-800 bg-green-950/80',
  error:   'border-red-800 bg-red-950/80',
  info:    'border-blue-800 bg-blue-950/80',
}

export function Toaster() {
  const { toasts, dismiss } = useToast()
  return (
    // Always mounted, so a screen reader is already listening when a toast arrives. Errors
    // interrupt (role=alert); everything else waits its turn.
    <div role="region" aria-label="Notifications" aria-live="polite"
      className="pointer-events-none fixed inset-x-3 bottom-3 z-60 flex flex-col items-end gap-2
                 sm:inset-x-auto sm:bottom-4 sm:right-4">
      {toasts.map((t) => (
        <div
          key={t.id}
          role={t.type === 'error' ? 'alert' : 'status'}
          className={`pointer-events-auto flex w-full items-start gap-3 rounded-lg border px-3.5 py-3
                      shadow-lg shadow-black/30 backdrop-blur-xs animate-slide-up sm:w-auto
                      sm:min-w-64 sm:max-w-sm ${colors[t.type]}`}
        >
          <span className="mt-0.5 shrink-0" aria-hidden="true">{icons[t.type]}</span>
          <span className="flex-1 wrap-break-word text-sm text-gray-100">{t.message}</span>
          <button type="button" onClick={() => dismiss(t.id)} aria-label="Dismiss notification"
            className="-mr-1 flex h-6 w-6 shrink-0 items-center justify-center rounded-sm
                       text-gray-400 hover:bg-gray-800/60 hover:text-gray-100">
            <X size={14} />
          </button>
        </div>
      ))}
    </div>
  )
}
