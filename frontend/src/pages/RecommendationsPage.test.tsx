import { describe, expect, it } from 'vitest'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useLocation } from 'react-router-dom'
import RecommendationsPage from './RecommendationsPage'
import { fakeServer, gate, type Reply } from '@/test/server'
import { renderAt, signedIn, user } from '@/test/render'
import { item, plan, problem, revision } from '@/test/fixtures/plans'

function Where() {
  const { search } = useLocation()
  return <output aria-label="search">{search}</output>
}

type Routes = Parameters<typeof fakeServer>[0]

/** The page at `path` for a signed-in student, with the plan endpoints answering from `routes`. */
function open(path: string, routes: Routes) {
  signedIn(user('USER'))
  const server = fakeServer({
    'GET /users/me': { data: user('USER') },
    'GET /recommendations/revision': { data: [] },
    ...routes,
  })
  renderAt(path, <><RecommendationsPage /><Where /></>)
  return server
}

const dailyItems = [
  item('dp-basics', 'DP Basics (1D)', {
    problems: [problem(1900, 'B', 1100, 'WARM_UP', true), problem(1901, 'C', 1200, 'CORE'),
      problem(1902, 'C', 1300, 'CORE'), problem(1903, 'D', 1400, 'STRETCH')],
  }),
  item('primes-sieve', 'Primes & the Sieve', {
    reasonCode: 'REVISION_DUE', reason: 'Revision due - faded from 70% to 20% mastery',
    problems: [problem(1910, 'A', 1200, 'CORE'), problem(1911, 'B', 1300, 'STRETCH')],
  }),
]

