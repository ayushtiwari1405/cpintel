import { useMemo, useState } from 'react'
import { useParams } from 'react-router-dom'
import {
  AlertTriangle, ArrowLeft, Code2, Eye, Flag, KeyRound, Loader2, Lock, Monitor, Plus, Save,
  ScrollText, Settings2, Trash2, Users,
} from 'lucide-react'
import { clsx } from 'clsx'

import { Ago, EmptyRow, Pager, Panel, Pill, StatCard } from '@/components/admin/AdminUi'
import { ExamPasswordsTab } from '@/components/admin/ExamPasswordsTab'
import { LifecyclePill } from '@/pages/admin/AdminExamsPage'
import {
  useAdminEvent, useAssignToEvent, useDeleteEvent, useEventLanguageCatalog, useExamFlags,
  useExamLogs, useExamMonitor, useSetLifecycle, useSetProblems, useUnassignTeam, useUnassignUser,
  useUpdateEvent,
} from '@/hooks/useExams'
import { useAdminGroups } from '@/hooks/useGroups'
import { useAdminUsers } from '@/hooks/useAdmin'
import type {
  DesktopPolicy, EventDetail, EventProblem, ExamEventType, ExamFlag, ExamFlagKind, ExamFlagReport,
  ExamMonitorRow,
} from '@/types'

type Tab = 'settings' | 'people' | 'problems' | 'passwords' | 'monitor' | 'logs'

/**
 * One examination, from the side that runs it.
 *
 * <p>Five tabs, in the order the work actually happens: configure it, say who sits it, list its
 * problems, watch it while it runs, read what happened afterwards. The last two are the reason
 * the examination product exists at all, and they are deliberately separate — a live dashboard
 * answers "who is in trouble right now", a log answers "what happened to this person", and a
 * screen that tried to do both would do neither.
 */
export default function AdminExamDetailPage() {
  const { examId } = useParams()
  const id = Number(examId)
  const valid = Number.isFinite(id)

  const { data: detail, isLoading } = useAdminEvent(valid ? id : null)
  const [tab, setTab] = useState<Tab>('settings')

  if (isLoading || !detail) {
    return (
      <div className="flex items-center justify-center gap-2 py-20 text-sm text-gray-500">
        <Loader2 size={16} className="animate-spin" /> Loading…
      </div>
    )
  }

  const { event } = detail
  const exam = event.kind === 'EXAM'

  const tabs: { id: Tab; label: string; icon: typeof Settings2 }[] = [
    { id: 'settings', label: 'Settings', icon: Settings2 },
    { id: 'people',   label: 'Who sits it', icon: Users },
    { id: 'problems', label: 'Problems', icon: Plus },
    ...(exam ? [
      // Only an examination has passwords. A contest is open to whoever it is assigned to for
      // as long as its window is open, and a tab offering to lock one would suggest otherwise.
      { id: 'passwords' as Tab, label: 'Passwords', icon: KeyRound },
      { id: 'monitor' as Tab, label: 'Monitor', icon: Monitor },
      { id: 'logs' as Tab,    label: 'Session log', icon: ScrollText },
    ] : []),
  ]

  return (
    <div className="space-y-4">
      <header className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <div className="flex items-center gap-2">
            <h1 className="text-lg font-semibold text-gray-100">{event.name}</h1>
            <LifecyclePill lifecycle={event.lifecycle} />
            {event.lockdownRequired && (
              <span className="inline-flex items-center gap-1 text-[11px] text-indigo-300">
                <Eye size={11} /> monitored
              </span>
            )}
          </div>
          <p className="mt-0.5 text-xs text-gray-500">
            {exam ? 'Examination' : 'Contest'} · {event.platform} {event.externalId}
            {event.teamName && <> · {event.teamName}</>}
            {event.startsAt && <> · starts <Ago at={event.startsAt} /></>}
          </p>
        </div>
        <LifecycleControls detail={detail} />
      </header>

      <div className="flex flex-wrap items-center gap-1 rounded-lg border border-gray-800
        bg-gray-900 p-1 w-fit">
        {tabs.map(({ id: tabId, label, icon: Icon }) => (
          <button
            key={tabId}
            onClick={() => setTab(tabId)}
            className={clsx(
              'flex items-center gap-1.5 rounded-md px-3 py-1.5 text-xs font-medium',
              'transition-colors',
              tab === tabId
                ? 'bg-indigo-600/20 text-indigo-300'
                : 'text-gray-500 hover:text-gray-300'
            )}
          >
            <Icon size={13} /> {label}
          </button>
        ))}
      </div>

      {tab === 'settings' && <SettingsTab detail={detail} />}
      {tab === 'people'   && <PeopleTab detail={detail} />}
      {tab === 'problems' && <ProblemsTab detail={detail} />}
      {tab === 'passwords' && <ExamPasswordsTab eventId={id} />}
      {tab === 'monitor'  && <MonitorTab eventId={id} live={event.lifecycle === 'ACTIVE'} />}
      {tab === 'logs'     && <LogsTab eventId={id} live={event.lifecycle === 'ACTIVE'} />}
    </div>
  )
}

// ----------------------------------------------------------------- lifecycle

/**
 * Publishing, ending and archiving.
 *
 * Publishing is the one step that makes an examination real to the people sitting it, so it is
 * a button of its own rather than a state dropdown — a dropdown invites the reading that all
 * five states are equivalent choices, and "back to draft" during a live paper is not a choice
 * anybody should be offered.
 */
