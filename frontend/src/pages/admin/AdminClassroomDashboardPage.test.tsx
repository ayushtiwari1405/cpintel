import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Route, Routes } from 'react-router-dom'
import AdminClassroomDashboardPage from './AdminClassroomDashboardPage'
import AdminStudentDashboardPage from './AdminStudentDashboardPage'
import { fakeServer } from '@/test/server'
import { renderAt, signedIn, user } from '@/test/render'
import type { ClassDashboard, StudentDashboard } from '@/types'

const past = new Date(Date.now() - 3 * 86_400_000).toISOString()
const future = new Date(Date.now() + 3 * 86_400_000).toISOString()

const dashboard: ClassDashboard = {
  classroomId: 3, name: 'DSA 2026', students: 2, generatedAt: past,
  labs: [{
    labId: 50, title: 'Lab 1', opensAt: past, dueAt: past, state: 'CLOSED', problemCount: 2,
    totalMarks: 30, studentCount: 2, averageSolved: 1, averageMarks: 15, finishedAll: 1, solvedNone: 1,
  }],
  events: [
    { eventId: 70, kind: 'EXAM', name: 'Midterm', platform: 'DOMJUDGE', startsAt: past, endsAt: past,
      lifecycle: 'ENDED', assigned: 2, sat: 1, problemCount: 2, averageScore: 55, maxScore: 100,
      averageSolved: 1, focusLosses: 3, boardAt: past },
    { eventId: 71, kind: 'EXAM', name: 'Final', platform: 'DOMJUDGE', startsAt: future, endsAt: future,
      lifecycle: 'SCHEDULED', assigned: 2, sat: 0, problemCount: 0, averageScore: null, maxScore: null,
      averageSolved: null, focusLosses: 0, boardAt: null },
  ],
  roster: [
    { userId: 10, username: 'asha', fullName: 'Asha R', labSolved: 2, labProblems: 2, labMarks: 30,
      labMaxMarks: 30, eventsAssigned: 1, eventsSat: 1, focusLosses: 3, needsLook: false,
      labs: [{ labId: 50, assigned: true, solved: 2, late: 0, problems: 2, marks: 30 }],
      events: [
        { eventId: 70, assigned: true, sat: true, rank: 1, solved: 1, score: 55, focusLosses: 3 },
        { eventId: 71, assigned: true, sat: false, rank: null, solved: null, score: null, focusLosses: 0 },
      ] },
    { userId: 11, username: 'bhavin', fullName: null, labSolved: 0, labProblems: 2, labMarks: 0,
      labMaxMarks: 30, eventsAssigned: 1, eventsSat: 0, focusLosses: 0, needsLook: true,
      labs: [{ labId: 50, assigned: true, solved: 0, late: 0, problems: 2, marks: 0 }],
      events: [
        { eventId: 70, assigned: true, sat: false, rank: null, solved: null, score: null, focusLosses: 0 },
        { eventId: 71, assigned: true, sat: false, rank: null, solved: null, score: null, focusLosses: 0 },
      ] },
  ],
}

const student: StudentDashboard = {
  classroomId: 3, classroomName: 'DSA 2026', userId: 10, username: 'asha', fullName: 'Asha R',
  email: 'asha@cpintel.test', joinedAt: past, teams: ['Section A'],
  handles: { codeforces: 'asha_cf', codeforcesVerified: true, leetcode: null, leetcodeVerified: false,
    cses: null, csesConnected: false },
  labs: [{
    labId: 50, title: 'Lab 1', opensAt: past, dueAt: past, state: 'CLOSED', solved: 1, late: 1,
    marks: 10, totalMarks: 30,
    problems: [
      { problemId: 1, platform: 'CODEFORCES', key: '1A', title: '1A. Theatre Square',
        url: 'https://codeforces.com/problemset/problem/1/A', difficulty: '1000', marks: 10,
        status: 'SOLVED', solvedAt: past },
      { problemId: 2, platform: 'CODEFORCES', key: '1B', title: '1B. Spreadsheets',
        url: 'https://codeforces.com/problemset/problem/1/B', difficulty: '1600', marks: 20,
        status: 'LATE', solvedAt: past },
    ],
  }],
  events: [{
    eventId: 70, kind: 'EXAM', name: 'Midterm', platform: 'DOMJUDGE', startsAt: past, endsAt: past,
    lifecycle: 'ENDED', sat: true, rank: 1, rankedOf: 2, solved: 1, score: 55, maxScore: 100,
    submissions: 4, focusLosses: 3, boardAt: past,
    problems: [
      { label: 'A', maxMarks: 40, solved: true, wrongAttempts: 0, marks: 40 },
      { label: 'B', maxMarks: 60, solved: false, wrongAttempts: 2, marks: 15 },
    ],
  }],
}

