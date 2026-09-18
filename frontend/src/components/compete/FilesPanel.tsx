import { useEffect, useRef, useState } from 'react'
import { clsx } from 'clsx'
import {
  Copy, Download, FileText, FolderOpen, Loader2, Trash2, Upload, X,
} from 'lucide-react'

import { filesApi } from '@/api/filesApi'
import { useToast } from '@/components/common/Toaster'
import {
  useDeleteFile, useDownloadFile, useFileContent, useFileVault, useUploadFile,
} from '@/hooks/useFiles'
import type { ContestRef, PersonalFile } from '@/types'

function human(bytes: number): string {
  if (bytes >= 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
  if (bytes >= 1024) return `${Math.round(bytes / 1024)} KB`
  return `${bytes} B`
}

interface Props {
  open: boolean
  onClose: () => void
  /**
   * Set while a contest is open. Reads then go through the contest route, so the admin's
   * rule for that contest is what answers — the panel does not decide its own access.
   */
  contest?: ContestRef
  /** Inserts a file's text into the editor buffer. Only offered for text files. */
  onUseInEditor?: (text: string) => void
}

/**
 * Your own files, alongside the contest.
 *
 * The point of it is that a contest page is a room you should not have to leave: a template,
 * a snippets header or a scanned team notebook is material you already own, and going to find
 * it in a file browser costs minutes that are being timed. So the library sits behind one
 * button, opens over the page, and closes without disturbing anything underneath.
 */
export function FilesPanel({ open, onClose, contest, onUseInEditor }: Props) {
  const toast = useToast()
  const inputRef = useRef<HTMLInputElement>(null)

  const [selected, setSelected] = useState<string | null>(null)
  const [replace, setReplace] = useState(false)
  const [dragging, setDragging] = useState(false)

  const { data: vault, isLoading, error } = useFileVault(contest, open)
  const { data: content, isFetching: contentLoading } = useFileContent(selected, contest)
  const upload = useUploadFile()
  const remove = useDeleteFile()
  const download = useDownloadFile(contest)

  // Esc closes, which is the only way out that does not need the mouse.
  useEffect(() => {
    if (!open) return
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose() }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [open, onClose])

  if (!open) return null

  const files = vault?.files ?? []
  const denied = (error as any)?.response?.status === 403

  const send = (file: File) => upload.mutate({ file, replace },
    { onSuccess: res => setSelected(res.data.id) })

  const pick = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0]
    if (file) send(file)
    e.target.value = ''
  }

  const onDrop = (e: React.DragEvent) => {
    e.preventDefault()
    setDragging(false)
    const file = e.dataTransfer.files?.[0]
    if (file) send(file)
  }

  return (
    <div className="fixed inset-0 z-40 flex justify-end">
      <div className="flex-1 bg-black/50" onClick={onClose} />

      <aside
        className={clsx(
          'w-[30rem] max-w-full h-full bg-gray-900 border-l border-gray-800',
          'flex flex-col shadow-2xl',
          dragging && 'ring-2 ring-inset ring-indigo-500'
        )}
        onDragOver={e => { e.preventDefault(); setDragging(true) }}
        onDragLeave={() => setDragging(false)}
        onDrop={onDrop}
      >
        <header className="flex items-center gap-2 px-4 h-14 border-b border-gray-800
                           flex-shrink-0">
          <FolderOpen size={16} className="text-indigo-400" />
          <span className="text-sm font-medium text-gray-100">Your files</span>
          {vault && (
            <span className="text-[11px] text-gray-600">
              {human(vault.usedBytes)} of {human(vault.maxTotalBytes)}
            </span>
          )}
          <button
            onClick={onClose}
            className="ml-auto text-gray-600 hover:text-gray-300 transition-colors"
          >
            <X size={16} />
          </button>
        </header>

        {denied ? (
          <div className="p-4 text-xs text-yellow-500/90 leading-relaxed">
            Personal files are turned off for this contest. Your library is untouched — it is
            available again from the Compete page once the contest is over.
          </div>
        ) : (
          <>
            <div className="px-4 py-3 border-b border-gray-800 flex flex-col gap-2
                            flex-shrink-0">
              <div className="flex items-center gap-2">
                <button
                  onClick={() => inputRef.current?.click()}
                  disabled={upload.isPending}
                  className="btn-secondary py-1.5 px-3 text-xs flex items-center gap-1.5"
                >
                  {upload.isPending
                    ? <Loader2 size={12} className="animate-spin" />
                    : <Upload size={12} />}
                  Add file
                </button>
                <label className="flex items-center gap-1.5 text-[11px] text-gray-500
                                  cursor-pointer select-none">
                  <input
                    type="checkbox"
                    checked={replace}
                    onChange={e => setReplace(e.target.checked)}
                    className="accent-indigo-500"
                  />
                  Replace if the name exists
                </label>
                <input
                  ref={inputRef}
                  type="file"
                  onChange={pick}
                  className="hidden"
                />
              </div>
              <p className="text-[11px] text-gray-600 leading-relaxed">
                Drop a file anywhere in this panel. Up to{' '}
                {vault ? human(vault.maxFileBytes) : '2 MB'} each,{' '}
                {vault?.maxFiles ?? 100} files. Nothing here is ever submitted or shared —
                it is read back only to you.
              </p>
            </div>

            <div className="flex flex-col min-h-0 flex-1">
              <div className="overflow-y-auto max-h-56 border-b border-gray-800 flex-shrink-0">
                {isLoading ? (
                  <div className="flex items-center gap-2 p-4 text-xs text-gray-600">
                    <Loader2 size={12} className="animate-spin" /> Loading your files…
                  </div>
                ) : files.length === 0 ? (
                  <p className="p-4 text-xs text-gray-600 leading-relaxed">
                    Nothing here yet. A template, a snippets header or a PDF of your notebook
                    is the usual thing to keep — upload it before a round, not during one.
                  </p>
                ) : (
                  files.map(file => (
                    <FileRow
                      key={file.id}
                      file={file}
                      active={selected === file.id}
                      onOpen={() => setSelected(file.id)}
                      onDownload={() => download.mutate({ fileId: file.id, name: file.name })}
                      onDelete={() => {
                        remove.mutate(file.id)
                        if (selected === file.id) setSelected(null)
                      }}
                    />
                  ))
                )}
              </div>

              <div className="flex-1 min-h-0 flex flex-col">
                {!selected ? (
                  <p className="p-4 text-xs text-gray-600">
                    Pick a file to read it here.
                  </p>
                ) : contentLoading && !content ? (
                  <div className="flex items-center gap-2 p-4 text-xs text-gray-600">
                    <Loader2 size={12} className="animate-spin" /> Opening…
                  </div>
                ) : content?.text != null ? (
                  <>
                    <div className="flex items-center gap-2 px-4 py-2 flex-shrink-0">
                      <FileText size={12} className="text-gray-600" />
                      <span className="text-[11px] text-gray-500 truncate">
                        {content.file.name}
                      </span>
                      <div className="ml-auto flex items-center gap-2">
                        <button
                          onClick={() => {
                            navigator.clipboard.writeText(content.text ?? '')
                            toast.push('info', 'Copied to clipboard')
                          }}
                          className="text-[11px] text-gray-500 hover:text-gray-300
                                     flex items-center gap-1 transition-colors"
                        >
                          <Copy size={11} /> Copy
                        </button>
                        {onUseInEditor && (
                          <button
                            onClick={() => {
                              onUseInEditor(content.text ?? '')
                              onClose()
                            }}
                            className="text-[11px] text-indigo-400 hover:text-indigo-300
                                       transition-colors"
                          >
                            Insert into editor
                          </button>
                        )}
                      </div>
                    </div>
                    {content.notice && (
                      <p className="px-4 pb-1 text-[11px] text-yellow-500/80">
                        {content.notice}
                      </p>
                    )}
                    <pre className="flex-1 overflow-auto mx-4 mb-4 p-3 bg-gray-950 rounded-lg
                                    border border-gray-800 text-[11px] font-mono
                                    text-gray-300 whitespace-pre">
                      {content.text}
                    </pre>
                  </>
                ) : content ? (
                  <BinaryPreview
                    file={content.file}
                    contest={contest}
                    onDownload={() => download.mutate({
                      fileId: content.file.id, name: content.file.name,
                    })}
                  />
                ) : null}
              </div>
            </div>
          </>
        )}
      </aside>
    </div>
  )
}

