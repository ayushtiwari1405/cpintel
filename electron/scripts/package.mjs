// Runs electron-builder for the server stamped into this build (scripts/stamp-server.mjs).
//
// The server's address doubles as the package's homepage, which Debian packages require and
// which differs per deployment, so it cannot live in package.json. Arguments are passed through:
//
//   node scripts/package.mjs              # installers for this machine
//   node scripts/package.mjs --mac --x64  # Intel Mac installer from an Apple Silicon runner
import { execFileSync } from 'node:child_process'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = join(dirname(fileURLToPath(import.meta.url)), '..')
const { url } = JSON.parse(readFileSync(join(root, 'dist', 'server.json'), 'utf8'))

execFileSync('npx', ['electron-builder', ...process.argv.slice(2),
  `-c.extraMetadata.homepage=${url}`], {
  cwd: root,
  stdio: 'inherit',
  // npx is a .cmd script on Windows, which only a shell can start.
  shell: process.platform === 'win32',
})
