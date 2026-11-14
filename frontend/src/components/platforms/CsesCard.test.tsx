import { afterEach, describe, expect, it, vi } from 'vitest'
import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { CsesCard } from './CsesCard'
import { fakeServer } from '@/test/server'
import { renderAt, signedIn, user } from '@/test/render'

afterEach(() => vi.restoreAllMocks())

describe('connecting CSES', () => {
  it('opens the CSES sign-in and connects once it has been approved', async () => {
    signedIn(user('USER'))
    const opened = vi.spyOn(window, 'open').mockReturnValue(null)
    let checks = 0
    fakeServer({
      'GET /users/me': { data: user('USER') },
      'POST /integrations/cses/connect': { data: { authenticationUrl: 'https://cses.fi/api/auth/abc',
        expiresAt: new Date(Date.now() + 900_000).toISOString() } },
      'POST /integrations/cses/connect/check': () => ({ data: ++checks < 2
        ? { connected: false, account: null }
        : { connected: true, account: { accountId: 1, platform: 'CSES', handle: 'alice', syncStatus: 'COMPLETED', verifiedAt: '2026-11-15T00:00:00Z' } } }),
    })
    renderAt('/platforms', <CsesCard account={undefined} />)

    await userEvent.click(screen.getByRole('button', { name: /Connect CSES/ }))
    expect(opened).toHaveBeenCalledWith('https://cses.fi/api/auth/abc', '_blank', 'noopener,noreferrer')
    expect(await screen.findByText(/Sign in on cses.fi to approve CPIntel/)).toBeInTheDocument()
    expect(await screen.findByText('CSES connected as alice', {}, { timeout: 9000 })).toBeInTheDocument()
  }, 15_000)

  it('shows a connected account, and one CSES stopped accepting', () => {
    signedIn(user('USER'))
    fakeServer({ 'GET /users/me': { data: user('USER') } })
    const { unmount } = renderAt('/platforms', <CsesCard account={{ accountId: 1, platform: 'CSES', handle: 'alice',
      syncStatus: 'COMPLETED', verifiedAt: '2026-11-15T00:00:00Z' }} />)
    expect(screen.getByText(/Lab solves on CSES count/)).toBeInTheDocument()
    unmount()
    renderAt('/platforms', <CsesCard account={{ accountId: 1, platform: 'CSES', handle: 'alice',
      syncStatus: 'FAILED', verifiedAt: '2026-11-15T00:00:00Z' }} />)
    expect(screen.getByRole('button', { name: /Reconnect CSES/ })).toBeInTheDocument()
  })
})