function LifecycleControls({ detail }: { detail: EventDetail }) {
  const setLifecycle = useSetLifecycle()
  const remove = useDeleteEvent()
  const { event } = detail
  const id = event.eventId

  return (
    <div className="flex flex-wrap items-center gap-2">
      {event.lifecycle === 'DRAFT' && (
        <button
          onClick={() => setLifecycle.mutate({ eventId: id, lifecycle: 'SCHEDULED' })}
          disabled={!event.startsAt || !event.endsAt || setLifecycle.isPending}
          title={!event.startsAt || !event.endsAt
            ? 'Give it a start and an end first'
            : 'Everyone assigned will see it'}
          className="rounded-lg bg-indigo-600 px-3 py-1.5 text-xs font-medium text-white
                     transition-colors hover:bg-indigo-500 disabled:opacity-40"
        >
          Publish
        </button>
      )}
      {(event.lifecycle === 'SCHEDULED' || event.lifecycle === 'ACTIVE') && (
        <button
          onClick={() => setLifecycle.mutate({ eventId: id, lifecycle: 'ENDED' })}
          className="rounded-lg border border-gray-800 bg-gray-900 px-3 py-1.5 text-xs
                     text-gray-300 transition-colors hover:bg-gray-800"
        >
          End now
        </button>
      )}
      {event.lifecycle === 'ENDED' && (
        <button
          onClick={() => setLifecycle.mutate({ eventId: id, lifecycle: 'ARCHIVED' })}
          className="rounded-lg border border-gray-800 bg-gray-900 px-3 py-1.5 text-xs
                     text-gray-300 transition-colors hover:bg-gray-800"
        >
          Archive
        </button>
      )}
      {event.lifecycle === 'ARCHIVED' && (
        <button
          onClick={() => setLifecycle.mutate({ eventId: id, lifecycle: 'SCHEDULED' })}
          className="rounded-lg border border-gray-800 bg-gray-900 px-3 py-1.5 text-xs
                     text-gray-300 transition-colors hover:bg-gray-800"
        >
          Unarchive
        </button>
      )}
      {event.lifecycle === 'DRAFT' && (
        <button
          onClick={() => {
            if (confirm(`Delete ${event.name}? This cannot be undone.`)) {
              remove.mutate({ eventId: id })
            }
          }}
          className="flex items-center gap-1.5 rounded-lg border border-red-900/60 px-3 py-1.5
                     text-xs text-red-400 transition-colors hover:bg-red-950/40"
        >
          <Trash2 size={12} /> Delete
        </button>
      )}
    </div>
  )
}

// ------------------------------------------------------------------ settings

const INPUT = 'w-full rounded-lg border border-gray-800 bg-gray-900 px-3 py-2 text-sm '
  + 'text-gray-200 placeholder-gray-600 outline-none focus:border-indigo-600'

function toLocalInput(iso: string | null): string {
  if (!iso) return ''
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return ''
  // datetime-local wants local wall-clock time with no zone, which is also how an invigilator
  // thinks about when a paper starts.
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
    + `T${pad(date.getHours())}:${pad(date.getMinutes())}`
}

const POLICY_LABELS: { key: keyof DesktopPolicy; label: string; hint: string }[] = [
  { key: 'restrictWindowSwitching', label: 'Keep the examination in front',
    hint: 'Holds the window above everything else on the desktop build.' },
  { key: 'blockNavigation', label: 'Refuse navigation out of the examination',
    hint: 'Links that would leave the examination interface are refused and recorded.' },
  { key: 'blockExternalApps', label: 'Refuse other applications and sites',
    hint: 'Anything the app is asked to open elsewhere is refused.' },
  { key: 'detectLeavingExam', label: 'Notice attempts to leave',
    hint: 'Warns the candidate and records the attempt.' },
  { key: 'detectAppTermination', label: 'Notice attempts to quit the app',
    hint: 'A session that stops reporting shows as having left, either way.' },
  { key: 'clipboardGuard', label: 'Discard clipboard content from outside',
    hint: 'Content that appeared while the window was away is cleared.' },
  { key: 'requireFullscreen', label: 'Require full screen',
    hint: 'The paper stays covered until the candidate enters full screen; leaving it covers '
      + 'the paper again and is recorded like leaving the window.' },
]

