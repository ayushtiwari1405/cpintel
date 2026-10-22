import { useEffect, useId, useRef, type ReactNode } from 'react'
import { X } from 'lucide-react'
import { clsx } from 'clsx'

interface Props {
  open: boolean
  onClose: () => void
  title: ReactNode
  description?: ReactNode
  children: ReactNode
  /** Buttons along the bottom: cancel first, the action last. */
  footer?: ReactNode
  /** 'side' slides in from the right edge and takes the full height, for record details. */
  variant?: 'center' | 'side'
  size?: 'sm' | 'md' | 'lg'
  /** False while a save is in flight, so Escape or a stray click cannot abandon it. */
  dismissible?: boolean
}

/**
 * A modal built on the native <dialog> element.
 *
 * showModal() gives what the hand-built overlays lacked: the page behind becomes inert, focus
 * moves in and is held there, Escape closes it, it sits in the top layer above everything,
 * and assistive technology announces a modal dialog with this title. Focus returns to whatever
 * opened it when it closes.
 */
export function Dialog({ open, onClose, title, description, children, footer,
  variant = 'center', size = 'md', dismissible = true }: Props) {
  const ref = useRef<HTMLDialogElement>(null)
  const titleId = useId()
  const descId = useId()

  useEffect(() => {
    const el = ref.current
    if (!el) return
    if (open && !el.open) el.showModal()
    if (!open && el.open) el.close()
  }, [open])

  return (
    <dialog
      ref={ref}
      aria-labelledby={titleId}
      aria-describedby={description ? descId : undefined}
      onCancel={(e) => { e.preventDefault(); if (dismissible) onClose() }}
      onClick={(e) => { if (dismissible && e.target === e.currentTarget) onClose() }}
      className={clsx(
        'm-0 max-h-none max-w-none border-gray-800 bg-gray-900 p-0 text-gray-100',
        'backdrop:bg-black/60 backdrop:backdrop-blur-[1px]',
        variant === 'side'
          ? 'ml-auto h-full w-full border-l sm:w-120'
          : clsx('mx-auto my-[8vh] max-h-[84vh] w-[calc(100%-2rem)] rounded-xl border',
              size === 'sm' ? 'sm:max-w-sm' : size === 'lg' ? 'sm:max-w-2xl' : 'sm:max-w-lg'),
      )}
    >
      {open && (
        <div className={clsx('flex flex-col', variant === 'side' ? 'h-full' : 'max-h-[84vh]')}>
          <div className="flex items-start justify-between gap-3 border-b border-gray-800 px-5 py-4">
            <div className="min-w-0">
              <h2 id={titleId} className="text-base font-semibold text-gray-50">{title}</h2>
              {description && (
                <p id={descId} className="mt-0.5 text-sm text-gray-400">{description}</p>
              )}
            </div>
            {dismissible && (
              <button type="button" onClick={onClose} aria-label="Close"
                className="-mr-1.5 flex h-8 w-8 shrink-0 items-center justify-center rounded-lg
                           text-gray-500 transition-colors hover:bg-gray-850 hover:text-gray-200">
                <X size={17} />
              </button>
            )}
          </div>
          <div className="min-h-0 flex-1 overflow-y-auto px-5 py-4">{children}</div>
          {footer && (
            <div className="flex flex-wrap items-center justify-end gap-2 border-t border-gray-800
                            px-5 py-3">
              {footer}
            </div>
          )}
        </div>
      )}
    </dialog>
  )
}