beforeEach(() => {
  // jsdom has no object URLs; the download only needs one to hand to a link.
  URL.createObjectURL = vi.fn(() => 'blob:report')
  URL.revokeObjectURL = vi.fn()
})
afterEach(() => vi.restoreAllMocks())

function open(path: string) {
  signedIn(user('ADMIN'))
  const server = fakeServer({
    'GET /admin/classrooms/3/report.xlsx': { data: null },
    'GET /admin/classrooms/3/events/70/report.xlsx': { data: null },
    'GET /admin/classrooms/3/labs/50/marks.xlsx': { data: null },
    'GET /users/me': { data: user('ADMIN') },
    'GET /admin/classrooms/3/dashboard': { data: dashboard },
    'GET /admin/classrooms/3/students/10/dashboard': { data: student },
  })
  renderAt(path, (
    <Routes>
      <Route path="/admin/classrooms/:classroomId/dashboard" element={<AdminClassroomDashboardPage />} />
      <Route path="/admin/classrooms/:classroomId/students/:userId" element={<AdminStudentDashboardPage />} />
    </Routes>
  ))
  return server
}

describe('classroom dashboard', () => {
  it('sums the class and links each student to their own page', async () => {
    open('/admin/classrooms/3/dashboard')

    expect(await screen.findByRole('heading', { name: 'DSA 2026' })).toBeInTheDocument()
    // Two of four lab problems solved; one of two seats taken in started events.
    expect(screen.getAllByText('50%')).toHaveLength(2)
    expect(screen.getByRole('link', { name: 'asha' }))
      .toHaveAttribute('href', '/admin/classrooms/3/students/10')
    expect(screen.getAllByText('absent')).toHaveLength(1)
    expect(screen.getAllByText('upcoming')).toHaveLength(2)
  })

  it('narrows the roster to the students who need a look', async () => {
    open('/admin/classrooms/3/dashboard')
    await screen.findByRole('link', { name: 'asha' })

    await userEvent.click(screen.getByLabelText('Only those needing a look'))

    expect(screen.queryByRole('link', { name: 'asha' })).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'bhavin' })).toBeInTheDocument()
  })
})

describe('classroom exports', () => {
  it('downloads the class report, an event report and a lab\'s marks', async () => {
    const server = open('/admin/classrooms/3/dashboard')
    await screen.findByRole('link', { name: 'asha' })

    await userEvent.click(screen.getByRole('button', { name: /Class report/ }))
    await userEvent.click(screen.getByRole('button', { name: 'Download the report for Midterm' }))
    await userEvent.click(screen.getByRole('button', { name: 'Download marks for Lab 1' }))

    await waitFor(() => expect(URL.createObjectURL).toHaveBeenCalledTimes(3))
    expect(server.calls('GET /admin/classrooms/3/report.xlsx')).toHaveLength(1)
    expect(server.calls('GET /admin/classrooms/3/events/70/report.xlsx')).toHaveLength(1)
    expect(server.calls('GET /admin/classrooms/3/labs/50/marks.xlsx')).toHaveLength(1)
  })
})

describe('student dashboard', () => {
  it('shows every lab problem and every exam question for one student', async () => {
    open('/admin/classrooms/3/students/10')

    expect(await screen.findByRole('heading', { name: 'asha' })).toBeInTheDocument()
    expect(screen.getByText('Section A')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /1A\. Theatre Square/ }))
      .toHaveAttribute('href', 'https://codeforces.com/problemset/problem/1/A')
    expect(screen.getByText(/^Late/)).toBeInTheDocument()

    const questions = screen.getByRole('list', { name: 'Per question' })
    expect(within(questions).getByText('15/60')).toBeInTheDocument()
    expect(screen.getByText('1 of 2')).toBeInTheDocument()
    expect(screen.getByText('Sat it')).toBeInTheDocument()
  })
})
