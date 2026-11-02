import { beforeAll, describe, expect, it } from 'vitest'
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import QuestionBankPage from './QuestionBankPage'
import { batches } from '@/components/questions/QuestionTests'
import { fakeServer } from '@/test/server'
import { renderAt, signedIn, user } from '@/test/render'
import type { BankClassroom, QuestionDetail, QuestionSummary } from '@/types'

type Routes = Parameters<typeof fakeServer>[0]

// jsdom has no object URLs; the iframe only needs some address.
beforeAll(() => {
  URL.createObjectURL = () => 'blob:preview'
  URL.revokeObjectURL = () => {}
})

const ROOM: BankClassroom = { classroomId: 5, name: 'DSA', role: 'TA', active: true }

function question(overrides: Partial<QuestionDetail> = {}): QuestionDetail {
  return {
    questionId: 40, classroomId: 5, title: 'Sum of two', statement: 'Add $a$ and $b$.',
    statementKind: 'MARKDOWN', statementPdf: null,
    timeLimitMs: 1000, memoryLimitMb: 256, checker: 'EXACT', floatTolerance: null,
    modelLanguage: 'cpp', modelSource: 'int main(){}', status: 'DRAFT', version: 3,
    archived: false, createdAt: '2026-11-01T10:00:00Z', createdBy: 'Asha',
    updatedAt: '2026-11-02T10:00:00Z', updatedBy: 'Asha',
    tests: [
      { testId: 1, sample: true, ordinal: 1, name: '1', inputBytes: 4, answerBytes: 2 },
      { testId: 2, sample: false, ordinal: 1, name: 'big', inputBytes: 2048, answerBytes: null },
    ],
    limits: { maxTests: 200, maxQuestionBytes: 64 << 20, maxFileBytes: 4 << 20, maxSampleBytes: 256 << 10 },
    ...overrides,
  }
}

const summary: QuestionSummary = {
  questionId: 40, classroomId: 5, title: 'Sum of two', status: 'DRAFT', samples: 1, secretTests: 1,
  testBytes: 2054, version: 3, archived: false, updatedAt: '2026-11-02T10:00:00Z', updatedBy: 'Asha',
}

function open(path: string, routes: Routes = {}) {
  signedIn(user('USER'))
  const server = fakeServer({
    'GET /users/me': { data: user('USER') },
    'GET /question-bank/classrooms': { data: [ROOM] },
    'GET /question-bank/classrooms/5/questions': { data: [summary] },
    'GET /question-bank/questions/40': { data: question() },
    'POST /question-bank/questions/40/statement/preview': { data: null },
    ...routes,
  })
  renderAt(path, <QuestionBankPage />)
  return server
}

describe('question bank', () => {
  it('lists a classroom\'s questions with their state and tests', async () => {
    open('/questions')
    expect(await screen.findByRole('button', { name: 'Sum of two' })).toBeInTheDocument()
    expect(screen.getByText('You are a TA here')).toBeInTheDocument()
    expect(screen.getByText('draft')).toBeInTheDocument()
    expect(screen.getByText(/1 sample · 1 secret/)).toBeInTheDocument()
  })

  it('tells someone with no classroom that the bank is for admins and TAs', async () => {
    open('/questions', { 'GET /question-bank/classrooms': { data: [] } })
    expect(await screen.findByText('No question banks for you')).toBeInTheDocument()
  })

  it('creates a question from a title and opens it', async () => {
    const server = open('/questions', {
      'POST /question-bank/classrooms/5/questions': { data: question({ title: 'New one' }) },
    })
    await userEvent.click(await screen.findByRole('button', { name: /New question/ }))
    await userEvent.type(screen.getByLabelText('Title'), 'New one')
    await userEvent.click(screen.getByRole('button', { name: 'Create' }))

    await waitFor(() => expect(server.calls('POST /question-bank/classrooms/5/questions')).toHaveLength(1))
    const body = server.calls('POST /question-bank/classrooms/5/questions')[0].body as Record<string, unknown>
    expect(body.title).toBe('New one')
    expect(body.version).toBeUndefined()
  })

  it('saves with the version it loaded, and is disabled until something changes', async () => {
    const server = open('/questions?classroom=5&q=40', {
      'PUT /question-bank/questions/40': ({ body }) => ({ data: question({ ...(body as object), version: 4 }) }),
    })
    const save = await screen.findByRole('button', { name: 'Save' })
    expect(save).toBeDisabled()

    const title = screen.getByLabelText('Title')
    await userEvent.clear(title)
    await userEvent.type(title, 'Sum of three')
    await userEvent.click(save)

    await waitFor(() => expect(server.calls('PUT /question-bank/questions/40')).toHaveLength(1))
    const body = server.calls('PUT /question-bank/questions/40')[0].body as Record<string, unknown>
    expect(body).toMatchObject({ title: 'Sum of three', version: 3, checker: 'EXACT' })
  })

  it('says so when someone else saved first, and keeps the edits', async () => {
    open('/questions?classroom=5&q=40', {
      'PUT /question-bank/questions/40': { status: 409, body: { message: 'Someone else saved it' } },
    })
    const title = await screen.findByLabelText('Title')
    await userEvent.type(title, '!')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    expect(await screen.findByText(/Someone else saved this question after you opened it/)).toBeInTheDocument()
    expect(screen.getByLabelText('Title')).toHaveValue('Sum of two!')
  })

  it('shows tests by group and flags a missing answer', async () => {
    open('/questions?classroom=5&q=40')
    expect(await screen.findByText('Samples (1)')).toBeInTheDocument()
    expect(screen.getByText('Secret tests (1)')).toBeInTheDocument()
    expect(screen.getByText('missing')).toBeInTheDocument()
  })

  it('removes a test only after an in-page confirmation', async () => {
    const server = open('/questions?classroom=5&q=40', {
      'DELETE /question-bank/questions/40/tests/2': { data: question({ version: 4, tests: [question().tests[0]] }) },
    })
    await userEvent.click(await screen.findByRole('button', { name: 'Remove secret test 1' }))
    expect(server.calls('DELETE /question-bank/questions/40/tests/2')).toHaveLength(0)

    await userEvent.click(screen.getByRole('button', { name: 'Remove' }))
    await waitFor(() => expect(server.calls('DELETE /question-bank/questions/40/tests/2')).toHaveLength(1))
    expect(await screen.findByText('Secret tests (0)')).toBeInTheDocument()
  })

  it('a read-only classroom offers no editing', async () => {
    open('/questions?classroom=5&q=40', {
      'GET /question-bank/classrooms': { data: [{ ...ROOM, active: false }] },
    })
    expect(await screen.findByLabelText('Title')).toBeDisabled()
    expect(screen.queryByRole('button', { name: 'Save' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Add tests/ })).not.toBeInTheDocument()
  })
})

