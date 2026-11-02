import { describe, expect, it } from 'vitest'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ExamQuestionsTab } from './ExamQuestionsTab'
import { fakeServer } from '@/test/server'
import { renderAt, signedIn, user } from '@/test/render'
import type { EventDetail, ExamBankState, ExamChosenQuestion } from '@/types'

const detail = { event: { eventId: 7, classroomId: 5 } } as unknown as EventDetail

function chosen(overrides: Partial<ExamChosenQuestion> = {}): ExamChosenQuestion {
  return {
    questionId: 10, title: 'Sum', status: 'READY', version: 2, archived: false, label: null,
    judgeProblemId: null, publishedVersion: null, publishedAt: null, changedSincePublished: false, ...overrides,
  }
}

function state(overrides: Partial<ExamBankState> = {}): ExamBankState {
  return {
    editable: true, scheduled: true, judgeContestId: 'midterm', publication: null,
    chosen: [chosen()],
    available: [
      { questionId: 10, title: 'Sum', status: 'READY', version: 2 },
      { questionId: 11, title: 'Paths', status: 'READY', version: 1 },
      { questionId: 12, title: 'Draft one', status: 'DRAFT', version: 0 },
    ],
    announcements: [],
    ...overrides,
  }
}

function open(routes: Parameters<typeof fakeServer>[0]) {
  signedIn(user('ADMIN'))
  const server = fakeServer({ 'GET /users/me': { data: user('ADMIN') }, ...routes })
  renderAt('/admin/exams/7', <ExamQuestionsTab detail={detail} />)
  return server
}

describe('exam questions from the bank', () => {
  it('names the judge contest publishing creates, and blocks it while a question is unchecked', async () => {
    open({ 'GET /admin/events/7/bank': { data: state({ chosen: [chosen({ status: 'DRAFT' })] }) } })
    expect(await screen.findByText(/creates contest "midterm"/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Publish to the judge/ })).toBeDisabled()
    expect(screen.getByText(/1 chosen question has not passed a check/)).toBeInTheDocument()
  })

  it('adds a question, saves the order, and publishes', async () => {
    const server = open({
      'GET /admin/events/7/bank': { data: state() },
      'PUT /admin/events/7/bank/questions': { data: state({ chosen: [chosen(), chosen({ questionId: 11, title: 'Paths', version: 1 })] }) },
      'POST /admin/events/7/bank/publish': {
        data: state({ publication: { status: 'PUBLISHED', contestCreated: true, judgeStart: null, judgeEnd: null,
          publishedAt: '2026-11-02T10:00:00Z', publishedBy: 'Admin', note: 'It should hold only this course\'s teams.' } }),
      },
    })
    await userEvent.selectOptions(await screen.findByRole('combobox'), '11')
    await userEvent.click(screen.getByRole('button', { name: /^Add$/ }))
    await userEvent.click(screen.getByRole('button', { name: 'Save the choice' }))
    await waitFor(() => expect(server.calls('PUT /admin/events/7/bank/questions')).toHaveLength(1))
    expect(server.calls('PUT /admin/events/7/bank/questions')[0].body).toEqual({ questionIds: [10, 11] })

    await userEvent.click(screen.getByRole('button', { name: /Publish to the judge/ }))
    expect(await screen.findByText('published')).toBeInTheDocument()
    expect(screen.getByText(/only this course's teams/)).toBeInTheDocument()
  })

  it('during the paper, publishes a checked correction only after confirming, then offers the rejudge', async () => {
    const live = state({
      editable: false,
      chosen: [chosen({ label: 'A', version: 3, publishedVersion: 2, judgeProblemId: 'cpi-e7-q10', changedSincePublished: true })],
    })
    const server = open({
      'GET /admin/events/7/bank': { data: live },
      'POST /admin/events/7/bank/questions/10/republish': { data: { ...live, chosen: [{ ...live.chosen[0], publishedVersion: 3, changedSincePublished: false }] } },
      'POST /admin/events/7/rejudge': { data: {} },
    })
    await userEvent.click(await screen.findByRole('button', { name: /Publish the correction/ }))
    const box = screen.getByRole('group', { name: 'Correct question A' })
    expect(within(box).getByText(/Earlier submissions keep their verdicts/)).toBeInTheDocument()
    expect(server.calls('POST /admin/events/7/bank/questions/10/republish')).toHaveLength(0)

    await userEvent.click(within(box).getByRole('button', { name: /Replace it on the judge/ }))
    await waitFor(() => expect(server.calls('POST /admin/events/7/bank/questions/10/republish')).toHaveLength(1))
    expect(server.calls('POST /admin/events/7/bank/questions/10/republish')[0].body).toEqual({
      confirm: true, notice: 'Question A has been corrected. Your submissions to it will be judged again.',
    })

    await userEvent.click(await screen.findByRole('button', { name: 'Rejudge problem A' }))
    await waitFor(() => expect(server.calls('POST /admin/events/7/rejudge')).toHaveLength(1))
    expect(server.calls('POST /admin/events/7/rejudge')[0].body).toEqual({ label: 'A' })
  })

  it('sends a notice to everyone sitting it', async () => {
    const server = open({
      'GET /admin/events/7/bank': { data: state() },
      'POST /admin/events/7/announcements': { data: [{ id: 1, message: 'Ten minutes left', createdAt: '2026-11-02T10:00:00Z' }] },
    })
    await userEvent.type(await screen.findByLabelText('New notice'), 'Ten minutes left')
    await userEvent.click(screen.getByRole('button', { name: /Send to everyone/ }))
    await waitFor(() => expect(server.calls('POST /admin/events/7/announcements')).toHaveLength(1))
    expect(await screen.findByText('Ten minutes left', { selector: 'li' })).toBeInTheDocument()
  })
})