function SettingsTab({ detail }: { detail: EventDetail }) {
  const update = useUpdateEvent()
  const { event } = detail
  // Started: the server takes a new end time and name and refuses the rest, so the rest is
  // shown as it is rather than offered as editable and then rejected.
  const locked = detail.settingsLocked

  const [form, setForm] = useState({
    name: event.name,
    description: event.description ?? '',
    rules: detail.rules ?? '',
    externalId: event.externalId,
    startsAt: toLocalInput(event.startsAt),
    endsAt: toLocalInput(event.endsAt),
    lockdownRequired: event.lockdownRequired,
    awayThresholdSeconds: event.awayThresholdSeconds,
    policy: detail.desktopPolicy,
    allowedLanguages: detail.allowedLanguages ?? [],
    personalFilesAllowed: detail.personalFilesAllowed,
  })

  const { data: languageCatalog } = useEventLanguageCatalog()

  const toggleLanguage = (id: string) => setForm(f => ({
    ...f,
    allowedLanguages: f.allowedLanguages.includes(id)
      ? f.allowedLanguages.filter(l => l !== id)
      : [...f.allowedLanguages, id],
  }))

  const save = () => {
    update.mutate({
      eventId: event.eventId,
      body: {
        kind: event.kind,
        platform: event.platform,
        externalId: form.externalId.trim(),
        name: form.name.trim(),
        description: form.description.trim() || null,
        rules: form.rules.trim() || null,
        startsAt: form.startsAt ? new Date(form.startsAt).toISOString() : null,
        endsAt: form.endsAt ? new Date(form.endsAt).toISOString() : null,
        lockdownRequired: form.lockdownRequired,
        awayThresholdSeconds: form.awayThresholdSeconds,
        desktopPolicy: form.policy,
        // Always sent. The server writes this field on every update, so a form that left it
        // out cleared the restriction each time anything else on this screen was saved.
        allowedLanguages: form.allowedLanguages,
        teamId: event.teamId,
        personalFilesAllowed: form.personalFilesAllowed,
      },
    })
  }

  return (
    <div className="space-y-4">
      {locked && (
        <p className="flex items-start gap-2 rounded-lg border border-amber-900 bg-amber-950/40
          px-3 py-2 text-xs leading-relaxed text-amber-200">
          <Lock size={13} className="mt-0.5 flex-shrink-0" />
          <span>
            This has started, so its settings are fixed — candidates are sitting it under them.
            Only the end time and the name can still change. Candidates can still be added on
            the People tab.
          </span>
        </p>
      )}

      <Panel title="The paper" description="What it is called, when it runs, and what it says">
        <div className="grid gap-3 p-4 sm:grid-cols-2">
          <label className="flex flex-col gap-1">
            <span className="text-xs text-gray-500">Name</span>
            <input value={form.name} maxLength={200} className={INPUT}
              onChange={e => setForm({ ...form, name: e.target.value })} />
          </label>
          <label className="flex flex-col gap-1">
            <span className="text-xs text-gray-500">DOMjudge contest id</span>
            <input value={form.externalId} maxLength={100} className={INPUT} disabled={locked}
              onChange={e => setForm({ ...form, externalId: e.target.value })} />
          </label>
          <label className="flex flex-col gap-1">
            <span className="text-xs text-gray-500">Starts</span>
            <input type="datetime-local" value={form.startsAt} className={INPUT}
              disabled={locked}
              onChange={e => setForm({ ...form, startsAt: e.target.value })} />
          </label>
          <label className="flex flex-col gap-1">
            <span className="text-xs text-gray-500">Ends</span>
            <input type="datetime-local" value={form.endsAt} className={INPUT}
              onChange={e => setForm({ ...form, endsAt: e.target.value })} />
          </label>
          <label className="flex flex-col gap-1 sm:col-span-2">
            <span className="text-xs text-gray-500">Description</span>
            <input value={form.description} maxLength={2000} className={INPUT}
              placeholder="Shown on the candidate's list"
              onChange={e => setForm({ ...form, description: e.target.value })} />
          </label>
          <label className="flex flex-col gap-1 sm:col-span-2">
            <span className="text-xs text-gray-500">Rules</span>
            <textarea value={form.rules} maxLength={4000} rows={4} className={INPUT}
              disabled={locked}
              placeholder="Shown to the candidate inside the examination, verbatim"
              onChange={e => setForm({ ...form, rules: e.target.value })} />
          </label>
        </div>
      </Panel>

      {/* A disabled fieldset disables every control inside it, which is the lock in one place
          rather than a `disabled` on each checkbox. */}
      <fieldset disabled={locked} className="space-y-4 disabled:opacity-60">
      <Panel
        title="Private files"
        description="Whether candidates may open their own uploaded notes and templates during it"
      >
        <div className="space-y-2 p-4">
          {([
            [false, 'Not allowed', 'The files panel is hidden and the server refuses reads.'],
            [true, 'Allowed', 'Candidates can open and insert their own uploaded files.'],
          ] as const).map(([value, label, hint]) => (
            <label key={label} className="flex items-start gap-2">
              <input
                type="radio"
                name="personal-files"
                checked={form.personalFilesAllowed === value}
                className="mt-0.5"
                onChange={() => setForm({ ...form, personalFilesAllowed: value })}
              />
              <span className="text-sm text-gray-300">
                {label}
                <span className="block text-xs text-gray-500">{hint}</span>
              </span>
            </label>
          ))}
        </div>
      </Panel>

      <Panel
        title="Languages"
        description="Which languages this paper accepts. Leave everything unticked to take
          whatever the judge takes, which is the default."
      >
        <div className="space-y-3 p-4">
          <div className="grid gap-x-4 gap-y-2 sm:grid-cols-2 lg:grid-cols-3">
            {(languageCatalog ?? []).map(lang => (
              <label key={lang.id} className="flex items-center gap-2">
                <input
                  type="checkbox"
                  checked={form.allowedLanguages.includes(lang.id)}
                  onChange={() => toggleLanguage(lang.id)}
                />
                <span className="text-sm text-gray-300">{lang.label}</span>
              </label>
            ))}
          </div>

          {form.allowedLanguages.length === 0 ? (
            <p className="text-xs leading-relaxed text-gray-600">
              Nothing ticked: candidates may use any language the judge offers for this contest.
            </p>
          ) : (
            <p className="flex items-start gap-2 rounded-lg border border-indigo-900
              bg-indigo-950/40 px-3 py-2 text-xs leading-relaxed text-indigo-200">
              <Code2 size={13} className="mt-0.5 flex-shrink-0" />
              <span>
                Candidates will see only these in the editor, and a submission in anything else
                is refused by the server — as is running one locally, so the rule holds at the
                Run button too and not only at Submit.
                {' '}
                {/*
                  Said plainly because it is the one way this setting bites unexpectedly: the
                  restriction is applied to whatever the judge offers, so a language the judge
                  does not have does not appear however it is ticked here.
                */}
                A language the judge does not offer for this contest stays unavailable whether
                or not it is ticked here.
              </span>
            </p>
          )}
        </div>
      </Panel>

      <Panel
        title="Monitoring"
        description="What is watched, and how long someone may be away before it is recorded"
      >
        <div className="space-y-3 p-4">
          <label className="flex items-start gap-2">
            <input type="checkbox" checked={form.lockdownRequired} className="mt-0.5"
              onChange={e => setForm({ ...form, lockdownRequired: e.target.checked })} />
            <span className="text-sm text-gray-300">
              Monitor this examination
              <span className="block text-xs text-gray-500">
                Candidates are told they are being watched, on the screen where it happens.
                With this off, nothing is recorded and submissions are not gated on a monitor.
              </span>
            </span>
          </label>

          <label className="flex flex-col gap-1 max-w-xs">
            <span className="text-xs text-gray-500">Away threshold (seconds)</span>
            <input
              type="number" min={1} max={3600} value={form.awayThresholdSeconds}
              className={INPUT}
              onChange={e => setForm({
                ...form, awayThresholdSeconds: Number(e.target.value) || 1 })} />
            <span className="text-xs text-gray-600">
              How long someone may be away before they are warned and the absence is recorded.
            </span>
          </label>

          <div className="space-y-2 pt-1">
            <p className="text-xs font-medium text-gray-400">Desktop restrictions</p>
            {POLICY_LABELS.map(({ key, label, hint }) => (
              <label key={key} className="flex items-start gap-2">
                <input
                  type="checkbox"
                  checked={form.policy[key]}
                  className="mt-0.5"
                  onChange={e => setForm({
                    ...form, policy: { ...form.policy, [key]: e.target.checked } })}
                />
                <span className="text-sm text-gray-300">
                  {label}
                  <span className="block text-xs text-gray-500">{hint}</span>
                </span>
              </label>
            ))}
            <p className="flex items-start gap-2 pt-1 text-xs leading-relaxed text-gray-600">
              <AlertTriangle size={12} className="mt-0.5 flex-shrink-0" />
              These are requests to the examination client. The desktop build applies what the
              operating system allows and reports what it could not; a browser applies almost
              none of them, and an examination sat in one records that its monitoring was the
              weaker kind rather than showing a clean sheet. Full screen is the exception: both
              enforce it.
            </p>
          </div>
        </div>
      </Panel>
      </fieldset>

      <button
        onClick={save}
        disabled={update.isPending}
        className="flex items-center gap-1.5 rounded-lg bg-indigo-600 px-3 py-2 text-sm
                   font-medium text-white transition-colors hover:bg-indigo-500
                   disabled:opacity-40"
      >
        {update.isPending ? <Loader2 size={14} className="animate-spin" /> : <Save size={14} />}
        Save
      </button>
    </div>
  )
}

