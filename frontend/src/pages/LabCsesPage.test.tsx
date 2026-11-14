import { describe, expect, it, vi } from 'vitest'
import { Route, Routes } from 'react-router-dom'
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import LabCsesPage from './LabCsesPage'
import { fakeServer } from '@/test/server'
import { renderAt, signedIn, user } from '@/test/render'
import type { LabCsesProblem, MyLabs } from '@/types'

vi.mock('@/components/editor/CodeEditor', () => ({
  CodeEditor: ({ value, onChange }: { value: string; onChange: (v: string) => void }) =>
    <textarea aria-label="Code" value={value} onChange={e => onChange(e.target.value)} />,
}))

// Runs are local; the runner is stubbed so the page's handling of a result is what is tested.
const runMock = vi.fn()
vi.mock('@/hooks/useRunner', () => ({
  useRunnerLanguages: () => ({ data: [
    { id: 'cpp', displayName: 'C++ 17', editorLanguage: 'cpp', available: true, unavailableReason: null },
    { id: 'python3', displayName: 'Python 3', editorLanguage: 'python', available: true, unavailableReason: null },
    { id: 'kotlin', displayName: 'Kotlin', editorLanguage: 'kotlin', available: true, unavailableReason: null },
  ] }),
  useRunCode: () => ({ mutate: runMock, isPending: false, progress: null }),
}))

const problem: LabCsesProblem = {
  labId: 1, problemId: 9, labTitle: 'Lab 4', taskId: '1640', title: 'Sum of Two Values',
  timeLimit: '1.00 s', memoryLimit: '512 MB',
  contentHtml: '<p>Find two values whose sum is $$$x$$$.</p><img src="x" onerror="alert(1)">',
  examples: [{ input: '4 8\n2 7 5 1\n', output: '2 4\n' }],
  url: 'https://cses.fi/problemset/task/1640/', marks: 3, status: null, solvedAt: null,
  dueAt: new Date(Date.now() + 86_400_000).toISOString(),
}

const labs = (cses: MyLabs['cses']): MyLabs => ({ labs: [], codeforces: null, leetcode: null, cses })

function open(mine: MyLabs, routes: Parameters<typeof fakeServer>[0] = {}) {
  signedIn(user('USER'))
  const server = fakeServer({
    'GET /users/me': { data: user('USER') },
    'GET /labs/problems/9/cses': { data: problem },
    'GET /labs/mine': { data: mine },
    ...routes,
  })
  renderAt('/labs/cses/9', <Routes><Route path="/labs/cses/:problemId" element={<LabCsesPage />} /></Routes>)
  return server
}

describe('solving a CSES lab task', () => {
  it('shows the statement safely, with only the languages CSES takes from here', async () => {
    open(labs({ handle: 'alice', verified: true }))
    expect(await screen.findByRole('heading', { name: 'Sum of Two Values' })).toBeInTheDocument()
    expect(screen.getByText(/Find two values/)).toBeInTheDocument()
    expect(document.querySelector('[onerror]')).toBeNull()
    const options = [...(screen.getByLabelText('Language') as HTMLSelectElement).options].map(o => o.value)
    expect(options).toEqual(['cpp', 'python3'])
  })

  it('runs on the examples locally', async () => {
    runMock.mockReset()
    open(labs(null))
    await userEvent.click(await screen.findByRole('button', { name: /Run/ }))
    expect(runMock.mock.calls[0][0]).toMatchObject({
      language: 'cpp',
      tests: [{ input: '4 8\n2 7 5 1\n', expected: '2 4\n', label: 'Example 1' }],
    })
  })

  it('without a connected CSES account, does not offer submit', async () => {
    open(labs(null))
    expect(await screen.findByText(/Connect your CSES account to submit from here/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Submit/ })).toBeDisabled()
  })

  it('asks to reconnect when CSES stopped accepting the connection', async () => {
    open(labs({ handle: 'alice', verified: false }))
    expect(await screen.findByText(/no longer accepts your connection/)).toBeInTheDocument()
  })

  it('submits through the server and follows the verdict until it is graded', async () => {
    let polls = 0
    const server = open(labs({ handle: 'alice', verified: true }), {
      'POST /labs/problems/9/cses/submissions': { data: {
        submissionId: 55, pending: true, status: 'PENDING', result: null, finishedTests: 0, totalTests: 20,
        compiler: null, tests: [] } },
      'GET /labs/problems/9/cses/submissions/55': () => ({ data: ++polls < 2
        ? { submissionId: 55, pending: true, status: 'TESTING', result: null, finishedTests: 7, totalTests: 20, compiler: null, tests: [] }
        : { submissionId: 55, pending: false, status: 'READY', result: 'ACCEPTED', finishedTests: 20, totalTests: 20,
            compiler: null, tests: [{ number: 1, verdict: 'ACCEPTED', timeMs: 10 }] } }),
    })

    await userEvent.click(await screen.findByRole('button', { name: /Submit/ }))

    expect(await screen.findByText('Accepted', {}, { timeout: 8000 })).toBeInTheDocument()
    expect(server.calls('POST /labs/problems/9/cses/submissions')[0].body).toMatchObject({ language: 'cpp' })
    await waitFor(() => expect(server.calls('GET /labs/problems/9/cses/submissions/55').length).toBeGreaterThanOrEqual(2))
    expect(await screen.findByText('Accepted, and counted for the lab.')).toBeInTheDocument()
  }, 15_000)
})
