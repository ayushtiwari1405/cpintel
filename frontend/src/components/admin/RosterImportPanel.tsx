import { useMemo, useRef, useState } from 'react'
import {
  AlertTriangle, Check, Download, Eye, Loader2, ShieldAlert, Upload, UserPlus, X,
} from 'lucide-react'
import { clsx } from 'clsx'
import { useImportRoster } from '@/hooks/useGroups'
import type { RosterImportResult, RosterRowOutcome, RosterRowStatus } from '@/api/adminApi'
import { Panel } from '@/components/admin/AdminUi'

/**
 * Bulk roster import.
 *
 * <p>Adding members was one click each against accounts that had to exist already, and
 * self-registration is off by default — so onboarding a class of two hundred had no path at all.
 *
 * <p>Two rules shape this screen. Nothing is written until the admin has seen the preview, and
 * generated passwords are shown exactly once: they are never stored in readable form and no
 * endpoint will hand them back, so the download is the only copy and the UI has to say so
 * before the admin navigates away from it.
 */

const STATUS_META: Record<RosterRowStatus, { label: string; cls: string }> = {
  ADD_EXISTING:   { label: 'Add',            cls: 'text-indigo-300 bg-indigo-950/40' },
  CREATE_AND_ADD: { label: 'Create',         cls: 'text-green-300 bg-green-950/40' },
  ALREADY_MEMBER: { label: 'Already in',     cls: 'text-gray-400 bg-gray-800/60' },
  DUPLICATE:      { label: 'Duplicate',      cls: 'text-amber-300 bg-amber-950/40' },
  INVALID:        { label: 'Skipped',        cls: 'text-red-300 bg-red-950/40' },
}

const EXAMPLE = `email,fullName,cfHandle,teamName
asha@uni.edu,Asha Rao,asha_r,Team 01
ben@uni.edu,Ben Tan,,Team 02`

interface Props {
  groupId: number
}

