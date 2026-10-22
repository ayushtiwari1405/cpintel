// Browser smoke test of the main journeys against a throwaway CPIntel started by
// scripts/e2e-smoke.sh, which sets everything below. Not part of `npm test`.
//
// env: E2E_BASE (the app's address), E2E_CHROME (a Chrome or Chromium binary),
//      E2E_STUDENT_PASSWORD, E2E_ADMIN2_PASSWORD, E2E_SHOTS (optional: screenshot directory)
import { after, before, describe, test } from 'node:test'
import assert from 'node:assert/strict'
import { chromium } from 'playwright-core'

const BASE = process.env.E2E_BASE
const CHROME = process.env.E2E_CHROME
const SHOTS = process.env.E2E_SHOTS
if (!BASE || !CHROME || !process.env.E2E_STUDENT_PASSWORD) {
  throw new Error('Run through scripts/e2e-smoke.sh; it starts the stack and sets E2E_BASE, E2E_CHROME and the passwords.')
}

const STUDENT = { name: 'e2e_student', password: process.env.E2E_STUDENT_PASSWORD }
const ADMIN = { name: 'e2e_admin', password: process.env.E2E_ADMIN2_PASSWORD }
const DESKTOP = { width: 1366, height: 900 }
const PHONE = { width: 390, height: 844 }

let browser
before(async () => {
  browser = await chromium.launch({ executablePath: CHROME, headless: true })
})
after(async () => {
  await browser?.close()
})

/**
 * A fresh browser profile at `viewport`, recording what went wrong: page errors, console errors,
 * failed requests and server errors. Fonts come from Google and are blocked, so a run needs no
 * internet and gives the same result offline.
 */
async function session(viewport = DESKTOP) {
  const context = await browser.newContext({ viewport })
  await context.route(/fonts\.(googleapis|gstatic)\.com/, r => r.abort())
  const page = await context.newPage()
  const problems = []
  page.on('pageerror', e => problems.push(`page error: ${e.message}`))
  page.on('console', m => { if (m.type() === 'error' && !/Failed to load resource/.test(m.text())) problems.push(`console: ${m.text()}`) })
  page.on('requestfailed', r => { if (!/fonts\.(googleapis|gstatic)/.test(r.url())) problems.push(`failed: ${r.url()}`) })
  page.on('response', r => { if (r.url().startsWith(BASE) && r.status() >= 500) problems.push(`${r.status()} ${r.url()}`) })
  return { context, page, problems }
}

async function signIn(page, who) {
  await page.goto(`${BASE}/login`)
  await page.getByLabel('Email or username').fill(who.name)
  await page.getByLabel('Password', { exact: true }).fill(who.password)
  await page.getByRole('button', { name: 'Sign in' }).click()
  await page.waitForURL(u => !u.pathname.startsWith('/login'))
}

const path = page => new URL(page.url()).pathname

async function shot(page, name) {
  if (SHOTS) await page.screenshot({ path: `${SHOTS}/${name}.png`, fullPage: true })
}

/**
 * Elements that run past the window's edge with no way to reach them. A parent that clips
 * (overflow: hidden) hides them from the page's own scroll width, so they are measured one by
 * one; an element inside a strip that scrolls sideways (the tab row on a phone) is reachable,
 * and is not counted.
 */
function overflowing(page) {
  return page.evaluate(() => {
    const main = document.querySelector('main')
    const found = []
    // <main> scrolls (overflow: auto), so content too wide for it makes the whole page scroll
    // sideways inside it, which the window's own scroll width does not show.
    if (main && main.scrollWidth > main.clientWidth + 1) found.push(`main scrolls sideways (${main.scrollWidth} > ${main.clientWidth})`)
    // Only strips inside <main> that scroll on purpose (the tab row) may hold wider content.
    const scrollsSideways = el => {
      for (let a = el.parentElement; a && a !== main && a !== document.body; a = a.parentElement) {
        const x = getComputedStyle(a).overflowX
        if ((x === 'auto' || x === 'scroll') && a.getBoundingClientRect().right <= window.innerWidth + 1) return true
      }
      return false
    }
    return found.concat([...document.querySelectorAll('main *')]
      .filter(e => e.getBoundingClientRect().right > window.innerWidth + 1 && !scrollsSideways(e))
      .map(e => `${e.tagName.toLowerCase()}.${String(e.className).split(' ')[0]}`)
      .slice(0, 5))
  })
}

