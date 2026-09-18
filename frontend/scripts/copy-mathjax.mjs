// Copies the MathJax runtime out of node_modules into public/ so the app serves it
// itself instead of fetching it from a CDN.
//
// Why not just use the CDN: Codeforces statements are pure TeX ($$$...$$$) and are
// unreadable without MathJax, so a blocked or unreachable CDN turns every statement
// into raw markup. Ad blockers and DNS filters routinely block jsdelivr, and the
// Electron desktop build has to work with no network at all.
//
// Only two paths are needed. tex-mml-chtml.js is a self-contained combined component
// (TeX input + CHTML output, no further component loading), and it resolves its font
// directory relative to its own script URL — hence the fonts must sit at exactly
// output/chtml/fonts/woff-v2 beside it. Copying all of es5/ would be 24 MB for the
// ~1.6 MB actually used.
import { cp, mkdir, access } from 'node:fs/promises'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = dirname(fileURLToPath(import.meta.url)) + '/..'
const src  = join(root, 'node_modules', 'mathjax', 'es5')
const dest = join(root, 'public', 'mathjax')

const ENTRIES = ['tex-mml-chtml.js', join('output', 'chtml', 'fonts', 'woff-v2')]

try {
  await access(src)
} catch {
  console.error('[mathjax] node_modules/mathjax not found — run npm install first.')
  process.exit(1)
}

for (const entry of ENTRIES) {
  const to = join(dest, entry)
  await mkdir(dirname(to), { recursive: true })
  await cp(join(src, entry), to, { recursive: true })
}
console.log(`[mathjax] runtime copied to public/mathjax`)