export function RosterImportPanel({ groupId }: Props) {
  const [text, setText] = useState('')
  const [preview, setPreview] = useState<RosterImportResult | null>(null)
  const [committed, setCommitted] = useState<RosterImportResult | null>(null)
  const [error, setError] = useState<string | null>(null)
  const fileRef = useRef<HTMLInputElement>(null)

  const importRoster = useImportRoster()

  const credentials = useMemo(
    () => committed?.rows.filter(r => r.generatedPassword) ?? [],
    [committed]
  )

  const run = (dryRun: boolean) => {
    setError(null)
    importRoster.mutate({ groupId, text, dryRun }, {
      onSuccess: result => {
        if (dryRun) { setPreview(result); setCommitted(null) }
        else { setCommitted(result); setPreview(null) }
      },
      onError: (err: any) =>
        setError(err.response?.data?.message ?? 'Could not read that roster'),
    })
  }

  const readFile = (file: File) => {
    const reader = new FileReader()
    reader.onload = () => {
      setText(String(reader.result ?? ''))
      setPreview(null)
      setCommitted(null)
    }
    reader.readAsText(file)
  }

  const downloadCredentials = () => {
    // Built here rather than fetched: these values exist only in the response that created
    // them, and asking the server for them again is deliberately impossible.
    const header = 'username,email,fullName,password,teamName\n'
    const body = credentials.map(r => [
      r.username, r.email, r.fullName ?? '', r.generatedPassword, r.teamName ?? '',
    ].map(csvCell).join(',')).join('\n')

    const blob = new Blob([header + body + '\n'], { type: 'text/csv;charset=utf-8' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `cpintel-group-${groupId}-credentials.csv`
    a.click()
    URL.revokeObjectURL(url)
  }

  const result = committed ?? preview
  const blocked = preview?.blockedReason ?? committed?.blockedReason ?? null

  return (
    <Panel
      title="Import a roster"
      description="Paste from a spreadsheet or a CSV — accounts are created for anyone who does not have one"
    >
      <div className="space-y-3 p-4">
        <textarea
          value={text}
          onChange={e => { setText(e.target.value); setPreview(null); setCommitted(null) }}
          rows={7}
          spellCheck={false}
          placeholder={EXAMPLE}
          className="w-full rounded-lg border border-gray-800 bg-gray-900 px-3 py-2
                     font-mono text-xs text-gray-200 placeholder-gray-700 outline-none
                     focus:border-indigo-600"
        />

        <p className="text-xs text-gray-600">
          First row names the columns. <span className="text-gray-500">email</span> or{' '}
          <span className="text-gray-500">username</span> is required;{' '}
          <span className="text-gray-500">fullName</span>,{' '}
          <span className="text-gray-500">cfHandle</span> and{' '}
          <span className="text-gray-500">teamName</span> are optional. Commas or tabs both
          work, so copying cells straight out of Excel or Sheets is fine.
        </p>

        <div className="flex flex-wrap items-center gap-2">
          <button
            onClick={() => run(true)}
            disabled={!text.trim() || importRoster.isPending}
            className="flex items-center gap-1.5 rounded-lg border border-gray-700 bg-gray-800
                       px-3 py-1.5 text-xs text-gray-200 transition-colors
                       hover:border-indigo-600 disabled:opacity-40"
          >
            {importRoster.isPending
              ? <Loader2 size={13} className="animate-spin" />
              : <Eye size={13} />}
            Preview
          </button>

          <button
            onClick={() => fileRef.current?.click()}
            className="flex items-center gap-1.5 rounded-lg border border-gray-800 px-3 py-1.5
                       text-xs text-gray-400 transition-colors hover:text-gray-200"
          >
            <Upload size={13} /> Load a .csv
          </button>
          <input
            ref={fileRef}
            type="file"
            accept=".csv,.tsv,.txt,text/csv,text/tab-separated-values,text/plain"
            className="hidden"
            onChange={e => {
              const file = e.target.files?.[0]
              if (file) readFile(file)
              e.target.value = ''
            }}
          />

          {preview && !blocked && preview.total > preview.invalid + preview.duplicates && (
            <button
              onClick={() => run(false)}
              disabled={importRoster.isPending}
              className="flex items-center gap-1.5 rounded-lg bg-indigo-600 px-3 py-1.5
                         text-xs font-medium text-white transition-colors
                         hover:bg-indigo-500 disabled:opacity-40"
            >
              <UserPlus size={13} />
              Import {preview.toAdd + preview.toCreate} {preview.toCreate > 0 && '· creates ' + preview.toCreate}
            </button>
          )}
        </div>

        {error && (
          <div className="flex items-start gap-2 rounded-lg border border-red-900 bg-red-950/30
                          px-3 py-2 text-xs text-red-300">
            <AlertTriangle size={13} className="mt-0.5 flex-shrink-0" /> {error}
          </div>
        )}

        {blocked && (
          <div className="flex items-start gap-2 rounded-lg border border-amber-900
                          bg-amber-950/30 px-3 py-2 text-xs text-amber-300">
            <ShieldAlert size={13} className="mt-0.5 flex-shrink-0" /> {blocked}
          </div>
        )}

        {result && <Summary result={result} />}

        {credentials.length > 0 && (
          <div className="rounded-lg border border-green-900 bg-green-950/20 p-3">
            <div className="flex items-start gap-2">
              <AlertTriangle size={14} className="mt-0.5 flex-shrink-0 text-green-400" />
              <div className="min-w-0 flex-1">
                <p className="text-xs font-medium text-green-300">
                  {credentials.length} new {credentials.length === 1 ? 'account' : 'accounts'} —
                  download the passwords now
                </p>
                <p className="mt-0.5 text-xs text-gray-400">
                  They are stored hashed and cannot be shown again. If you leave this page
                  without saving them, each person will need a password reset.
                </p>
                <button
                  onClick={downloadCredentials}
                  className="mt-2 flex items-center gap-1.5 rounded-lg bg-green-700 px-3 py-1.5
                             text-xs font-medium text-white transition-colors
                             hover:bg-green-600"
                >
                  <Download size={13} /> Download credentials CSV
                </button>
              </div>
            </div>
          </div>
        )}

        {result && result.rows.length > 0 && <RowTable rows={result.rows} />}
      </div>
    </Panel>
  )
}

function Summary({ result }: { result: RosterImportResult }) {
  const stats: Array<[string, number, string]> = [
    [result.dryRun ? 'To add' : 'Added', result.toAdd, 'text-indigo-300'],
    [result.dryRun ? 'To create' : 'Created', result.toCreate, 'text-green-300'],
    ['Already in', result.alreadyMembers, 'text-gray-400'],
    ['Duplicates', result.duplicates, 'text-amber-300'],
    ['Skipped', result.invalid, 'text-red-300'],
  ]
  return (
    <div className="flex flex-wrap gap-3 rounded-lg border border-gray-800 bg-gray-900/60 px-3 py-2">
      <span className="text-xs text-gray-500">
        {result.total} row{result.total === 1 ? '' : 's'}
        {result.dryRun && <span className="ml-1 text-gray-600">· nothing written yet</span>}
      </span>
      {stats.filter(([, n]) => n > 0).map(([label, n, tone]) => (
        <span key={label} className="text-xs text-gray-500">
          <span className={clsx('font-medium tabular-nums', tone)}>{n}</span> {label.toLowerCase()}
        </span>
      ))}
    </div>
  )
}

function RowTable({ rows }: { rows: RosterRowOutcome[] }) {
  return (
    <div className="max-h-72 overflow-auto rounded-lg border border-gray-800">
      <table className="w-full text-xs">
        <thead className="sticky top-0 bg-gray-900">
          <tr className="border-b border-gray-800 text-left text-gray-500">
            <th className="px-3 py-1.5 font-medium">Line</th>
            <th className="px-3 py-1.5 font-medium">Who</th>
            <th className="px-3 py-1.5 font-medium">Handle</th>
            <th className="px-3 py-1.5 font-medium">Outcome</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-gray-800/70">
          {rows.map(row => {
            const meta = STATUS_META[row.status]
            return (
              <tr key={row.line} className="hover:bg-gray-800/40">
                <td className="px-3 py-1.5 tabular-nums text-gray-600">{row.line}</td>
                <td className="px-3 py-1.5">
                  <span className="block truncate text-gray-300">
                    {row.email ?? row.username ?? '—'}
                  </span>
                  {row.fullName && (
                    <span className="block truncate text-gray-600">{row.fullName}</span>
                  )}
                </td>
                <td className="px-3 py-1.5 font-mono text-gray-500">
                  {row.teamName ?? row.cfHandle ?? '—'}
                </td>
                <td className="px-3 py-1.5">
                  <span className={clsx('inline-flex items-center gap-1 rounded px-1.5 py-0.5',
                    meta.cls)}>
                    {row.status === 'INVALID'
                      ? <X size={10} />
                      : row.status === 'DUPLICATE' ? <AlertTriangle size={10} />
                      : <Check size={10} />}
                    {meta.label}
                  </span>
                  <span className="ml-2 text-gray-600">{row.message}</span>
                </td>
              </tr>
            )
          })}
        </tbody>
      </table>
    </div>
  )
}

/** Quote a CSV cell so a name containing a comma survives the round trip. */
function csvCell(value: string | null | undefined): string {
  const v = value ?? ''
  return /[",\n]/.test(v) ? `"${v.replace(/"/g, '""')}"` : v
}
