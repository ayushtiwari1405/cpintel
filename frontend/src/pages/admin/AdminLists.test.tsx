import { describe, expect, it } from 'vitest'
import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import AdminClassroomsPage from './AdminClassroomsPage'
import AdminGroupsPage from './AdminGroupsPage'
import AdminUsersPage from './AdminUsersPage'
import { fakeServer } from '@/test/server'
import { renderAt, signedIn, user } from '@/test/render'

const failed = { status: 500, body: { message: 'An unexpected error occurred' } }

describe.each([
  ['Classrooms', AdminClassroomsPage, 'GET /admin/classrooms', 'Could not load the classrooms', /No classrooms yet|not in a classroom/],
  ['Teams', AdminGroupsPage, 'GET /admin/groups', 'Could not load the teams', /No teams yet/],
  ['Users', AdminUsersPage, 'GET /admin/users', 'Could not load the accounts', /No accounts yet/],
])('%s, when the list cannot be loaded', (_name, Page, route, title, emptyMessage) => {
  it('says so with a retry, instead of looking empty', async () => {
    signedIn(user('ADMIN'))
    const server = fakeServer({ 'GET /users/me': { data: user('ADMIN') }, [route]: failed })
    renderAt('/admin', <Page />)

    expect(await screen.findByText(title)).toBeInTheDocument()
    expect(screen.queryByText(emptyMessage)).not.toBeInTheDocument()

    const before = server.calls(route).length
    await userEvent.click(screen.getByRole('button', { name: 'Retry' }))
    await expect.poll(() => server.calls(route).length).toBeGreaterThan(before)
  })
})
