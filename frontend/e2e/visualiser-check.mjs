// Ad-hoc browser check for the code visualiser (not part of any suite). Fakes a signed-in
// session at the network layer, opens /visualise, runs an example, and screenshots each view.
//
//   node e2e/visualiser-check.mjs <base-url> <out-dir> <example-id> [stdin]
import { chromium } from 'playwright-core'

const [base, out, example] = process.argv.slice(2)
const CHROME = process.env.E2E_CHROME ?? '/usr/bin/google-chrome'

const user = { userId: 1, username: 'student', email: 's@example.test', role: 'USER',
  isVerified: true, createdAt: '2026-01-01T00:00:00Z' }

const browser = await chromium.launch({ executablePath: CHROME, headless: true })
const context = await browser.newContext({ viewport: { width: 1440, height: 900 } })
await context.addInitScript(u => {
  localStorage.setItem('cpintel-auth', JSON.stringify({
    state: { user: u, isAuthenticated: true, mode: 'NORMAL', examId: null }, version: 3 }))
}, user)
await context.route(/\/api\/v1\//, route => {
  const url = route.request().url()
  const data = url.includes('/auth/refresh') ? { accessToken: 'test' }
    : url.includes('/users/me') ? user : []
  return route.fulfill({ status: 200, contentType: 'application/json',
    body: JSON.stringify({ success: true, data }) })
})
await context.route(/fonts\.(googleapis|gstatic)\.com/, r => r.abort())

const page = await context.newPage()
const errors = []
page.on('pageerror', e => errors.push(`pageerror: ${e.message}`))
page.on('console', m => { if (m.type() === 'error') errors.push(`console: ${m.text()}`) })

await page.goto(`${base}/visualise`)
await page.waitForSelector('text=Visualise', { timeout: 30000 })
await page.selectOption('select[aria-label="Load an example"]', example)
await page.click('button:has-text("Visualise")')
await page.waitForSelector('text=/Step \\d+ of/', { timeout: 180000 }).catch(() => {})
await page.screenshot({ path: `${out}/${example}-0-start.png` })

const count = await page.evaluate(() => {
  const m = document.body.innerText.match(/Step \d+ of (\d+)/)
  return m ? Number(m[1]) : 0
})
console.log('steps', count)
for (const frac of [0.35, 0.7]) {
  const target = Math.floor(count * frac)
  await page.locator('input[aria-label="Step"]').fill(String(target))
  await page.waitForTimeout(300)
  await page.screenshot({ path: `${out}/${example}-memory-${Math.round(frac * 100)}.png` })
}
for (const tab of ['Structures', 'Calls', 'Output']) {
  await page.click(`button:has-text("${tab}")`)
  await page.waitForTimeout(300)
  await page.screenshot({ path: `${out}/${example}-${tab.toLowerCase()}.png` })
}
console.log(errors.length ? errors.join('\n') : 'no page errors')
console.log(await page.evaluate(() => document.body.innerText.slice(0, 400)))
await browser.close()
