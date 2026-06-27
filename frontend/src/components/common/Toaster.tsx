import { create } from 'zustand'
import { useEffect, useState } from 'react'
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
    <div className="fixed bottom-4 right-4 z-50 flex flex-col gap-2">
      {toasts.map((t) => (
        <div
          key={t.id}
          className={`flex items-center gap-3 px-4 py-3 rounded-lg border backdrop-blur-sm
                      min-w-64 max-w-sm shadow-lg animate-slide-up ${colors[t.type]}`}
        >
          {icons[t.type]}
          <span className="text-sm text-gray-200 flex-1">{t.message}</span>
          <button onClick={() => dismiss(t.id)} className="text-gray-500 hover:text-gray-300">
            <X size={14} />
          </button>
        </div>
      ))}
    </div>
  )
}