interface RowProps {
  file: PersonalFile
  active: boolean
  onOpen: () => void
  onDownload: () => void
  onDelete: () => void
}

function FileRow({ file, active, onOpen, onDownload, onDelete }: RowProps) {
  return (
    <div
      className={clsx(
        'flex items-center gap-2 px-4 py-2 cursor-pointer transition-colors group',
        active ? 'bg-indigo-600/15' : 'hover:bg-gray-800/60'
      )}
      onClick={onOpen}
    >
      <FileText
        size={13}
        className={clsx('flex-shrink-0', active ? 'text-indigo-400' : 'text-gray-600')}
      />
      <div className="min-w-0 flex-1">
        <div className="text-xs text-gray-200 truncate">{file.name}</div>
        {file.label && (
          <div className="text-[10px] text-gray-600 truncate">{file.label}</div>
        )}
      </div>
      <span className="text-[10px] text-gray-600 tabular-nums flex-shrink-0">
        {human(file.sizeBytes)}
      </span>
      <button
        onClick={e => { e.stopPropagation(); onDownload() }}
        title="Download"
        className="text-gray-700 hover:text-gray-300 transition-colors flex-shrink-0"
      >
        <Download size={12} />
      </button>
      <button
        onClick={e => { e.stopPropagation(); onDelete() }}
        title="Delete"
        className="text-gray-700 hover:text-red-400 transition-colors flex-shrink-0"
      >
        <Trash2 size={12} />
      </button>
    </div>
  )
}

