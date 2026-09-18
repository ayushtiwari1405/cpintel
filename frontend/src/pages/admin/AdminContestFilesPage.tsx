import { useState } from 'react'
import {
  useClearContestRule, useClearPolicyDefault, useContestFilePolicy, useSetContestRule,
  useSetPolicyDefault,
} from '@/hooks/useAdmin'
import { Ago, EmptyRow, Panel, Pill } from '@/components/admin/AdminUi'
import { Info, Loader2, Plus, RotateCcw, Trash2 } from 'lucide-react'
import { clsx } from 'clsx'

const PLATFORMS = ['CODEFORCES', 'DOMJUDGE']

/**
 * Which contests let their contestants open their own uploaded files.
 *
 * The screen mirrors how the rule is actually resolved: a default that covers everything, and
 * a short list of contests that depart from it. Writing exceptions rather than a row per
 * contest is what keeps this readable — the list stays at the length of "rounds we had a
 * reason to treat differently", which is the only part worth checking before a contest starts.
 *
 * Turning access off never touches anyone's files. It hides the panel inside that contest;
 * the library itself stays exactly where its owner left it.
 */
export default function AdminContestFilesPage() {
  const { data, isLoading } = useContestFilePolicy()
  const setDefault = useSetPolicyDefault()
  const clearDefault = useClearPolicyDefault()
  const setRule = useSetContestRule()
  const clearRule = useClearContestRule()

  const [platform, setPlatform] = useState(PLATFORMS[0])
  const [contestId, setContestId] = useState('')
  const [enabled, setEnabled] = useState(false)
  const [note, setNote] = useState('')

  // Any non-empty id is valid: Codeforces numbers its contests and DOMjudge does not, so
  // rejecting anything non-numeric here would make DOMjudge rounds unruleable.
  const trimmedId = contestId.trim()
  const canAdd = trimmedId !== ''

  if (isLoading || !data) {
    return (
      <div className="flex items-center justify-center gap-2 py-20 text-sm text-gray-500">
        <Loader2 size={16} className="animate-spin" /> Loading the policy…
      </div>
    )
  }

  const submitRule = () => {
    if (!canAdd) return
    setRule.mutate(
      { platform, contestId: trimmedId, enabled, note: note.trim() || undefined },
      { onSuccess: () => { setContestId(''); setNote('') } }
    )
  }

  return (
    <div className="space-y-4">
      <Panel
        title="Default for every contest"
        description="What a contest does unless it has a rule of its own"
        actions={!data.defaultFromConfig && (
          <button
            onClick={() => clearDefault.mutate()}
            className="flex items-center gap-1 text-xs text-gray-500 hover:text-gray-300"
          >
            <RotateCcw size={12} /> Revert to configuration
          </button>
        )}
      >
        <div className="flex items-center justify-between gap-4 p-4">
          <div>
            <p className="text-sm text-gray-200">
              Personal files are{' '}
              <span className={data.defaultEnabled ? 'text-green-400' : 'text-red-400'}>
                {data.defaultEnabled ? 'available' : 'unavailable'}
              </span>{' '}
              during contests by default.
            </p>
            <p className="mt-1 text-xs text-gray-500">
              {data.defaultFromConfig
                ? 'This is the value from configuration; no admin has overridden it.'
                : 'Set from this screen, overriding the configured value.'}
            </p>
          </div>
          <button
            onClick={() => setDefault.mutate({ enabled: !data.defaultEnabled })}
            disabled={setDefault.isPending}
            className={clsx(
              'flex-shrink-0 rounded-lg px-3 py-2 text-sm font-medium transition-colors',
              'disabled:opacity-50',
              data.defaultEnabled
                ? 'border border-gray-800 text-gray-300 hover:bg-red-900/30 hover:text-red-400'
                : 'bg-indigo-600 text-white hover:bg-indigo-500'
            )}
          >
            {data.defaultEnabled ? 'Turn off everywhere' : 'Turn on everywhere'}
          </button>
        </div>
      </Panel>

      <Panel
        title="Contests that differ"
        description={`${data.rules.length} exception${data.rules.length === 1 ? '' : 's'} to the default`}
      >
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-gray-800 text-left text-xs text-gray-500">
                <th className="px-4 py-2 font-medium">Contest</th>
                <th className="px-4 py-2 font-medium">Personal files</th>
                <th className="px-4 py-2 font-medium">Note</th>
                <th className="px-4 py-2 font-medium">Changed</th>
                <th className="px-4 py-2 font-medium text-right">Remove</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-800">
              {data.rules.length === 0 && (
                <EmptyRow colSpan={5}>
                  No exceptions. Every contest follows the default above.
                </EmptyRow>
              )}
              {data.rules.map(rule => (
                <tr key={`${rule.platform}-${rule.contestId}`}
                  className="transition-colors hover:bg-gray-800/40">
                  <td className="px-4 py-2.5 text-gray-200">
                    {rule.platform} · {rule.contestId}
                  </td>
                  <td className="px-4 py-2.5">
                    <Pill tone={rule.enabled ? 'green' : 'red'}>
                      {rule.enabled ? 'Allowed' : 'Blocked'}
                    </Pill>
                  </td>
                  <td className="max-w-xs truncate px-4 py-2.5 text-xs text-gray-500">
                    {rule.note || '—'}
                  </td>
                  <td className="px-4 py-2.5 text-xs text-gray-500">
                    <Ago at={rule.updatedAt} />
                  </td>
                  <td className="px-4 py-2.5 text-right">
                    <button
                      title="Drop this rule so the contest follows the default again"
                      aria-label={`Remove the rule for contest ${rule.contestId}`}
                      onClick={() => clearRule.mutate({
                        platform: rule.platform,
                        contestId: rule.contestId!,
                      })}
                      className="rounded-md border border-gray-800 p-1.5 text-gray-400
                        transition-colors hover:bg-gray-800 hover:text-gray-200"
                    >
                      <Trash2 size={14} />
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Panel>

      <Panel title="Add an exception" description="Overrides the default for one contest only">
        <div className="flex flex-wrap items-end gap-2 p-4">
          <label className="flex flex-col gap-1">
            <span className="text-xs text-gray-500">Platform</span>
            <select
              value={platform}
              onChange={e => setPlatform(e.target.value)}
              className="rounded-lg border border-gray-800 bg-gray-900 px-3 py-2 text-sm
                         text-gray-300 outline-none focus:border-indigo-600"
            >
              {PLATFORMS.map(p => <option key={p} value={p}>{p}</option>)}
            </select>
          </label>

          <label className="flex flex-col gap-1">
            <span className="text-xs text-gray-500">Contest ID</span>
            <input
              value={contestId}
              onChange={e => setContestId(e.target.value)}
              placeholder="2259"
              inputMode="numeric"
              className="w-28 rounded-lg border border-gray-800 bg-gray-900 px-3 py-2 text-sm
                         text-gray-200 placeholder-gray-600 outline-none focus:border-indigo-600"
            />
          </label>

          <label className="flex flex-col gap-1">
            <span className="text-xs text-gray-500">Personal files</span>
            <select
              value={enabled ? 'true' : 'false'}
              onChange={e => setEnabled(e.target.value === 'true')}
              className="rounded-lg border border-gray-800 bg-gray-900 px-3 py-2 text-sm
                         text-gray-300 outline-none focus:border-indigo-600"
            >
              <option value="false">Blocked</option>
              <option value="true">Allowed</option>
            </select>
          </label>

          <label className="flex min-w-[12rem] flex-1 flex-col gap-1">
            <span className="text-xs text-gray-500">Why (optional)</span>
            <input
              value={note}
              onChange={e => setNote(e.target.value)}
              placeholder="Proctored round"
              maxLength={200}
              className="rounded-lg border border-gray-800 bg-gray-900 px-3 py-2 text-sm
                         text-gray-200 placeholder-gray-600 outline-none focus:border-indigo-600"
            />
          </label>

          <button
            onClick={submitRule}
            disabled={!canAdd || setRule.isPending}
            className="flex items-center gap-1.5 rounded-lg bg-indigo-600 px-3 py-2 text-sm
              font-medium text-white transition-colors hover:bg-indigo-500
              disabled:cursor-not-allowed disabled:opacity-40"
          >
            <Plus size={14} /> Add rule
          </button>
        </div>

        <p className="flex items-start gap-2 border-t border-gray-800 px-4 py-3 text-xs text-gray-500">
          <Info size={13} className="mt-0.5 flex-shrink-0" />
          Blocking a contest hides the files panel inside it. Nobody's uploads are touched, and
          their library stays reachable everywhere else.
        </p>
      </Panel>
    </div>
  )
}
