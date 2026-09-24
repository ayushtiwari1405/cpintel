// Builds the extension for one CPIntel deployment.
//
//   CPINTEL_SERVER_URL=https://cpintel.example.edu node build.mjs
//   node build.mjs --dev            # for http://localhost:5173 and http://localhost
//
// The CPIntel address is stamped into manifest.json as the only site the extension runs on, so
// no other site can ask it to fetch Codeforces with the user's sign-in. Output: dist/ (load it
// unpacked from chrome://extensions) and cpintel-codeforces.zip (for the Chrome Web Store, or to
// hand out).
import { cpSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { execFileSync } from 'node:child_process'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const dev = process.argv.includes('--dev')

const origins = []
const raw = (process.env.CPINTEL_SERVER_URL ?? '').trim()
if (raw) {
  const url = new URL(raw)
  if (url.protocol !== 'https:') {
    console.error(`CPINTEL_SERVER_URL must be https:// (got ${url.protocol}//).`)
    process.exit(1)
  }
  origins.push(`${url.origin}/*`)
}
if (dev) origins.push('http://localhost:5173/*', 'http://localhost/*')
if (origins.length === 0) {
  console.error('Set CPINTEL_SERVER_URL=https://<your server>, or pass --dev.')
  process.exit(1)
}

const dist = join(here, 'dist')
rmSync(dist, { recursive: true, force: true })
mkdirSync(dist, { recursive: true })
cpSync(join(here, 'src'), dist, { recursive: true })

const manifest = JSON.parse(readFileSync(join(dist, 'manifest.json'), 'utf8'))
manifest.content_scripts[0].matches = origins
writeFileSync(join(dist, 'manifest.json'), JSON.stringify(manifest, null, 2) + '\n')

try {
  rmSync(join(here, 'cpintel-codeforces.zip'), { force: true })
  execFileSync('zip', ['-qr', join(here, 'cpintel-codeforces.zip'), '.'], { cwd: dist })
  console.log('Built dist/ and cpintel-codeforces.zip for', origins.join(', '))
} catch {
  console.log('Built dist/ for', origins.join(', '), '(zip not available, so no .zip)')
}
