// Ad-hoc browser check of the visualiser's teaching tools (not part of any suite).
//   node e2e/visualiser-teach.mjs <base-url> <out-dir>
import { chromium } from 'playwright-core'

const [base, out] = process.argv.slice(2)
const user = { userId: 1, username: 'prof', email: 'p@example.test', role: 'USER',
  isVerified: true, createdAt: '2026-01-01T00:00:00Z' }

const browser = await chromium.launch({ executablePath: process.env.E2E_CHROME ?? '/usr/bin/google-chrome', headless: true })
const context = await browser.newContext({ viewport: { width: 1440, height: 900 } })
await context.grantPermissions(['clipboard-read', 'clipboard-write'], { origin: base })
await context.addInitScript(u => {
  localStorage.setItem('cpintel-auth', JSON.stringify({
    state: { user: u, isAuthenticated: true, mode: 'NORMAL', examId: null }, version: 3 }))
}, user)
await context.route(/\/api\/v1\//, route => {
  const url = route.request().url()
  const data = url.includes('/auth/refresh') ? { accessToken: 'test' } : url.includes('/users/me') ? user : []
  return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ success: true, data }) })
})
await context.route(/fonts\.(googleapis|gstatic)\.com/, r => r.abort())

const page = await context.newPage()
const errors = []
page.on('pageerror', e => errors.push(`pageerror: ${e.message}`))
const stepText = () => page.evaluate(() => document.body.innerText.match(/Step (\d+) of (\d+)/)?.slice(1).map(Number))
const check = (label, ok) => console.log(`${ok ? 'PASS' : 'FAIL'} ${label}`)

await page.goto(`${base}/visualise`)
await page.waitForSelector('text=Visualise')
await page.selectOption('select[aria-label="Load an example"]', 'py-bubble')

// A breakpoint on line 6 (the swap), set from the gutter before running.
const lineNumber = page.locator('.margin-view-overlays .line-numbers', { hasText: /^6$/ })
await lineNumber.click()
await page.click('button:has-text("Visualise")')
await page.waitForSelector('text=/Step \\d+ of/', { timeout: 120000 })
check('starts at step 1', (await stepText())[0] === 1)

await page.click('button[aria-label="Next breakpoint"]')
const [atBreak] = await stepText()
const lineBadge = await page.locator('text=/^line \\d+$/').first().textContent()
check(`next breakpoint lands on line 6 (step ${atBreak}, ${lineBadge})`, lineBadge === 'line 6')

await page.keyboard.press('ArrowRight')
check('arrow key steps forward', (await stepText())[0] === atBreak + 1)
await page.keyboard.press('ArrowLeft')

await page.click('button[title="Add a note to this step"]')
await page.fill('textarea[aria-label="Note for this step"]', 'Swap happens here: watch a[j] and a[j+1]')
await page.click('button:has-text("Done")')
await page.screenshot({ path: `${out}/teach-note.png` })

await page.click('button[title="Copy a link to this visualisation"]')
await page.waitForTimeout(500)
const link = await page.evaluate(() => navigator.clipboard.readText())
check(`share link copied (${link.length} chars)`, link.includes('/visualise#v1.'))

const second = await context.newPage()
await second.goto(link)
await second.waitForSelector('text=/Step \\d+ of/', { timeout: 120000 })
const restored = await second.evaluate(() => document.body.innerText.match(/Step (\d+) of/)?.[1])
check(`shared link opens at the same step (${restored} vs ${atBreak})`, Number(restored) === atBreak)
check('shared link shows the note', await second.locator('text=Swap happens here').count() > 0)
await second.screenshot({ path: `${out}/teach-shared.png` })
await second.close()

// C++: a compile error is reported as the student's, a crash with its line.
await page.click('button:has-text("Edit")')
await page.selectOption('select[aria-label="Language"]', 'cpp')
await page.click('.monaco-editor .view-lines')
await page.keyboard.press('Control+A')
await page.keyboard.press('Delete')
await page.keyboard.insertText('int main() {\n  int x = \n}\n')
await page.click('button:has-text("Visualise")')
await page.waitForSelector('text=/Compilation failed/', { timeout: 180000 }).catch(() => {})
check('compile error shown as the program\'s', await page.locator('text=/Compilation failed/').count() > 0)
await page.screenshot({ path: `${out}/cpp-compile-error.png` })

await page.click('button:has-text("Edit")').catch(() => {})
await page.click('.monaco-editor .view-lines')
await page.keyboard.press('Control+A')
await page.keyboard.press('Delete')
await page.keyboard.insertText('#include <bits/stdc++.h>\nint main() {\nint* p = nullptr;\nint a = 1;\n*p = a;\nreturn 0;\n}\n')
await page.click('button:has-text("Visualise")')
await page.waitForSelector('text=/Step \\d+ of/', { timeout: 180000 }).catch(() => {})
await page.click('button[aria-label="Last step"]').catch(() => {})
await page.waitForTimeout(300)
const body = await page.evaluate(() => document.body.innerText)
check('runtime error reported', /Runtime error/.test(body))
await page.screenshot({ path: `${out}/cpp-runtime-error.png` })

console.log(errors.length ? errors.join('\n') : 'no page errors')
await browser.close()