// -------------------------------------------------------------------- people

function PeopleTab({ detail }: { detail: EventDetail }) {
  const { event } = detail
  const { data: teams } = useAdminGroups()
  const { data: users } = useAdminUsers({ size: 100 })
  const assign = useAssignToEvent()
  const unassignTeam = useUnassignTeam()
  const unassignUser = useUnassignUser()

  const [teamId, setTeamId] = useState<number | ''>('')
  const [userId, setUserId] = useState<number | ''>('')

  const availableTeams = useMemo(
    () => (teams ?? []).filter(t => !detail.teams.some(a => a.teamId === t.groupId)),
    [teams, detail.teams])
  const availableUsers = useMemo(
    () => (users?.users ?? []).filter(u => !detail.users.some(a => a.userId === u.userId)),
    [users, detail.users])

  return (
    <div className="space-y-4">
      <Panel
        title="Teams"
        description="Everyone on an assigned team may sit this — membership is read when they open it"
      >
        <ul className="divide-y divide-gray-800">
          {detail.teams.length === 0 && (
            <li className="px-4 py-6 text-center text-sm text-gray-600">No teams assigned.</li>
          )}
          {detail.teams.map(team => (
            <li key={team.teamId} className="flex items-center justify-between px-4 py-2.5">
              <span className="text-sm text-gray-200">
                {team.name}
                <span className="ml-2 text-xs text-gray-500">{team.memberCount} members</span>
              </span>
              {/* Once it has started nobody can be taken off it — the server refuses. */}
              {!detail.settingsLocked && (
                <button
                  onClick={() => unassignTeam.mutate({ eventId: event.eventId, teamId: team.teamId })}
                  className="text-xs text-gray-500 hover:text-red-400"
                >
                  Remove
                </button>
              )}
            </li>
          ))}
        </ul>
        <div className="flex items-end gap-2 border-t border-gray-800 p-4">
          <select
            value={teamId}
            onChange={e => setTeamId(e.target.value === '' ? '' : Number(e.target.value))}
            className={INPUT}
          >
            <option value="">Add a team…</option>
            {availableTeams.map(team => (
              <option key={team.groupId} value={team.groupId}>{team.name}</option>
            ))}
          </select>
          <button
            onClick={() => {
              if (teamId === '') return
              assign.mutate({ eventId: event.eventId, teamIds: [Number(teamId)] },
                { onSuccess: () => setTeamId('') })
            }}
            disabled={teamId === ''}
            className="rounded-lg bg-indigo-600 px-3 py-2 text-sm font-medium text-white
                       transition-colors hover:bg-indigo-500 disabled:opacity-40"
          >
            Assign
          </button>
        </div>
      </Panel>

      <Panel
        title="Individuals"
        description="Named people, whatever team they are on — for a resit, or a candidate on no team"
      >
        <ul className="divide-y divide-gray-800">
          {detail.users.length === 0 && (
            <li className="px-4 py-6 text-center text-sm text-gray-600">
              Nobody named individually.
            </li>
          )}
          {detail.users.map(user => (
            <li key={user.userId} className="flex items-center justify-between px-4 py-2.5">
              <span className="text-sm text-gray-200">
                {user.username}
                {user.fullName && <span className="ml-2 text-xs text-gray-500">{user.fullName}</span>}
                {!user.active && <span className="ml-2"><Pill tone="gray">inactive</Pill></span>}
              </span>
              {/* Once it has started nobody can be taken off it — the server refuses. */}
              {!detail.settingsLocked && (
                <button
                  onClick={() => unassignUser.mutate({ eventId: event.eventId, userId: user.userId })}
                  className="text-xs text-gray-500 hover:text-red-400"
                >
                  Remove
                </button>
              )}
            </li>
          ))}
        </ul>
        <div className="flex items-end gap-2 border-t border-gray-800 p-4">
          <select
            value={userId}
            onChange={e => setUserId(e.target.value === '' ? '' : Number(e.target.value))}
            className={INPUT}
          >
            <option value="">Add a person…</option>
            {availableUsers.map(user => (
              <option key={user.userId} value={user.userId}>
                {user.username} {user.fullName ? `— ${user.fullName}` : ''}
              </option>
            ))}
          </select>
          <button
            onClick={() => {
              if (userId === '') return
              assign.mutate({ eventId: event.eventId, userIds: [Number(userId)] },
                { onSuccess: () => setUserId('') })
            }}
            disabled={userId === ''}
            className="rounded-lg bg-indigo-600 px-3 py-2 text-sm font-medium text-white
                       transition-colors hover:bg-indigo-500 disabled:opacity-40"
          >
            Assign
          </button>
        </div>
      </Panel>

      <p className="px-1 text-xs text-gray-600">
        {event.participantCount} distinct {event.participantCount === 1 ? 'person' : 'people'} may
        sit this. Somebody named both directly and through a team counts once.
      </p>
    </div>
  )
}

