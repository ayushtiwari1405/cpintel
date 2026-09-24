import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { AlertTriangle, Check, Eye, KeyRound, Loader2, X } from 'lucide-react'
import { clsx } from 'clsx'

import { adminApi } from '@/api/adminApi'
import type {
  DomjudgePasswordResult, DomjudgePasswordRow, DomjudgePasswordRowStatus,
} from '@/api/adminApi'
import { Panel } from '@/components/admin/AdminUi'
import { useToast } from '@/components/common/Toaster'

/**
 * New DOMjudge passwords for the whole group at once.
 *
 * <p>When the judge's passwords are regenerated, every attached login stops working together,
 * and this replaces fixing them one dialog at a time. Each row reaches the member that login is
 * attached to, so it works even where a CPIntel username differs from the DOMjudge one. As with
 * the roster import, nothing is written until the admin has seen the preview.
 */

const STATUS_META: Record<DomjudgePasswordRowStatus, { label: string; cls: string }> = {
  WILL_CHANGE: { label: 'Will update', cls: 'text-green-300 bg-green-950/40' },
  WILL_ATTACH: { label: 'Will attach', cls: 'text-indigo-300 bg-indigo-950/40' },
  CHANGED:     { label: 'Updated',     cls: 'text-green-300 bg-green-950/40' },
  ATTACHED:    { label: 'Attached',    cls: 'text-indigo-300 bg-indigo-950/40' },
  NOT_FOUND:   { label: 'Not in group', cls: 'text-amber-300 bg-amber-950/40' },
  DUPLICATE:   { label: 'Duplicate',   cls: 'text-amber-300 bg-amber-950/40' },
  FAILED:      { label: 'Failed',      cls: 'text-red-300 bg-red-950/40' },
}

const EXAMPLE = `djUsername,djPassword
team01,newpass1
team02,newpass2`

export function DomjudgePasswordPanel({ groupId }: { groupId: number }) {
  const qc = useQueryClient()
  const toast = useToast()
  const [text, setText] = useState('')
  const [result, setResult] = useState<DomjudgePasswordResult | null>(null)
  const [error, setError] = useState<string | null>(null)

  const update = useMutation({
    mutationFn: (dryRun: boolean) =>
      adminApi.domjudgePasswords(groupId, { text, dryRun }).then(r => r.data),
    onSuccess: (res) => {
      setResult(res)
      if (res.dryRun) return
      // Each member's account cell shows the team and expiry, both of which may have moved.
      qc.invalidateQueries({ queryKey: ['admin', 'domjudge'] })
      toast.push(res.failed > 0 ? 'error' : 'success',
        `${res.ok} DOMjudge password${res.ok === 1 ? '' : 's'} updated`
          + (res.failed > 0 ? `, ${res.failed} failed` : ''))
    },
    onError: (err: any) =>
      setError(err.response?.data?.message ?? 'Could not read that paste'),
  })

  const run = (dryRun: boolean) => {
    setError(null)
    update.mutate(dryRun)
  }

  const reset = (value: string) => {
    setText(value)
    setResult(null)
  }

  const canCommit = result?.dryRun && result.ok > 0

  return (
    <Panel
      title="Update DOMjudge passwords"
      description="After passwords are changed on the judge, paste the new ones here"
    >
      <div className="space-y-3 p-4">
        <textarea
          value={text}
          onChange={e => reset(e.target.value)}
          rows={5}
          spellCheck={false}
          placeholder={EXAMPLE}
          className="w-full rounded-lg border border-gray-800 bg-gray-900 px-3 py-2
                     font-mono text-xs text-gray-200 placeholder-gray-700 outline-none
                     focus:border-indigo-600"
        />

        <p className="text-xs text-gray-600">
          Needs <span className="text-gray-500">djUsername</span> and{' '}
          <span className="text-gray-500">djPassword</span> columns. Each row updates the member
          of this group that login is attached to. A member with nothing attached whose CPIntel
          username is the login has it attached. Every new password is checked against the judge
          first, and a rejected one leaves the old password in place.
        </p>

        <div className="flex flex-wrap items-center gap-2">
          <button
            onClick={() => run(true)}
            disabled={!text.trim() || update.isPending}
            className="flex items-center gap-1.5 rounded-lg border border-gray-700 bg-gray-800
                       px-3 py-1.5 text-xs text-gray-200 transition-colors
                       hover:border-indigo-600 disabled:opacity-40"
          >
            {update.isPending ? <Loader2 size={13} className="animate-spin" /> : <Eye size={13} />}
            Preview
          </button>

          {canCommit && (
            <button
              onClick={() => run(false)}
              disabled={update.isPending}
              className="flex items-center gap-1.5 rounded-lg bg-indigo-600 px-3 py-1.5
                         text-xs font-medium text-white transition-colors
                         hover:bg-indigo-500 disabled:opacity-40"
            >
              <KeyRound size={13} /> Update {result.ok}
            </button>
          )}
        </div>

        {error && (
          <div className="flex items-start gap-2 rounded-lg border border-red-900 bg-red-950/30
                          px-3 py-2 text-xs text-red-300">
            <AlertTriangle size={13} className="mt-0.5 flex-shrink-0" /> {error}
          </div>
        )}

        {result && (
          <div className="flex flex-wrap gap-3 rounded-lg border border-gray-800 bg-gray-900/60
                          px-3 py-2 text-xs text-gray-500">
            <span>
              {result.total} row{result.total === 1 ? '' : 's'}
              {result.dryRun && <span className="ml-1 text-gray-600">· nothing written yet</span>}
            </span>
            {result.ok > 0 && (
              <span><span className="font-medium text-green-300">{result.ok}</span>{' '}
                {result.dryRun ? 'ready' : 'updated'}</span>
            )}
            {result.notFound > 0 && (
              <span><span className="font-medium text-amber-300">{result.notFound}</span>{' '}
                not in this group</span>
            )}
            {result.failed > 0 && (
              <span><span className="font-medium text-red-300">{result.failed}</span> failed</span>
            )}
          </div>
        )}

        {result && result.rows.length > 0 && <RowTable rows={result.rows} />}
      </div>
    </Panel>
  )
}

function RowTable({ rows }: { rows: DomjudgePasswordRow[] }) {
  return (
    <div className="max-h-72 overflow-auto rounded-lg border border-gray-800">
      <table className="w-full text-xs">
        <thead className="sticky top-0 bg-gray-900">
          <tr className="border-b border-gray-800 text-left text-gray-500">
            <th className="px-3 py-1.5 font-medium">Line</th>
            <th className="px-3 py-1.5 font-medium">DOMjudge login</th>
            <th className="px-3 py-1.5 font-medium">Member</th>
            <th className="px-3 py-1.5 font-medium">Outcome</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-gray-800/70">
          {rows.map(row => {
            const meta = STATUS_META[row.status]
            const bad = row.status === 'FAILED'
            const warn = row.status === 'NOT_FOUND' || row.status === 'DUPLICATE'
            return (
              <tr key={row.line} className="hover:bg-gray-800/40">
                <td className="px-3 py-1.5 tabular-nums text-gray-600">{row.line}</td>
                <td className="px-3 py-1.5 font-mono text-gray-300">{row.djUsername ?? '—'}</td>
                <td className="px-3 py-1.5 text-gray-400">{row.username ?? '—'}</td>
                <td className="px-3 py-1.5">
                  <span className={clsx('inline-flex items-center gap-1 rounded px-1.5 py-0.5',
                    meta.cls)}>
                    {bad ? <X size={10} /> : warn ? <AlertTriangle size={10} /> : <Check size={10} />}
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
