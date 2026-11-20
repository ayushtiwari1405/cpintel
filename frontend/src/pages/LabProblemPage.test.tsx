import { afterEach, describe, expect, it, vi } from 'vitest'
import { Route, Routes } from 'react-router-dom'
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import LabProblemPage, { lcChipVerdict, lcRunResponse } from './LabProblemPage'
import { fakeServer } from '@/test/server'
import { renderAt, signedIn, user } from '@/test/render'
import type { LabLeetCodeProblem, MyLabs } from '@/types'

// Monaco does not run in jsdom; a textarea stands in for it.
vi.mock('@/components/editor/CodeEditor', () => ({
  CodeEditor: ({ value, onChange }: { value: string; onChange: (v: string) => void }) =>
    <textarea aria-label="Code" value={value} onChange={e => onChange(e.target.value)} />,
}))

const problem: LabLeetCodeProblem = {
  labId: 1, problemId: 5, labTitle: 'Lab 3', questionId: '1', slug: 'two-sum', title: '1. Two Sum',
  difficulty: 'Easy', paidOnly: false,
  contentHtml: '<p>Given <code>nums</code>.</p><img src="x" onerror="window.__pwned=1">',
  snippets: [
    { lang: 'C++', langSlug: 'cpp', code: 'class Solution { /* cpp */ };' },
    { lang: 'Python3', langSlug: 'python3', code: 'class Solution:  # py' },
  ],
  exampleTestcases: ['[2,7,11,15]\n9'], url: 'https://leetcode.com/problems/two-sum/', marks: 2,
  status: null, solvedAt: null, dueAt: new Date(Date.now() + 86_400_000).toISOString(),
}

const labs = (handle: string | null, verified = true): MyLabs =>
  ({ labs: [], codeforces: null, leetcode: handle ? { handle, verified } : null, cses: null })

function open(mine: MyLabs, routes: Parameters<typeof fakeServer>[0] = {}) {
  signedIn(user('USER'))
  const server = fakeServer({
    'GET /users/me': { data: user('USER') },
    'GET /labs/problems/5/leetcode': { data: problem },
    'GET /labs/mine': { data: mine },
    ...routes,
  })
  renderAt('/labs/problems/5', <Routes><Route path="/labs/problems/:problemId" element={<LabProblemPage />} /></Routes>)
  return server
}

function desktop(username: string | null, extra: Record<string, unknown> = {}) {
  const call = vi.fn(async (op: string) => {
    if (op === 'status') return { result: { signedIn: !!username, username } }
    if (op in extra) return { result: extra[op] }
    return { error: `unexpected ${op}` }
  })
  ;(window as any).cpintelDesktop = { lc: { call, signIn: vi.fn(), forget: vi.fn() } }
  return call
}

afterEach(() => {
  delete (window as any).cpintelDesktop
  try { localStorage.clear() } catch { /* none */ }
})