describe('statement', () => {
  it('renders the typed statement, unsaved, and lists formulas that could not be drawn', async () => {
    const server = open('/questions?classroom=5&q=40', {
      'POST /question-bank/questions/40/statement/preview': req => ({ data: null, status: 200, ...(req && {}) }),
    })
    const editor = await screen.findByLabelText('Statement')
    await waitFor(() => expect(server.calls('POST /question-bank/questions/40/statement/preview').length).toBeGreaterThan(0), { timeout: 3000 })

    await userEvent.type(editor, ' More.')
    await waitFor(() => {
      const calls = server.calls('POST /question-bank/questions/40/statement/preview')
      expect((calls[calls.length - 1].body as Record<string, unknown>).statement).toBe('Add $a$ and $b$. More.')
    }, { timeout: 3000 })
    expect(await screen.findByTitle('Statement preview')).toBeInTheDocument()
  })

  it('inserts a section where the cursor is', async () => {
    open('/questions?classroom=5&q=40')
    const editor = await screen.findByLabelText('Statement')
    await userEvent.click(screen.getByRole('button', { name: 'Output' }))
    expect((editor as HTMLTextAreaElement).value).toContain('## Output')
  })

  it('a question whose statement is an uploaded PDF shows it, and asks before going back', async () => {
    const pdfQuestion = question({
      statementKind: 'PDF',
      statementPdf: { fileName: 'sum.pdf', sizeBytes: 2048, pages: 2, uploadedAt: '2026-11-02T10:00:00Z', uploadedBy: 'Asha' },
    })
    const server = open('/questions?classroom=5&q=40', {
      'GET /question-bank/questions/40': { data: pdfQuestion },
      'GET /question-bank/questions/40/statement.pdf': { data: null },
      'DELETE /question-bank/questions/40/statement-pdf': { data: question({ version: 4 }) },
    })
    expect(await screen.findByText('sum.pdf')).toBeInTheDocument()
    expect(screen.getByText(/2 pages/)).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Use the written statement' }))
    expect(server.calls('DELETE /question-bank/questions/40/statement-pdf')).toHaveLength(0)
    await userEvent.click(screen.getByRole('button', { name: 'Remove' }))
    await waitFor(() => expect(server.calls('DELETE /question-bank/questions/40/statement-pdf')).toHaveLength(1))
  })

  it('uploads a chosen PDF with the current version', async () => {
    const server = open('/questions?classroom=5&q=40', {
      'PUT /question-bank/questions/40/statement-pdf': { data: question({ version: 4, statementKind: 'PDF' }) },
      'GET /question-bank/questions/40/statement.pdf': { data: null },
    })
    const chooser = await screen.findByLabelText('Choose a statement PDF')
    await userEvent.upload(chooser, new File(['%PDF-1.7'], 'mine.pdf', { type: 'application/pdf' }))

    await waitFor(() => expect(server.calls('PUT /question-bank/questions/40/statement-pdf')).toHaveLength(1))
    const form = server.calls('PUT /question-bank/questions/40/statement-pdf')[0].body as FormData
    expect(form.get('version')).toBe('3')
    expect((form.get('file') as File).name).toBe('mine.pdf')
  })
})

describe('upload batches', () => {
  const file = (name: string, size: number) => new File([new Uint8Array(size)], name)

  it('keeps an input with its answer and splits by the request budget', () => {
    const mb = 1024 * 1024
    const { batches: parts, tooBig } = batches(
      [file('1.in', 3 * mb), file('1.ans', 1 * mb), file('2.in', 3 * mb), file('2.ans', 1 * mb)], 4 * mb)
    expect(tooBig).toEqual([])
    expect(parts.map(p => p.map(f => f.name))).toEqual([['1.in', '1.ans'], ['2.in', '2.ans']])
  })

  it('reports files over the per-file limit instead of sending them', () => {
    const { batches: parts, tooBig } = batches([file('1.in', 10), file('2.in', 99)], 50)
    expect(tooBig.map(f => f.name)).toEqual(['2.in'])
    expect(parts.flat().map(f => f.name)).toEqual(['1.in'])
  })
})