// ------------------------------------------------------------------ problems

function ProblemsTab({ detail }: { detail: EventDetail }) {
  const save = useSetProblems()
  const [rows, setRows] = useState<Omit<EventProblem, 'problemId'>[]>(
    detail.problems.length > 0
      ? detail.problems.map(({ problemId: _ignored, ...rest }) => rest)
      : [{ label: 'A', title: '', externalId: '', ordering: 0, points: null }])

  const update = (index: number, patch: Partial<EventProblem>) => {
    setRows(current => current.map((row, i) => i === index ? { ...row, ...patch } : row))
  }

  return (
    <Panel
      title="Problems"
      description="Order and marks live here; statements, tests and verdicts stay on the judge"
      actions={
        <button
          onClick={() => save.mutate({
            eventId: detail.event.eventId,
            problems: rows
              .filter(row => row.label.trim())
              .map((row, index) => ({ ...row, ordering: index })),
          })}
          disabled={save.isPending || detail.settingsLocked}
          title={detail.settingsLocked ? 'It has started, so its problems are fixed' : undefined}
          className="flex items-center gap-1.5 rounded-lg bg-indigo-600 px-3 py-1.5 text-xs
                     font-medium text-white transition-colors hover:bg-indigo-500
                     disabled:opacity-40"
        >
          {save.isPending ? <Loader2 size={12} className="animate-spin" /> : <Save size={12} />}
          Save list
        </button>
      }
    >
      <div className="space-y-2 p-4">
        {rows.map((row, index) => (
          <div key={index} className="flex flex-wrap items-end gap-2">
            <label className="flex w-20 flex-col gap-1">
              <span className="text-xs text-gray-500">Label</span>
              <input value={row.label} maxLength={16} className={INPUT}
                onChange={e => update(index, { label: e.target.value })} />
            </label>
            <label className="flex min-w-[12rem] flex-1 flex-col gap-1">
              <span className="text-xs text-gray-500">Title</span>
              <input value={row.title ?? ''} maxLength={200} className={INPUT}
                onChange={e => update(index, { title: e.target.value })} />
            </label>
            <label className="flex w-36 flex-col gap-1">
              <span className="text-xs text-gray-500">Judge id</span>
              <input value={row.externalId ?? ''} maxLength={100} className={INPUT}
                onChange={e => update(index, { externalId: e.target.value })} />
            </label>
            <label className="flex w-24 flex-col gap-1">
              <span className="text-xs text-gray-500">Marks</span>
              <input type="number" value={row.points ?? ''} className={INPUT}
                onChange={e => update(index, {
                  points: e.target.value === '' ? null : Number(e.target.value) })} />
            </label>
            <button
              onClick={() => setRows(current => current.filter((_, i) => i !== index))}
              className="rounded-lg border border-gray-800 p-2 text-gray-500 hover:text-red-400"
              aria-label="Remove problem"
            >
              <Trash2 size={14} />
            </button>
          </div>
        ))}

        <button
          onClick={() => setRows(current => [...current, {
            label: nextLabel(current.map(r => r.label)),
            title: '', externalId: '', ordering: current.length, points: null,
          }])}
          className="flex items-center gap-1.5 text-xs text-indigo-400 hover:text-indigo-300"
        >
          <Plus size={12} /> Add a problem
        </button>
      </div>
    </Panel>
  )
}

/** A, B, C… — the labels a judge uses, continued from whatever is already listed. */
function nextLabel(existing: string[]): string {
  const letters = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ'
  for (const letter of letters) {
    if (!existing.includes(letter)) return letter
  }
  return `P${existing.length + 1}`
}

// ------------------------------------------------------------------ monitor

function duration(ms: number): string {
  const total = Math.round(ms / 1000)
  if (total < 60) return `${total}s`
  const minutes = Math.floor(total / 60)
  return total % 60 === 0 ? `${minutes}m` : `${minutes}m ${total % 60}s`
}

const STATUS_TONE: Record<string, 'gray' | 'green' | 'amber' | 'red' | 'indigo'> = {
  NOT_STARTED: 'gray',
  ACTIVE:      'green',
  AWAY:        'amber',
  SUBMITTED:   'indigo',
  LEFT:        'red',
}