describe('solving a LeetCode lab problem', () => {
  it('shows the statement without anything that could run, and the starter code', async () => {
    open(labs('alice'))
    expect(await screen.findByText('1. Two Sum')).toBeInTheDocument()
    expect(screen.getByText('nums')).toBeInTheDocument()
    expect(document.querySelector('[onerror]')).toBeNull()
    expect(screen.getByLabelText('Code')).toHaveValue('class Solution { /* cpp */ };')
    // Each example is a case in the console, as in Practice; LeetCode supplies the expected answer.
    expect(screen.getByPlaceholderText('One argument per line, as on LeetCode')).toHaveValue('[2,7,11,15]\n9')
    expect(screen.queryByText('Expected output')).toBeNull()
  })

  it('keeps a draft per language', async () => {
    open(labs('alice'))
    const code = await screen.findByLabelText('Code')
    await userEvent.clear(code)
    await userEvent.type(code, 'my cpp')
    await userEvent.selectOptions(screen.getByLabelText('Language'), 'python3')
    expect(screen.getByLabelText('Code')).toHaveValue('class Solution:  # py')
    await userEvent.selectOptions(screen.getByLabelText('Language'), 'cpp')
    expect(screen.getByLabelText('Code')).toHaveValue('my cpp')
  })

  it('without the app or extension, says how to get them and does not offer submit', async () => {
    open(labs('alice'))
    expect(await screen.findByText(/with the desktop app or browser extension/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Submit/ })).toBeDisabled()
  })

  it('warns when the LeetCode account signed in is not the linked handle', async () => {
    desktop('bob')
    open(labs('alice'))
    expect(await screen.findByText(/so solves from this account will not count/)).toBeInTheDocument()
  })

  it('submits through the app and asks for the lab to be checked when accepted', async () => {
    const call = desktop('alice', {
      submit: { id: '987' },
      check: { state: 'SUCCESS', status_msg: 'Accepted', total_correct: 63, total_testcases: 63,
        status_runtime: '4 ms', status_memory: '12 MB' },
    })
    const server = open(labs('alice'), { 'POST /labs/check': { data: { recorded: 1, checkedAt: '2026-11-15T00:00:00Z' } } })

    // Signed in as the linked handle: nothing to say, so no notice, and Submit is open.
    await waitFor(() => expect(screen.getByRole('button', { name: /Submit/ })).toBeEnabled())
    expect(screen.queryByText(/will not count/)).toBeNull()
    await userEvent.click(screen.getByRole('button', { name: /Submit/ }))

    expect(await screen.findByText('63 / 63 tests', {}, { timeout: 5000 })).toBeInTheDocument()
    expect(screen.getByText('Runtime 4 ms · Memory 12 MB')).toBeInTheDocument()
    expect(call).toHaveBeenCalledWith('submit', { slug: 'two-sum', questionId: '1', lang: 'cpp',
      code: 'class Solution { /* cpp */ };' })
    await waitFor(() => expect(server.calls('POST /labs/check')).toHaveLength(1))
    expect(await screen.findByText('Accepted, and counted for the lab.')).toBeInTheDocument()
  })

  it('runs every case on LeetCode in one go and marks each against LeetCode\'s answer', async () => {
    const call = desktop('alice', {
      run: { id: 'r1' },
      check: { state: 'SUCCESS', status_msg: 'Accepted', run_success: true, status_runtime: '0 ms',
        code_answer: ['[0,1]', '[1,0]'], expected_code_answer: ['[0,1]', '[0,1]'], std_output_list: ['hi', ''] },
    })
    open(labs('alice'))
    await waitFor(() => expect(screen.getByRole('button', { name: /Run/ })).toBeEnabled())
    await userEvent.click(screen.getByTitle('Add a case of your own'))
    await userEvent.type(screen.getByPlaceholderText('One argument per line, as on LeetCode'), '[[3,3]{enter}6')
    await userEvent.click(screen.getByRole('button', { name: /Run/ }))

    expect(await screen.findByText('1/2 cases passed')).toBeInTheDocument()
    const runCall = call.mock.calls.find(c => c[0] === 'run') as unknown[] | undefined
    expect(runCall?.[1]).toMatchObject({ input: '[2,7,11,15]\n9\n[3,3]\n6' })
  })
})

describe('lcRunResponse', () => {
  const tests = [
    { input: 'a', expected: null, label: 'Case 1' },
    { input: 'b', expected: null, label: 'Case 2' },
  ]
  it('reports a compile error as one, with no cases', () => {
    const r = lcRunResponse({ state: 'SUCCESS', status_msg: 'Compile Error', full_compile_error: 'line 1: oops' }, tests)
    expect(r).toMatchObject({ compiled: false, compileOutput: 'line 1: oops', results: [] })
  })
  it('marks the case that crashed and leaves out the ones after it', () => {
    const r = lcRunResponse({ state: 'SUCCESS', status_msg: 'Runtime Error', run_success: false,
      runtime_error: 'index out of range', code_answer: [] }, tests)
    expect(r.results).toHaveLength(1)
    expect(r.results[0]).toMatchObject({ label: 'Case 1', verdict: 'RUNTIME_ERROR', stderr: 'index out of range' })
  })
})

describe('lcChipVerdict', () => {
  it('names the test that failed', () => {
    expect(lcChipVerdict({ state: 'SUCCESS', status_msg: 'Wrong Answer', total_correct: 4, total_testcases: 9 }, '7'))
      .toEqual({ submissionId: '7', verdict: 'WRONG_ANSWER', passedTestCount: 4, finished: true })
  })
})
