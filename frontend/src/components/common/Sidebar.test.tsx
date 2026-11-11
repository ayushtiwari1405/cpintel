import { describe, expect, it } from 'vitest'
import { screen, waitFor, within } from '@testing-library/react'
import { Sidebar } from './Sidebar'
import { fakeServer } from '@/test/server'
import { renderAt, signedIn, user } from '@/test/render'
import type { Role } from '@/types'

/** The sidebar as a wide screen shows it, labels on. */
function renderSidebar(props: Partial<Parameters<typeof Sidebar>[0]> = {}) {
  return renderAt('/dashboard', <Sidebar open wide onToggle={() => {}} drawerOpen={false} onCloseDrawer={() => {}} {...props} />)
}

const nav = () => screen.getByRole('complementary', { name: 'Main navigation' })

/** Every link the sidebar offers, as "label -> href". */
function links() {
  return within(nav()).getAllByRole('link').map(a => `${a.textContent?.trim()} -> ${a.getAttribute('href')}`)
}

function as(role: Role, taExams: unknown[] = [], banks: unknown[] = []) {
  const server = fakeServer({
    'GET /users/me': { data: user(role) },
    'GET /evaluation/exams': { data: taExams },
    'GET /question-bank/classrooms': { data: banks },
  })
  signedIn(user(role))
  return server
}

const STUDENT = [
  'Dashboard -> /dashboard', 'Analytics -> /analytics', 'Roadmap -> /roadmap', 'Practice plan -> /recommendations',
  'Practice -> /practice', 'Compete -> /compete', 'Exams -> /exams', 'Visualiser -> /visualise', 'Teams -> /teams',
  'Platforms -> /platforms', 'Profile -> /profile', 'Desktop app -> /download',
]
const CONSOLE = [
  'Overview -> /admin', 'Users -> /admin/users', 'Classrooms -> /admin/classrooms', 'Teams -> /admin/teams',
  'Examinations -> /admin/exams', 'Question bank -> /questions', 'Audit trail -> /admin/audit', 'Contest files -> /admin/contest-files',
  'Visualiser -> /visualise', 'Profile -> /profile',
]

describe('sidebar navigation by role', () => {
  it('a student sees the student pages, the practice plan among them, and nothing of the console', async () => {
    as('USER')
    renderSidebar()
    await waitFor(() => expect(links()).toEqual(STUDENT))
    expect(links().some(l => l.includes('/admin'))).toBe(false)
  })

  it('a teaching assistant also gets the marking page', async () => {
    as('USER', [{ eventId: 3, name: 'Midterm' }])
    renderSidebar()
    await waitFor(() => expect(links()).toContain('Evaluate -> /evaluate'))
  })

  it('a teaching assistant gets the question bank of the classrooms they TA in', async () => {
    as('USER', [], [{ classroomId: 5, name: 'DSA', role: 'TA', active: true }])
    renderSidebar()
    await waitFor(() => expect(links()).toContain('Question bank -> /questions'))
    expect(links()).not.toContain('Evaluate -> /evaluate')
  })

  it('a student with no classroom to write questions for is not offered the bank', async () => {
    const server = as('USER')
    renderSidebar()
    await waitFor(() => expect(server.calls('GET /question-bank/classrooms')).toHaveLength(1))
    expect(links()).not.toContain('Question bank -> /questions')
  })

  it('a student who marks nothing is not offered the marking page', async () => {
    const server = as('USER', [])
    renderSidebar()
    await waitFor(() => expect(server.calls('GET /evaluation/exams')).toHaveLength(1))
    expect(links()).not.toContain('Evaluate -> /evaluate')
  })

  it('inside the desktop app there is no link to download it', async () => {
    window.cpintelDesktop = { isDesktop: true } as typeof window.cpintelDesktop
    try {
      as('USER')
      renderSidebar()
      await waitFor(() => expect(links()).toEqual(STUDENT.filter(l => !l.startsWith('Desktop app'))))
    } finally {
      delete window.cpintelDesktop
    }
  })

  it.each([['ADMIN', 'Console'], ['SUPER_ADMIN', 'Super admin']] as const)(
    'an %s gets the console under "%s", their profile, and no student pages', async (role, heading) => {
      const server = as(role)
      renderSidebar()
      expect(links()).toEqual(CONSOLE)
      expect(within(within(nav()).getByRole('navigation')).getByText(heading)).toBeInTheDocument()
      expect(links().some(l => l.startsWith('Practice plan'))).toBe(false)
      // Admins are not teaching assistants here; the marking list is not even asked for.
      expect(server.calls('GET /evaluation/exams')).toHaveLength(0)
      expect(server.calls('GET /question-bank/classrooms')).toHaveLength(0)
    })

  it('an examination session is offered nothing but its paper', () => {
    as('USER')
    renderSidebar({ examMode: true })
    expect(within(nav()).queryAllByRole('link')).toHaveLength(0)
    expect(nav()).toHaveTextContent('Examination mode — only this paper is available.')
  })

  it('while an examination is live, the links stay visible but out of reach', async () => {
    as('USER')
    renderSidebar({ locked: true })
    await waitFor(() => expect(links()).toEqual(STUDENT))
    for (const link of within(nav()).getAllByRole('link')) expect(link).toHaveAttribute('tabindex', '-1')
  })
})