describe('today', () => {
  it('shows the loading state while the sheet is built', async () => {
    const held = gate()
    open('/recommendations', { 'GET /recommendations/daily': { wait: held.wait, data: plan('DAILY', dailyItems) } })

    expect((await screen.findByText("Building today's sheet…")).closest('[role="status"]')).toBeInTheDocument()
    held.open()
    expect(await screen.findByText('DP Basics (1D)')).toBeInTheDocument()
    expect(screen.queryByText("Building today's sheet…")).not.toBeInTheDocument()
  })

  it('lists each skill with its reason, problems easiest first, and links into the workspace', async () => {
    open('/recommendations', { 'GET /recommendations/daily': { data: plan('DAILY', dailyItems) } })

    const skills = await screen.findAllByRole('listitem')
    const first = skills.find(li => li.textContent?.includes('DP Basics (1D)'))!
    expect(within(first).getByText('Weak area')).toBeInTheDocument()
    expect(within(first).getByText('aim ~1400')).toBeInTheDocument()
    expect(within(first).getByText('Weak area - mastery 25% over 6 attempted problems')).toBeInTheDocument()
    expect(within(first).getByRole('link', { name: /Open skill/ })).toHaveAttribute('href', '/practice?node=dp-basics')

    const problemLinks = within(first).getAllByRole('link')
      .filter(a => a.getAttribute('href')?.startsWith('/practice?platform='))
    expect(problemLinks.map(a => a.textContent)).toEqual(['Problem 1900B', 'Problem 1901C', 'Problem 1902C', 'Problem 1903D'])
    expect(problemLinks[1]).toHaveAttribute('href', '/practice?platform=CODEFORCES&contest=1901&index=C&node=dp-basics')
    expect(within(first).getByText('warm-up')).toBeInTheDocument()
    expect(within(first).getByText('stretch')).toBeInTheDocument()
    // A solved problem is marked as such and struck through.
    expect(within(first).getByLabelText('solved')).toBeInTheDocument()

    const second = skills.find(li => li.textContent?.includes('Primes & the Sieve'))!
    expect(within(second).getByText('Revision')).toBeInTheDocument()
  })

  it('shows progress on the bar and on the tab', async () => {
    open('/recommendations', { 'GET /recommendations/daily': { data: plan('DAILY', dailyItems) } })

    const bar = await screen.findByRole('progressbar')
    expect(bar).toHaveAttribute('aria-valuenow', '1')
    expect(bar).toHaveAttribute('aria-valuemax', '6')
    expect(bar).toHaveAccessibleName('1 of 6 solved today')
    expect(screen.getByRole('tab', { name: /Today/ })).toHaveTextContent('1/6')
  })

  it('with nothing to plan from, says why and offers to link Codeforces', async () => {
    open('/recommendations', {
      'GET /recommendations/daily': { data: plan('DAILY', [], { summary: { basis: 'NONE', notes: [
        'Nothing to plan from yet. Link and sync your Codeforces account, and your roadmap\'s first skills will appear here.',
      ] } }) },
    })

    expect(await screen.findByText('Nothing to plan from yet')).toBeInTheDocument()
    expect(screen.getByText(/Link and sync your Codeforces account/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Link Codeforces' })).toHaveAttribute('href', '/platforms')
    expect(screen.queryByRole('progressbar')).not.toBeInTheDocument()
  })

  it('a partly inferred sheet explains itself and labels the roadmap skills', async () => {
    const note = 'Skills marked "from your roadmap" have fewer than 3 attempted problems, so they come from your roadmap\'s next steps rather than from measured weakness.'
    open('/recommendations', {
      'GET /recommendations/daily': { data: plan('DAILY', [
        dailyItems[0],
        item('io-basics', 'Input, Output & Complexity', {
          reasonCode: 'NEXT_ON_ROADMAP', basis: 'INFERRED',
          reason: 'Next on your roadmap - not enough attempts here yet to measure it',
          problems: [problem(1920, 'A', 800, 'WARM_UP')],
        }),
      ], { summary: { basis: 'PARTIAL', notes: [note] } }) },
    })

    expect(await screen.findByText(note)).toBeInTheDocument()
    expect(screen.getByText('From your roadmap')).toBeInTheDocument()
  })

  it('a failed request says so and recovers on retry', async () => {
    let calls = 0
    open('/recommendations', {
      'GET /recommendations/daily': () => (++calls === 1
        ? { status: 500, body: { code: 'INTERNAL_ERROR', message: 'An unexpected error occurred' } }
        : { data: plan('DAILY', dailyItems) }),
    })

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent("Could not load today's sheet")
    expect(alert).toHaveTextContent('An unexpected error occurred')

    await userEvent.click(within(alert).getByRole('button', { name: 'Retry' }))
    expect(await screen.findByText('DP Basics (1D)')).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(calls).toBe(2)
  })

  it('once everything is solved, offers another sheet and asks for one', async () => {
    const done = [item('dp-basics', 'DP Basics (1D)', { problems: [problem(1900, 'B', 1100, 'CORE', true)] })]
    const fresh = [item('primes-sieve', 'Primes & the Sieve')]
    let current = plan('DAILY', done)
    const server = open('/recommendations', {
      'GET /recommendations/daily': () => ({ data: current }),
      'POST /recommendations/daily/regenerate': () => { current = plan('DAILY', fresh); return { data: current } },
    })

    expect(await screen.findByText('Everything on this plan is solved.')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Give me another' }))

    expect(await screen.findByText('Primes & the Sieve')).toBeInTheDocument()
    expect(server.calls('POST /recommendations/daily/regenerate')).toHaveLength(1)
  })

  it('"New plan" rebuilds the plan of the tab that is open', async () => {
    const server = open('/recommendations?tab=week', {
      'GET /recommendations/daily': { data: plan('DAILY', dailyItems) },
      'GET /recommendations/weekly': { data: plan('WEEKLY', dailyItems) },
      'POST /recommendations/weekly/regenerate': { data: plan('WEEKLY', dailyItems) },
    })
    await screen.findByText(/solved this week/)
    await userEvent.click(screen.getByRole('button', { name: 'New plan' }))

    await waitFor(() => expect(server.calls('POST /recommendations/weekly/regenerate')).toHaveLength(1))
    expect(server.calls('POST /recommendations/daily/regenerate')).toHaveLength(0)
  })

  it('a new plan the server cannot build says so and keeps the sheet that was there', async () => {
    const server = open('/recommendations', {
      'GET /recommendations/daily': { data: plan('DAILY', dailyItems) },
      'POST /recommendations/daily/regenerate': { status: 503, body: { code: 'UNAVAILABLE', message: 'Planner is busy, try again' } },
    })
    await screen.findByText('DP Basics (1D)')
    await userEvent.click(screen.getByRole('button', { name: 'New plan' }))

    expect(await screen.findByText('Planner is busy, try again')).toBeInTheDocument()
    expect(screen.queryByText('New plan ready')).not.toBeInTheDocument()
    expect(screen.getByText('DP Basics (1D)')).toBeInTheDocument()
    expect(server.calls('POST /recommendations/daily/regenerate')).toHaveLength(1)
  })
})

describe('this week', () => {
  it('is reached from its tab, remembered in the address, and set out as sessions with a goal', async () => {
    const weekly = plan('WEEKLY', [
      item('dp-basics', 'DP Basics (1D)', { session: 1, priority: 'NEW' }),
      item('stacks', 'Stacks', { session: 2, reasonCode: 'PROGRESSION', priority: 'PROGRESS',
        reason: 'Strong and fresh - mastery 85%: step up to harder problems' }),
    ], { summary: { basis: 'MEASURED', notes: [], goalProblems: 6 } })
    open('/recommendations', {
      'GET /recommendations/daily': { data: plan('DAILY', dailyItems) },
      'GET /recommendations/weekly': { data: weekly },
    })
    await screen.findByText('DP Basics (1D)')

    await userEvent.click(screen.getByRole('tab', { name: 'This week' }))

    expect(await screen.findByText(/solved this week/)).toBeInTheDocument()
    expect(screen.getByLabelText('search')).toHaveTextContent('?tab=week')
    expect(screen.getByRole('tab', { name: 'This week' })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByLabelText('Session 1')).toHaveTextContent('S1')
    expect(screen.getByLabelText('Session 2')).toHaveTextContent('S2')
    expect(screen.getByText(/goal 6/)).toBeInTheDocument()
    expect(screen.getByText('Step up')).toBeInTheDocument()
  })
})

describe('revision', () => {
  const queue = [
    revision(11, 'Depth-First Search', 'DUE'),
    revision(12, 'Counting Principles', 'DUE'),
    revision(13, 'Stacks', 'QUEUED', { queuePosition: 1, decayScore: 33 }),
    revision(14, 'Binary Search on Arrays', 'UPCOMING'),
  ]

  it('shows today\'s revision, the queued skills in order, and what comes next', async () => {
    open('/recommendations?tab=revision', {
      'GET /recommendations/daily': { data: plan('DAILY', []) },
      'GET /recommendations/revision': { data: queue },
    })

    expect(await screen.findByText('2 skills to revise today')).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: /Revision/ })).toHaveTextContent('2')
    expect(screen.getAllByText('Due')).toHaveLength(2)
    expect(screen.getAllByText('solve again')).toHaveLength(2)

    const waiting = screen.getByText('Waiting their turn (1)').closest('section')!
    expect(within(waiting).getByText('Stacks')).toBeInTheDocument()
    expect(within(waiting).getByText('33% faded')).toBeInTheDocument()
    expect(within(waiting).queryByRole('button', { name: /Done/ })).not.toBeInTheDocument()

    const next = screen.getByText('Coming up').closest('section')!
    expect(within(next).getByText('Binary Search on Arrays')).toBeInTheDocument()
  })

  it('marking one done tells the server which item, and the refreshed queue shows it moved', async () => {
    let after = false
    const server = open('/recommendations?tab=revision', {
      'GET /recommendations/daily': { data: plan('DAILY', []) },
      'GET /recommendations/revision': () => ({
        data: after ? [revision(12, 'Counting Principles', 'DUE'), revision(11, 'Depth-First Search', 'UPCOMING')] : queue,
      }),
      'POST /recommendations/revision/11/done': () => { after = true; return { data: null } },
    })
    const dfs = (await screen.findByText('Depth-First Search')).closest('.card') as HTMLElement

    await userEvent.click(within(dfs).getByRole('button', { name: /Done/ }))

    expect(await screen.findByText('Revision marked complete')).toBeInTheDocument()
    expect(await screen.findByText('1 skill to revise today')).toBeInTheDocument()
    expect(server.calls('POST /recommendations/revision/11/done')).toHaveLength(1)
    expect(server.calls('POST /recommendations/revision/12/done')).toHaveLength(0)
  })

  it('with today\'s revision done but more waiting, says so', async () => {
    open('/recommendations?tab=revision', {
      'GET /recommendations/daily': { data: plan('DAILY', []) },
      'GET /recommendations/revision': { data: [revision(13, 'Stacks', 'QUEUED')] },
    })
    expect(await screen.findByText("Today's revision is done")).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: /Revision/ })).not.toHaveTextContent(/\d/)
  })

  it('with nothing scheduled, says what will put skills there', async () => {
    open('/recommendations?tab=revision', { 'GET /recommendations/daily': { data: plan('DAILY', []) } })
    expect(await screen.findByText('Nothing to revise')).toBeInTheDocument()
    expect(screen.getByText(/gone long enough without practice/)).toBeInTheDocument()
  })

  it('a failed queue says so', async () => {
    open('/recommendations?tab=revision', {
      'GET /recommendations/daily': { data: plan('DAILY', []) },
      'GET /recommendations/revision': { status: 503, body: { code: 'UNAVAILABLE', message: 'Try again shortly' } } as Reply,
    })
    expect(await screen.findByRole('alert')).toHaveTextContent('Could not load the revision queue')
  })

  it('a "done" the server refuses says so, and the queue is not shown as moved', async () => {
    const server = open('/recommendations?tab=revision', {
      'GET /recommendations/daily': { data: plan('DAILY', []) },
      'GET /recommendations/revision': { data: queue },
      'POST /recommendations/revision/11/done': { status: 500, body: { code: 'INTERNAL_ERROR', message: 'An unexpected error occurred' } },
    })
    const dfs = (await screen.findByText('Depth-First Search')).closest('.card') as HTMLElement

    await userEvent.click(within(dfs).getByRole('button', { name: /Done/ }))

    expect(await screen.findByText('An unexpected error occurred')).toBeInTheDocument()
    expect(screen.queryByText('Revision marked complete')).not.toBeInTheDocument()
    expect(screen.getByText('2 skills to revise today')).toBeInTheDocument()
    expect(server.calls('GET /recommendations/revision')).toHaveLength(1)
  })
})

