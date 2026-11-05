import { describe, expect, it } from 'vitest'
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { CodeforcesReadiness } from './CodeforcesReadiness'
import { fakeServer } from '@/test/server'
import { renderAt, signedIn, user } from '@/test/render'
import type { CodeforcesReadiness as Readiness } from '@/types'

function readiness(overrides: Partial<Readiness> = {}): Readiness {
  return {
    groupContest: true, keySet: true, contestName: 'Midsem', contestStartsAt: null,
    contestDurationSeconds: 7200, contestProblem: null, windowProblem: null,
    checking: false, checkedAt: null,
    candidates: [
      { userId: 1, username: 'alice', fullName: 'Alice', connected: true, handle: 'alice_cf', canOpen: null },
      { userId: 2, username: 'bob', fullName: null, connected: false, handle: null, canOpen: null },
    ],
    ...overrides,
  }
}

describe('Codeforces readiness', () => {
  it('names who has not connected, and the contest problems in words', async () => {
    signedIn(user('ADMIN'))
    fakeServer({
      'GET /users/me': { data: user('ADMIN') },
      'GET /admin/events/7/codeforces-readiness': {
        data: readiness({ windowProblem: 'The Codeforces contest opens after the paper starts.' }),
      },
    })
    renderAt('/admin/exams/7', <CodeforcesReadiness eventId={7} />)
    expect(await screen.findByText(/opens after the paper starts/)).toBeInTheDocument()
    expect(screen.getByText('not connected')).toBeInTheDocument()
    expect(screen.getByText(/1 not connected/)).toBeInTheDocument()
  })

  it('starts a check and shows who cannot open the contest', async () => {
    signedIn(user('ADMIN'))
    const checked = readiness({
      checkedAt: '2026-11-05T10:00:00Z',
      candidates: [{ userId: 1, username: 'alice', fullName: null, connected: true, handle: 'a', canOpen: false }],
    })
    const server = fakeServer({
      'GET /users/me': { data: user('ADMIN') },
      'GET /admin/events/7/codeforces-readiness': { data: readiness() },
      'POST /admin/events/7/codeforces-readiness': { data: checked },
    })
    renderAt('/admin/exams/7', <CodeforcesReadiness eventId={7} />)
    await userEvent.click(await screen.findByRole('button', { name: /Check candidates/ }))
    await waitFor(() => expect(server.calls('POST /admin/events/7/codeforces-readiness')).toHaveLength(1))
    expect(await screen.findByText('cannot open it')).toBeInTheDocument()
  })
})