function MonitorTab({ eventId, live }: { eventId: number; live: boolean }) {
  const { data: snapshot, isLoading } = useExamMonitor(eventId, live)

  if (isLoading || !snapshot) {
    return (
      <div className="flex items-center justify-center gap-2 py-16 text-sm text-gray-500">
        <Loader2 size={14} className="animate-spin" /> Reading the room…
      </div>
    )
  }

  return (
    <div className="space-y-4">
      <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
        <StatCard label="Assigned" value={snapshot.expected} />
        <StatCard label="Present" value={snapshot.present} tone="good" />
        <StatCard label="Away now" value={snapshot.away}
          tone={snapshot.away > 0 ? 'warn' : 'default'} />
        <StatCard label="Not started" value={snapshot.notStarted} />
      </div>

      <Panel
        title="Candidates"
        description={live
          ? 'Refreshed every few seconds — every figure is an observation, not a finding'
          : 'This examination is not running; these are its final figures'}
      >
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-gray-800 text-left text-xs text-gray-500">
                <th className="px-4 py-2 font-medium">Candidate</th>
                <th className="px-4 py-2 font-medium">Status</th>
                <th className="px-4 py-2 font-medium">Focus</th>
                <th className="px-4 py-2 font-medium">Away</th>
                <th className="px-4 py-2 font-medium">Losses</th>
                <th className="px-4 py-2 font-medium">Submissions</th>
                <th className="px-4 py-2 font-medium">On</th>
                <th className="px-4 py-2 font-medium">Last seen</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-800">
              {snapshot.rows.length === 0 && (
                <EmptyRow colSpan={8}>Nobody is assigned to this examination yet.</EmptyRow>
              )}
              {snapshot.rows.map(row => (
                <tr key={row.userId} className="transition-colors hover:bg-gray-800/40">
                  <td className="px-4 py-2.5">
                    <span className="text-gray-200">{row.username}</span>
                    {row.fullName && (
                      <span className="ml-2 text-xs text-gray-600">{row.fullName}</span>
                    )}
                  </td>
                  <td className="px-4 py-2.5">
                    <Pill tone={STATUS_TONE[row.status] ?? 'gray'}>
                      {row.status.toLowerCase().replace('_', ' ')}
                    </Pill>
                  </td>
                  <td className="px-4 py-2.5 text-xs">
                    {row.status === 'NOT_STARTED'
                      ? <span className="text-gray-600">—</span>
                      : row.focused
                        ? <span className="text-green-400">focused</span>
                        : <span className="text-amber-400">away</span>}
                    {!row.monitorAlive && row.status !== 'NOT_STARTED' && (
                      <span className="ml-2 text-gray-600">no monitor</span>
                    )}
                  </td>
                  <td className="px-4 py-2.5 tabular-nums text-gray-400">
                    {row.awayMs > 0 ? duration(row.awayMs) : '—'}
                  </td>
                  <td className="px-4 py-2.5 tabular-nums text-gray-400">{row.focusLosses}</td>
                  <td className="px-4 py-2.5 tabular-nums text-gray-400">{row.submissions}</td>
                  <td className="px-4 py-2.5 text-gray-400">{row.currentProblem ?? '—'}</td>
                  <td className="px-4 py-2.5 text-xs text-gray-500">
                    <Ago at={row.lastActivityAt} fallback="never" />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Panel>

      <p className="flex items-start gap-2 px-1 text-xs leading-relaxed text-gray-600">
        <AlertTriangle size={12} className="mt-0.5 flex-shrink-0" />
        A focus loss is a focus loss. Whether it was a second screen, a notification or somebody
        answering the door is not something this can know, which is why there is no single score
        here to read as a verdict.
      </p>
    </div>
  )
}

// --------------------------------------------------------------------- logs

const TYPE_TONE: Record<string, 'gray' | 'green' | 'amber' | 'red' | 'indigo'> = {
  EXAM_ENTERED: 'indigo',
  EXAM_STARTED: 'indigo',
  PROBLEM_OPENED: 'gray',
  PROBLEM_SUBMITTED: 'green',
  SUBMISSION_RESULT: 'green',
  FOCUS_LOST: 'amber',
  FOCUS_REGAINED: 'gray',
  AWAY_THRESHOLD_EXCEEDED: 'red',
  EXAM_EXITED: 'red',
  EXAM_COMPLETED: 'indigo',
  SESSION_TERMINATED: 'red',
  LOCKDOWN_TRIGGERED: 'amber',
  SUSPICIOUS_ACTIVITY: 'amber',
}

type LogsView = 'students' | 'flags'

/**
 * The session log, read two ways.
 *
 * <p>"Students" is the roster: everyone assigned, with the counts that matter, and a click opens
 * that one person's log. "Suspicious activity" is the short list of things worth a look —
 * long or repeated absences, repeated sign-ins, very fast first submissions — computed by the
 * server from the same log. Either way, a name leads to the full record behind it.
 */
function LogsTab({ eventId, live }: { eventId: number; live: boolean }) {
  const [view, setView] = useState<LogsView>('students')
  const [selected, setSelected] = useState<{ userId: number; username: string } | null>(null)

  const { data: snapshot, isLoading: rosterLoading } = useExamMonitor(eventId, live)
  const { data: report, isLoading: flagsLoading } = useExamFlags(eventId, live)

  const flagCounts = useMemo(() => {
    const counts = new Map<number, { total: number; high: number }>()
    for (const flag of report?.flags ?? []) {
      const entry = counts.get(flag.userId) ?? { total: 0, high: 0 }
      entry.total++
      if (flag.severity === 'HIGH') entry.high++
      counts.set(flag.userId, entry)
    }
    return counts
  }, [report])

  if (selected) {
    return (
      <CandidateLog
        eventId={eventId}
        userId={selected.userId}
        username={selected.username}
        flags={report?.flags.filter(f => f.userId === selected.userId) ?? []}
        onBack={() => setSelected(null)}
      />
    )
  }

  const open = (userId: number, username: string) => setSelected({ userId, username })
  const flagTotal = report?.flags.length ?? 0

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center gap-1 rounded-lg border border-gray-800
        bg-gray-900 p-1 w-fit">
        {([
          { id: 'students' as LogsView, label: 'Students', icon: Users },
          { id: 'flags' as LogsView, label: 'Suspicious activity', icon: Flag },
        ]).map(({ id, label, icon: Icon }) => (
          <button
            key={id}
            onClick={() => setView(id)}
            className={clsx(
              'flex items-center gap-1.5 rounded-md px-3 py-1.5 text-xs font-medium',
              'transition-colors',
              view === id
                ? 'bg-indigo-600/20 text-indigo-300'
                : 'text-gray-500 hover:text-gray-300'
            )}
          >
            <Icon size={13} /> {label}
            {id === 'flags' && flagTotal > 0 && (
              <span className="rounded-full bg-amber-500/20 px-1.5 text-[10px] text-amber-300">
                {flagTotal}
              </span>
            )}
          </button>
        ))}
      </div>

      {view === 'students' && (
        <StudentList
          rows={snapshot?.rows}
          loading={rosterLoading}
          flagCounts={flagCounts}
          onOpen={open}
        />
      )}
      {view === 'flags' && (
        <FlagList report={report} loading={flagsLoading} onOpen={open} />
      )}
    </div>
  )
}

function StudentList({ rows, loading, flagCounts, onOpen }: {
  rows: ExamMonitorRow[] | undefined
  loading: boolean
  flagCounts: Map<number, { total: number; high: number }>
  onOpen: (userId: number, username: string) => void
}) {
  const [search, setSearch] = useState('')
  const needle = search.trim().toLowerCase()
  const shown = (rows ?? []).filter(row => !needle
    || row.username.toLowerCase().includes(needle)
    || (row.fullName ?? '').toLowerCase().includes(needle))

  return (
    <Panel
      title="Students"
      description={rows
        ? `${rows.length} candidate${rows.length === 1 ? '' : 's'} · click one to read their log`
        : 'Everyone assigned to this examination'}
      actions={
        <div className="w-48">
          <input
            value={search}
            onChange={e => setSearch(e.target.value)}
            placeholder="Search by name"
            className={INPUT}
          />
        </div>
      }
    >
      <div className="overflow-x-auto">
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-gray-800 text-left text-xs text-gray-500">
              <th className="px-4 py-2 font-medium">Candidate</th>
              <th className="px-4 py-2 font-medium">Status</th>
              <th className="px-4 py-2 font-medium">Events</th>
              <th className="px-4 py-2 font-medium">Focus losses</th>
              <th className="px-4 py-2 font-medium">Time away</th>
              <th className="px-4 py-2 font-medium">Submissions</th>
              <th className="px-4 py-2 font-medium">Flags</th>
              <th className="px-4 py-2 font-medium">Last seen</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-800">
            {loading && <EmptyRow colSpan={8}>Reading the roster…</EmptyRow>}
            {!loading && shown.length === 0 && (
              <EmptyRow colSpan={8}>
                {needle ? 'Nobody matches that search.' : 'Nobody is assigned to this examination yet.'}
              </EmptyRow>
            )}
            {shown.map(row => {
              const flags = flagCounts.get(row.userId)
              return (
                <tr
                  key={row.userId}
                  onClick={() => onOpen(row.userId, row.username)}
                  className="cursor-pointer transition-colors hover:bg-gray-800/40"
                >
                  <td className="px-4 py-2.5">
                    <span className="text-gray-200">{row.username}</span>
                    {row.fullName && (
                      <span className="ml-2 text-xs text-gray-600">{row.fullName}</span>
                    )}
                  </td>
                  <td className="px-4 py-2.5">
                    <Pill tone={STATUS_TONE[row.status] ?? 'gray'}>
                      {row.status.toLowerCase().replace('_', ' ')}
                    </Pill>
                  </td>
                  <td className="px-4 py-2.5 tabular-nums text-gray-400">{row.events}</td>
                  <td className="px-4 py-2.5 tabular-nums text-gray-400">{row.focusLosses}</td>
                  <td className="px-4 py-2.5 tabular-nums text-gray-400">
                    {row.awayMs > 0 ? duration(row.awayMs) : '—'}
                  </td>
                  <td className="px-4 py-2.5 tabular-nums text-gray-400">{row.submissions}</td>
                  <td className="px-4 py-2.5">
                    {flags
                      ? <Pill tone={flags.high > 0 ? 'red' : 'amber'}>{flags.total}</Pill>
                      : <span className="text-gray-600">—</span>}
                  </td>
                  <td className="px-4 py-2.5 text-xs text-gray-500">
                    <Ago at={row.lastActivityAt} fallback="never" />
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>
    </Panel>
  )
}

const FLAG_LABEL: Record<ExamFlagKind, string> = {
  LONG_AWAY: 'long time away',
  FREQUENT_AWAY: 'away many times',
  MULTIPLE_SIGN_INS: 'multiple sign-ins',
  FAST_SUBMISSION: 'fast submission',
  LOCKDOWN: 'desktop restriction',
  FLAGGED: 'flagged by system',
}

function FlagList({ report, loading, onOpen }: {
  report: ExamFlagReport | undefined
  loading: boolean
  onOpen: (userId: number, username: string) => void
}) {
  const [kind, setKind] = useState<ExamFlagKind | ''>('')
  const shown = (report?.flags ?? []).filter(flag => !kind || flag.kind === kind)

  return (
    <div className="space-y-4">
      <Panel
        title="Suspicious activity"
        description={report
          ? `Away ${report.longAwaySeconds}s+ at once, away ${report.frequentAwayCount}+ times, `
            + `signed in more than once, or a first submission under `
            + `${report.fastSubmissionSeconds}s after opening the problem`
          : 'Things in this examination worth a look'}
        actions={
          <div className="w-48">
            <select value={kind} className={INPUT}
              onChange={e => setKind(e.target.value as ExamFlagKind | '')}>
              <option value="">Every kind</option>
              {(Object.keys(FLAG_LABEL) as ExamFlagKind[]).map(value => (
                <option key={value} value={value}>{FLAG_LABEL[value]}</option>
              ))}
            </select>
          </div>
        }
      >
        <FlagTable flags={shown} loading={loading} onOpen={onOpen} />
      </Panel>

      <p className="flex items-start gap-2 px-1 text-xs leading-relaxed text-gray-600">
        <AlertTriangle size={12} className="mt-0.5 flex-shrink-0" />
        Each row is something worth a look, not a finding. Click a name to read the whole session
        around it before drawing any conclusion.
      </p>
    </div>
  )
}

function FlagTable({ flags, loading, onOpen }: {
  flags: ExamFlag[]
  loading: boolean
  onOpen?: (userId: number, username: string) => void
}) {
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-sm">
        <thead>
          <tr className="border-b border-gray-800 text-left text-xs text-gray-500">
            <th className="px-4 py-2 font-medium">When</th>
            {onOpen && <th className="px-4 py-2 font-medium">Candidate</th>}
            <th className="px-4 py-2 font-medium">What</th>
            <th className="px-4 py-2 font-medium">Problem</th>
            <th className="px-4 py-2 font-medium">Detail</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-gray-800">
          {loading && <EmptyRow colSpan={onOpen ? 5 : 4}>Reading the log…</EmptyRow>}
          {!loading && flags.length === 0 && (
            <EmptyRow colSpan={onOpen ? 5 : 4}>Nothing has crossed a threshold.</EmptyRow>
          )}
          {flags.map((flag, index) => (
            <tr key={`${flag.userId}-${flag.kind}-${flag.occurredAt}-${index}`}
              className="transition-colors hover:bg-gray-800/40">
              <td className="px-4 py-2 text-xs text-gray-500"
                title={flag.occurredAt ? new Date(flag.occurredAt).toLocaleString() : undefined}>
                {flag.occurredAt ? new Date(flag.occurredAt).toLocaleTimeString() : '—'}
              </td>
              {onOpen && (
                <td className="px-4 py-2">
                  <button
                    onClick={() => onOpen(flag.userId, flag.username)}
                    className="text-gray-300 hover:text-indigo-400"
                    title="Read this candidate's log"
                  >
                    {flag.username}
                  </button>
                  {flag.fullName && (
                    <span className="ml-2 text-xs text-gray-600">{flag.fullName}</span>
                  )}
                </td>
              )}
              <td className="px-4 py-2">
                <Pill tone={flag.severity === 'HIGH' ? 'red' : 'amber'}>
                  {FLAG_LABEL[flag.kind] ?? flag.kind.toLowerCase()}
                </Pill>
              </td>
              <td className="px-4 py-2 text-gray-400">{flag.problemLabel ?? '—'}</td>
              <td className="px-4 py-2 text-xs text-gray-500">{flag.detail ?? '—'}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

/** One candidate's whole session, newest first, with their flags above it. */
function CandidateLog({ eventId, userId, username, flags, onBack }: {
  eventId: number
  userId: number
  username: string
  flags: ExamFlag[]
  onBack: () => void
}) {
  const [type, setType] = useState<ExamEventType | ''>('')
  const [page, setPage] = useState(0)

  const { data: logs, isLoading } = useExamLogs(eventId, {
    userId,
    type: type || undefined,
    page,
    size: 100,
  })

  return (
    <div className="space-y-4">
      <button
        onClick={onBack}
        className="flex items-center gap-1.5 text-xs text-gray-400 hover:text-gray-200"
      >
        <ArrowLeft size={13} /> All students
      </button>

      {flags.length > 0 && (
        <Panel
          title="Flagged for a look"
          description={`${flags.length} thing${flags.length === 1 ? '' : 's'} in ${username}'s `
            + 'session crossed a threshold'}
        >
          <FlagTable flags={flags} loading={false} />
        </Panel>
      )}

      <Panel
        title={`${username} — session log`}
        description={logs
          ? `${logs.total} event${logs.total === 1 ? '' : 's'} · kept for ${logs.retentionDays} days`
          : 'Everything recorded for this candidate'}
        actions={
          <div className="w-48">
            <select value={type} className={INPUT}
              onChange={e => { setType(e.target.value as ExamEventType | ''); setPage(0) }}>
              <option value="">Every event</option>
              {logs?.types.map(value => (
                <option key={value} value={value}>
                  {value.toLowerCase().replace(/_/g, ' ')}
                </option>
              ))}
            </select>
          </div>
        }
      >
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-gray-800 text-left text-xs text-gray-500">
                <th className="px-4 py-2 font-medium">When</th>
                <th className="px-4 py-2 font-medium">Event</th>
                <th className="px-4 py-2 font-medium">Problem</th>
                <th className="px-4 py-2 font-medium">Detail</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-800">
              {isLoading && <EmptyRow colSpan={4}>Reading the log…</EmptyRow>}
              {!isLoading && logs?.entries.length === 0 && (
                <EmptyRow colSpan={4}>
                  Nothing recorded for this candidate. Not having sat the examination leaves an
                  empty log, which is not the same as a clean one.
                </EmptyRow>
              )}
              {logs?.entries.map(entry => (
                <tr key={entry.id} className="transition-colors hover:bg-gray-800/40">
                  <td className="px-4 py-2 text-xs text-gray-500"
                    title={new Date(entry.occurredAt).toLocaleString()}>
                    {new Date(entry.occurredAt).toLocaleTimeString()}
                  </td>
                  <td className="px-4 py-2">
                    <Pill tone={TYPE_TONE[entry.type] ?? 'gray'}>
                      {entry.type.toLowerCase().replace(/_/g, ' ')}
                    </Pill>
                  </td>
                  <td className="px-4 py-2 text-gray-400">{entry.problemLabel ?? '—'}</td>
                  <td className="px-4 py-2 text-xs text-gray-500">
                    {entry.detail ?? '—'}
                    {entry.durationMs != null && (
                      <span className="ml-2 tabular-nums text-gray-600">
                        {duration(entry.durationMs)}
                      </span>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>

        {logs && (
          <Pager page={logs.page} totalPages={logs.totalPages} total={logs.total} onPage={setPage} />
        )}
      </Panel>
    </div>
  )
}