describe('contest prep', () => {
  it('names the round, links to it, and says what the plan is aimed at', async () => {
    open('/recommendations?tab=contest', {
      'GET /recommendations/daily': { data: plan('DAILY', []) },
      'GET /recommendations/contest-prep': { data: plan('CONTEST_PREP', [
        item('constructive', 'Constructive Algorithms', { reasonCode: 'CONTEST_STAPLE', targetRating: 1350 }),
      ], { summary: {
        basis: 'MEASURED', notes: [], rating: 1350, ratingBasis: 'MEASURED',
        contest: { id: 2143, name: 'Codeforces Round 1043 (Div. 2)', division: 'Div. 2',
          startsAt: '2099-10-24T14:35:00Z', durationSeconds: 7200, url: 'https://codeforces.com/contests/2143' },
        record: { contests: 3, avgSolved: 3, avgRatingChange: 18 },
      } }) },
    })

    expect(await screen.findByText('Codeforces Round 1043 (Div. 2)')).toBeInTheDocument()
    expect(screen.getByText('Div. 2')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /Register on Codeforces/ })).toHaveAttribute('href', 'https://codeforces.com/contests/2143')
    expect(screen.getByText('1350')).toBeInTheDocument()
    expect(screen.getByText('your Codeforces rating')).toBeInTheDocument()
    expect(screen.getByText(/Last 3 rounds/)).toHaveTextContent('Last 3 rounds: 3.0 solved, +18 on average')
    expect(screen.getByText('Contest staple')).toBeInTheDocument()
  })

  it('without a rating or a round, says it is an estimate and general preparation', async () => {
    open('/recommendations?tab=contest', {
      'GET /recommendations/daily': { data: plan('DAILY', []) },
      'GET /recommendations/contest-prep': { data: plan('CONTEST_PREP', [item('dp-basics', 'DP Basics (1D)')], {
        summary: { basis: 'MEASURED', notes: [], rating: 800, ratingBasis: 'INFERRED', contest: null, record: null },
      }) },
    })

    expect(await screen.findByText('estimated from what you have solved')).toBeInTheDocument()
    expect(screen.getByText(/No rated round for your level in the next two weeks/)).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /Register on Codeforces/ })).not.toBeInTheDocument()
    expect(screen.queryByText(/Last \d+ rounds/)).not.toBeInTheDocument()
  })
})

describe('a plan that does not load', () => {
  it.each([
    ['/recommendations?tab=week', 'GET /recommendations/weekly', 'Could not load the weekly plan'],
    ['/recommendations?tab=contest', 'GET /recommendations/contest-prep', 'Could not load contest preparation'],
  ])('%s says which plan failed and offers a retry', async (path, route, title) => {
    open(path, {
      'GET /recommendations/daily': { data: plan('DAILY', []) },
      [route]: { status: 500, body: { code: 'INTERNAL_ERROR', message: 'Codeforces did not answer' } },
    })
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent(title)
    expect(alert).toHaveTextContent('Codeforces did not answer')
    expect(within(alert).getByRole('button', { name: 'Retry' })).toBeInTheDocument()
  })
})