describe('signing in and staying signed in', () => {
  test('a signed-out visitor is sent to sign in', async () => {
    const { context, page } = await session()
    await page.goto(`${BASE}/recommendations`)
    await page.waitForURL(u => u.pathname === '/login')
    await context.close()
  })

  test('a student signs in, lands on the dashboard, and a reload restores the session without a 401', async () => {
    const { context, page, problems } = await session()
    await signIn(page, STUDENT)
    assert.equal(path(page), '/dashboard')

    const seen = []
    page.on('response', r => { if (r.url().includes('/api/')) seen.push({ path: new URL(r.url()).pathname, status: r.status() }) })
    await page.reload()
    await page.waitForLoadState('networkidle')
    assert.equal(path(page), '/dashboard')
    assert.deepEqual(seen.filter(s => s.status === 401), [], 'no 401 while restoring')
    assert.equal(seen.filter(s => s.path.endsWith('/auth/refresh')).length, 1, 'one renewal')
    assert.deepEqual(problems, [])
    await context.close()
  })
})

describe('a student\'s main pages', () => {
  test('dashboard, roadmap and the practice plan, reached from the sidebar', async () => {
    const { context, page, problems } = await session()
    await signIn(page, STUDENT)
    const nav = page.getByRole('complementary', { name: 'Main navigation' })

    await nav.getByRole('link', { name: 'Roadmap' }).click()
    await page.getByRole('heading', { name: 'Learning roadmap' }).waitFor()
    await shot(page, 'roadmap-desktop')

    await nav.getByRole('link', { name: 'Practice plan' }).click()
    await page.getByRole('heading', { name: 'Practice plan' }).waitFor()
    await page.getByRole('progressbar').waitFor()
    const today = await page.getByRole('tabpanel').innerText()
    assert.match(today, /solved today/)
    assert.match(today, /warm-up|core|stretch/)
    await shot(page, 'plan-today-desktop')

    await page.getByRole('tab', { name: 'This week' }).click()
    await page.getByText(/solved this week/).waitFor()
    assert.ok(await page.getByLabel('Session 1').isVisible())

    await page.getByRole('tab', { name: /Revision/ }).click()
    await page.getByText(/to revise today|revision is done|Nothing due|Nothing to revise/).first().waitFor()

    await page.getByRole('tab', { name: 'Contest prep' }).click()
    await page.getByText('Codeforces Round 999 (Div. 2)').waitFor()
    await shot(page, 'plan-contest-desktop')

    await nav.getByRole('link', { name: 'Dashboard' }).click()
    await page.waitForURL(u => u.pathname === '/dashboard')
    await page.waitForLoadState('networkidle')
    assert.deepEqual(problems, [])
    await context.close()
  })

  test('at phone width, nothing runs off the screen and the menu opens', async () => {
    const { context, page, problems } = await session(PHONE)
    await signIn(page, STUDENT)
    for (const route of ['/dashboard', '/roadmap', '/recommendations', '/recommendations?tab=revision', '/recommendations?tab=contest']) {
      await page.goto(`${BASE}${route}`)
      await page.waitForLoadState('networkidle')
      assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1), `${route}: page scrolls sideways`)
      assert.deepEqual(await overflowing(page), [], `${route}: elements wider than the screen`)
    }
    await shot(page, 'plan-contest-phone')
    await page.getByRole('button', { name: 'Open navigation' }).click()
    await page.getByRole('complementary', { name: 'Main navigation' }).getByRole('link', { name: 'Practice plan' }).waitFor()
    assert.deepEqual(problems, [])
    await context.close()
  })
})

describe('an administrator', () => {
  test('lands in the console, is kept out of the student pages, and signs out for good', async () => {
    const { context, page, problems } = await session()
    await signIn(page, ADMIN)
    assert.equal(path(page), '/admin')
    const nav = page.getByRole('complementary', { name: 'Main navigation' })
    assert.equal(await nav.getByRole('link', { name: 'Practice plan' }).count(), 0)

    await page.goto(`${BASE}/recommendations`)
    await page.waitForURL(u => u.pathname === '/admin')

    await page.getByRole('button', { name: /Sign out/ }).click()
    await page.waitForURL(u => u.pathname === '/login')
    await page.goto(`${BASE}/admin`)
    await page.waitForURL(u => u.pathname === '/login')
    assert.deepEqual(problems, [])
    await context.close()
  })
})
