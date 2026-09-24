// Fixes the CPIntel server this build talks to, at build time.
//
// The desktop app is the examination client, and an installer that could be pointed at any
// server by a setting would be one a candidate could point at their own. So the address is
// baked in here and read back by main.ts; a packaged build has no runtime override.
//
//   CPINTEL_SERVER_URL=https://cpintel.example.edu npm run build
//
// https only. The app carries logins, exam passwords and the monitor's reports; a plain-http
// server would send all of them across the lab network readable. CPINTEL_ALLOW_HTTP=1 lifts that
// for a throwaway test build against a local server, and says so loudly.
import { writeFileSync, mkdirSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const raw = (process.env.CPINTEL_SERVER_URL ?? '').trim()
if (!raw) {
  console.error('CPINTEL_SERVER_URL is not set. A release build must know its server, e.g.\n'
    + '  CPINTEL_SERVER_URL=https://cpintel.example.edu npm run build')
  process.exit(1)
}

let url
try {
  url = new URL(raw)
} catch {
  console.error(`CPINTEL_SERVER_URL is not a URL: ${raw}`)
  process.exit(1)
}

if (url.protocol !== 'https:') {
  if (process.env.CPINTEL_ALLOW_HTTP === '1' && url.protocol === 'http:') {
    console.warn(`WARNING: building against plain-http ${url.origin}. Test builds only.`)
  } else {
    console.error(`CPINTEL_SERVER_URL must be https:// (got ${url.protocol}//).`)
    process.exit(1)
  }
}

const out = join(dirname(fileURLToPath(import.meta.url)), '..', 'dist', 'server.json')
mkdirSync(dirname(out), { recursive: true })
writeFileSync(out, JSON.stringify({ url: url.origin }, null, 2) + '\n')
console.log(`Desktop build will talk to ${url.origin}`)
