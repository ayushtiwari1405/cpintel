// The smoke suite's front door, as nginx is in production: serves a built frontend (falling back
// to index.html for client routes), passes /api to the backend with the browser's Host header,
// and stands in for Codeforces under /cf/api so nothing in a run reaches the real site.
//
// usage: node e2e/server.mjs <dist dir> <port> <backend base url>
import http from 'node:http'
import fs from 'node:fs'
import path from 'node:path'
import { problemset, contestList } from './fixtures.mjs'

const [DIST, PORT, BACKEND] = process.argv.slice(2)
if (!DIST || !PORT || !BACKEND) {
  console.error('usage: node e2e/server.mjs <dist dir> <port> <backend base url>')
  process.exit(2)
}
const root = path.resolve(DIST)
const backend = new URL(BACKEND)

const TYPES = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.mjs': 'text/javascript',
  '.css': 'text/css', '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png',
  '.ico': 'image/x-icon', '.woff2': 'font/woff2', '.wasm': 'application/wasm', '.txt': 'text/plain',
}

function json(res, body) {
  res.writeHead(200, { 'Content-Type': 'application/json' })
  res.end(JSON.stringify(body))
}

function proxy(req, res) {
  const upstream = http.request({
    hostname: backend.hostname, port: backend.port, path: req.url, method: req.method,
    // Host passes through, so the backend sees the page and the API as one origin, as behind nginx.
    headers: { ...req.headers, 'x-forwarded-host': req.headers.host },
  }, up => {
    res.writeHead(up.statusCode ?? 502, up.headers)
    up.pipe(res)
  })
  upstream.on('error', () => {
    res.writeHead(502, { 'Content-Type': 'application/json' })
    res.end(JSON.stringify({ code: 'BAD_GATEWAY', message: 'backend unreachable' }))
  })
  req.pipe(upstream)
}

function serveFile(req, res) {
  const pathname = decodeURIComponent(new URL(req.url, 'http://x').pathname)
  let file = path.join(root, pathname)
  // Never outside the build directory.
  if (!file.startsWith(root + path.sep) && file !== root) {
    res.writeHead(403)
    return res.end()
  }
  if (!fs.existsSync(file) || fs.statSync(file).isDirectory()) file = path.join(root, 'index.html')
  res.writeHead(200, { 'Content-Type': TYPES[path.extname(file)] ?? 'application/octet-stream' })
  fs.createReadStream(file).pipe(res)
}

http.createServer((req, res) => {
  const url = req.url ?? '/'
  if (url.startsWith('/cf/api/problemset.problems')) return json(res, problemset())
  if (url.startsWith('/cf/api/contest.list')) return json(res, contestList(Date.now()))
  if (url.startsWith('/cf/api/')) return json(res, { status: 'FAILED', comment: 'not stubbed' })
  if (url.startsWith('/api/')) return proxy(req, res)
  return serveFile(req, res)
}).listen(Number(PORT), '127.0.0.1', () => console.log(`e2e server on http://127.0.0.1:${PORT}`))