/**
 * Anything not renderable as text.
 *
 * Images and PDFs are shown anyway, from a blob fetched through the same gated route as the
 * download — a scanned notebook is one of the most useful things to keep here, and "download
 * it and find it in your downloads folder" is exactly the trip out of the page this feature
 * exists to avoid.
 */
function BinaryPreview({ file, contest, onDownload }: {
  file: PersonalFile
  contest?: ContestRef
  onDownload: () => void
}) {
  const [url, setUrl] = useState<string | null>(null)
  const inline = file.contentType?.startsWith('image/')
    || file.contentType === 'application/pdf'

  useEffect(() => {
    if (!inline) return
    let revoked = false
    let objectUrl: string | null = null

    filesApi.download(file.id, contest).then(blob => {
      if (revoked) return
      objectUrl = URL.createObjectURL(blob)
      setUrl(objectUrl)
    }).catch(() => setUrl(null))

    return () => {
      revoked = true
      setUrl(null)
      if (objectUrl) URL.revokeObjectURL(objectUrl)
    }
  }, [file.id, contest, inline])

  return (
    <div className="flex-1 min-h-0 flex flex-col gap-2 p-4">
      <div className="flex items-center gap-2">
        <span className="text-[11px] text-gray-500 truncate">{file.name}</span>
        <button
          onClick={onDownload}
          className="ml-auto text-[11px] text-indigo-400 hover:text-indigo-300
                     flex items-center gap-1 transition-colors"
        >
          <Download size={11} /> Download
        </button>
      </div>

      {!inline ? (
        <p className="text-xs text-gray-600">
          This file is not text — download it to open it.
        </p>
      ) : !url ? (
        <div className="flex items-center gap-2 text-xs text-gray-600">
          <Loader2 size={12} className="animate-spin" /> Loading preview…
        </div>
      ) : file.contentType === 'application/pdf' ? (
        <iframe
          src={url}
          title={file.name}
          className="flex-1 w-full rounded-lg border border-gray-800 bg-gray-950"
        />
      ) : (
        <img
          src={url}
          alt={file.name}
          className="flex-1 min-h-0 object-contain rounded-lg border border-gray-800
                     bg-gray-950"
        />
      )}
    </div>
  )
}
