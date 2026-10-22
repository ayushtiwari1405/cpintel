import { describe, expect, it } from 'vitest'
import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ExamUnlock } from './ExamUnlock'
import { fakeServer, gate } from '@/test/server'
import { renderAt, signedIn, user } from '@/test/render'
import type { MyExam } from '@/types'

/** A paper as GET /exams/{id} returns it before it is unlocked. */
function paper(overrides: { lifecycle?: MyExam['event']['lifecycle']; needsExamPassword?: boolean; needsPasscode?: boolean } = {}): MyExam {
  return {
    event: {
      eventId: 7, kind: 'EXAM', platform: 'DOMJUDGE', externalId: 'mid', judgeContestId: 'mid', classroomId: 1,
      name: 'Data Structures Midterm', description: null, url: null,
      startsAt: '2026-10-22T09:00:00Z', endsAt: '2099-10-22T12:00:00Z', durationSeconds: 10800,
      lifecycle: overrides.lifecycle ?? 'ACTIVE', visibility: 'ASSIGNED', lockdownRequired: false,
      desktopAppRequired: false, awayThresholdSeconds: 30, teamId: null, teamName: null,
      assignedTeams: 0, assignedUsers: 40, participantCount: 0, problemCount: 4,
      standingsRefreshedAt: null, standingsError: null,
    },
    rules: null, problems: [], desktopPolicy: 'ANY', allowedLanguages: ['cpp'],
    secondsUntilStart: 0, secondsRemaining: 3600, entered: false, mySubmissions: 0, myRank: null, mySolved: null,
    requiresPassword: true, unlocked: false,
    needsExamPassword: overrides.needsExamPassword ?? true,
    needsPasscode: overrides.needsPasscode ?? false,
    canReviewSubmissions: false, examSession: false,
  } as unknown as MyExam
}

function open(exam: MyExam, routes: Parameters<typeof fakeServer>[0] = {}) {
  signedIn(user('USER'))
  const server = fakeServer({ 'GET /users/me': { data: user('USER') }, ...routes })
  renderAt('/exams', <ExamUnlock exam={exam} />)
  return server
}

describe('unlocking an examination', () => {
  it('sends the room password, trimmed, for this paper only', async () => {
    const server = open(paper(), { 'POST /exams/7/unlock': { data: paper() } })
    await userEvent.type(screen.getByLabelText('Examination password'), '  ABCD-1234 ')
    await userEvent.click(screen.getByRole('button', { name: 'Open the examination' }))

    const [call] = server.calls('POST /exams/7/unlock')
    expect(call.body).toEqual({ examPassword: 'ABCD-1234' })
  })

  it('a paper with personal codes asks for the code on the slip, and sends that instead', async () => {
    const exam = paper({ needsExamPassword: false, needsPasscode: true })
    const server = open(exam, { 'POST /exams/7/unlock': { data: exam } })
    expect(screen.queryByLabelText('Examination password')).not.toBeInTheDocument()
    expect(screen.getByText('This paper needs the personal code printed on your own slip.')).toBeInTheDocument()

    await userEvent.type(screen.getByLabelText('Your own code'), 'WXYZ-9876{Enter}')
    expect(server.calls('POST /exams/7/unlock')[0].body).toEqual({ passcode: 'WXYZ-9876' })
  })

  it('shows that it is opening while the server answers', async () => {
    const held = gate()
    open(paper(), { 'POST /exams/7/unlock': { wait: held.wait, data: paper() } })
    await userEvent.type(screen.getByLabelText('Examination password'), 'ABCD-1234{Enter}')
    expect(await screen.findByRole('button', { name: /Opening…/ })).toBeDisabled()
    held.open()
  })

  it('a refused password is announced with the server\'s reason', async () => {
    open(paper(), { 'POST /exams/7/unlock': { status: 403, body: { code: 'WRONG_PASSWORD', message: 'That is not this paper\'s password.' } } })
    await userEvent.type(screen.getByLabelText('Examination password'), 'NOPE-0000{Enter}')
    expect(await screen.findByRole('alert')).toHaveTextContent("That is not this paper's password.")
  })

  it('before the paper starts, nothing can be typed or sent', async () => {
    const server = open(paper({ lifecycle: 'SCHEDULED' }))
    expect(screen.getByText('Not open yet')).toBeInTheDocument()
    expect(screen.getByLabelText('Examination password')).toBeDisabled()
    const submit = screen.getByRole('button', { name: 'Open the examination' })
    expect(submit).toBeDisabled()
    await userEvent.click(submit)
    expect(server.calls('POST /exams/7/unlock')).toHaveLength(0)
  })
})
