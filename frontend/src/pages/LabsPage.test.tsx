import { describe, expect, it } from 'vitest'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import LabsPage from './LabsPage'
import { fakeServer } from '@/test/server'
import { renderAt, signedIn, user } from '@/test/render'
import type { MyLab, MyLabs } from '@/types'

const future = new Date(Date.now() + 3 * 86_400_000).toISOString()
const past = new Date(Date.now() - 3 * 86_400_000).toISOString()

function lab(over: Partial<MyLab> = {}): MyLab {
  return {
    labId: 1, classroomId: 10, classroomName: 'DSA 2026', title: 'Lab 3: Two pointers',
    description: null, opensAt: past, dueAt: future, countEarlierSolves: false,
    lastCheckedAt: null, state: 'OPEN', marks: 2, totalMarks: 5,
    problems: [
      { problem: { problemId: 100, platform: 'CODEFORCES', key: '1700/A', title: '1700A. Optimal Path',
          url: 'https://codeforces.com/problemset/problem/1700/A', difficulty: '800', marks: 2 },
        status: 'SOLVED', solvedAt: past },
      { problem: { problemId: 101, platform: 'LEETCODE', key: 'two-sum', title: '1. Two Sum',
          url: 'https://leetcode.com/problems/two-sum/', difficulty: 'Easy', marks: 3 },
        status: null, solvedAt: null },
    ],
    ...over,
  }
}

function open(data: MyLabs, routes: Parameters<typeof fakeServer>[0] = {}) {
  signedIn(user('USER'))
  const server = fakeServer({
    'GET /users/me': { data: user('USER') },
    'GET /labs/mine': { data },
    ...routes,
  })
  renderAt('/labs', <LabsPage />)
  return server
}

describe('labs', () => {
  it('lists each problem with where to solve it, its status and the marks so far', async () => {
    open({ labs: [lab()], codeforces: { handle: 'alice', verified: true }, leetcode: { handle: 'alice', verified: true }, cses: null })

    const card = (await screen.findByText('Lab 3: Two pointers')).closest('article')!
    expect(within(card).getByText('DSA 2026')).toBeInTheDocument()
    expect(within(card).getByRole('link', { name: /1700A\. Optimal Path/ }))
      .toHaveAttribute('href', 'https://codeforces.com/problemset/problem/1700/A')
    expect(within(card).getByLabelText('Solved in time')).toBeInTheDocument()
    expect(within(card).getByLabelText('Not solved')).toBeInTheDocument()
    expect(within(card).getByText('1 of 2 solved in time')).toBeInTheDocument()
    expect(screen.queryByText(/Your solves are not being counted yet/)).not.toBeInTheDocument()
  })

  it('warns when a handle the labs need is missing or unverified', async () => {
    open({ labs: [lab()], codeforces: { handle: 'alice', verified: false }, leetcode: null, cses: null })

    const warning = (await screen.findByText(/Your solves are not being counted yet/)).closest<HTMLElement>('[role="status"]')!
    expect(warning).toHaveTextContent('verify your Codeforces handle (alice)')
    expect(warning).toHaveTextContent('link your LeetCode handle')
    expect(within(warning).getByRole('link', { name: 'Platforms' })).toHaveAttribute('href', '/platforms')
  })

  it('separates past labs from open ones', async () => {
    open({
      labs: [lab({ labId: 2, title: 'Old lab', state: 'CLOSED', dueAt: past }), lab()],
      codeforces: { handle: 'a', verified: true }, leetcode: { handle: 'a', verified: true }, cses: null,
    })
    const pastSection = (await screen.findByRole('heading', { name: 'Past' })).closest('section')!
    expect(within(pastSection).getByText('Old lab')).toBeInTheDocument()
    const openSection = screen.getByRole('heading', { name: 'Open' }).closest('section')!
    expect(within(openSection).queryByText('Old lab')).not.toBeInTheDocument()
  })

  it('checks for new solves on request', async () => {
    const server = open({ labs: [lab()], codeforces: { handle: 'a', verified: true }, leetcode: { handle: 'a', verified: true }, cses: null },
      { 'POST /labs/check': { data: { recorded: 1, checkedAt: new Date().toISOString() } } })

    await userEvent.click(await screen.findByRole('button', { name: /Check for new solves/ }))
    await waitFor(() => expect(server.calls('POST /labs/check')).toHaveLength(1))
    expect(await screen.findByText('Found 1 new solve.')).toBeInTheDocument()
  })

  it('says so when there are no labs', async () => {
    open({ labs: [], codeforces: null, leetcode: null, cses: null })
    expect(await screen.findByText('No labs yet')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Check for new solves/ })).not.toBeInTheDocument()
  })
})
