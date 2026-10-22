// How requests carry and restore the session (src/api/session.ts), against real axios and a
// scripted server. Run with `npm test` (Node 24 runs the TypeScript module directly).
import { test } from 'node:test'
import assert from 'node:assert/strict'
import axios, { AxiosError, CanceledError } from 'axios'
import { installSession } from '../src/api/session.ts'

/** A server that answers from `handle` and records what it was sent. */
function serverFor(handle) {
  const seen = []
  const adapter = async config => {
    const token = String(config.headers?.Authorization ?? '').replace(/^Bearer /, '') || null
    seen.push({ method: config.method, url: config.url, token })
    const { status = 200, data = { ok: true }, delay = 0, network = false } = (await handle(config, token)) ?? {}
    if (delay) await new Promise(resolve => setTimeout(resolve, delay))
    if (network) throw new AxiosError('Network Error', 'ERR_NETWORK', config)
    const response = { data, status, statusText: String(status), headers: {}, config, request: {} }
    if (status >= 400) {
      throw new AxiosError(`Request failed with status code ${status}`, 'ERR_BAD_REQUEST', config, {}, response)
    }
    return response
  }
  return { seen, adapter }
}

/** A client with the session installed, and counters for what the hooks were asked to do. */
function setup({ token = null, signedIn = true, renew, handle }) {
  const state = { token, signedIn, renewals: 0, ended: [] }
  const server = serverFor(handle)
  const client = axios.create({ adapter: server.adapter })
  installSession(client, {
    token: () => state.token,
    signedIn: () => state.signedIn,
    renew: async () => { state.renewals++; return renew(state.renewals) },
    keep: t => { state.token = t },
    ended: e => { state.ended.push(e); state.signedIn = false },
    sleep: async () => {},
  })
  return { client, state, server }
}

/** A refresh endpoint that answers after a pause, so concurrent requests overlap with it. */
const slowRenew = (token = 'fresh', ms = 20) => () => new Promise(r => setTimeout(() => r(token), ms))
const refused = status => {
  const response = { status, data: { code: 'TOKEN_EXPIRED' }, statusText: '', headers: {}, config: {} }
  return new AxiosError('refused', 'ERR_BAD_REQUEST', {}, {}, response)
}
const acceptOnly = good => (config, token) => (token === good ? {} : { status: 401 })

test('a hard reload restores the session once and sends nothing without it', async () => {
  const { client, state, server } = setup({ renew: slowRenew('fresh'), handle: acceptOnly('fresh') })

  const answers = await Promise.all(['/users/me', '/roadmaps/current', '/roadmaps/gauntlet',
    '/recommendations/daily', '/evaluation/exams'].map(url => client.get(url)))

  assert.equal(answers.length, 5)
  assert.equal(state.renewals, 1, 'one renewal shared by every request')
  assert.deepEqual(server.seen.map(s => s.token), Array(5).fill('fresh'), 'no request went out without the token')
  assert.equal(state.ended.length, 0)
})

test('requests made while a renewal is running join it rather than starting another', async () => {
  const { client, state } = setup({ renew: slowRenew('fresh', 40), handle: acceptOnly('fresh') })

  const first = client.get('/users/me')
  await new Promise(r => setTimeout(r, 10))
  const later = client.get('/roadmaps/current')
  await Promise.all([first, later])

  assert.equal(state.renewals, 1)
})

test('a 401 for a request sent before a renewal finished is retried with the new token, not renewed again', async () => {
  let renewedAt = null
  const { client, state, server } = setup({
    token: 'old',
    renew: () => { renewedAt = Date.now(); return 'fresh' },
    // The first answer is quick, the second slow: by the time it says 401, the first has renewed.
    handle: (config, token) => token === 'fresh' ? {} : { status: 401, delay: config.url === '/slow' ? 40 : 0 },
  })

  await Promise.all([client.get('/fast'), client.get('/slow')])

  assert.ok(renewedAt)
  assert.equal(state.renewals, 1)
  assert.deepEqual(server.seen.filter(s => s.token === 'fresh').map(s => s.url).sort(), ['/fast', '/slow'])
})

test('concurrent 401s on an expired token share one renewal', async () => {
  const { client, state } = setup({ token: 'old', renew: slowRenew('fresh'), handle: acceptOnly('fresh') })
  await Promise.all([client.get('/a'), client.get('/b'), client.get('/c')])
  assert.equal(state.renewals, 1)
})

test('an ended session signs out once, and its requests are not sent', async () => {
  const { client, state, server } = setup({ renew: () => { throw refused(401) }, handle: () => ({}) })

  const results = await Promise.allSettled([client.get('/a'), client.get('/b'), client.get('/c')])

  assert.ok(results.every(r => r.status === 'rejected' && r.reason instanceof CanceledError))
  assert.equal(state.renewals, 1)
  assert.equal(state.ended.length, 1, 'signed out once')
  assert.equal(state.ended[0].response.status, 401, 'with the server\'s own answer, for the message')
  assert.equal(server.seen.length, 0, 'nothing went to the API')
})

test('a token the server rejects is renewed once and the request tried once more, never in a loop', async () => {
  const { client, state, server } = setup({
    token: 'revoked', renew: () => 'still-bad', handle: () => ({ status: 401 }),
  })

  await assert.rejects(client.get('/users/me'), e => e.response?.status === 401)
  assert.equal(state.renewals, 1)
  assert.equal(server.seen.length, 2)
})

test('a server that cannot be reached during restore keeps the session and the read is tried again', async () => {
  let reachable = false
  const { client, state, server } = setup({
    renew: () => {
      if (!reachable) { reachable = true; throw new AxiosError('Network Error', 'ERR_NETWORK', {}) }
      return 'fresh'
    },
    handle: (config, token) => (token === 'fresh' ? {} : { network: true }),
  })

  const answer = await client.get('/roadmaps/current')

  assert.equal(answer.status, 200)
  assert.equal(state.ended.length, 0, 'not signed out over an outage')
  assert.equal(state.renewals, 2)
  assert.equal(server.seen.at(-1).token, 'fresh')
})

test('somebody signed out gets no renewal before a request, and a public request goes out as is', async () => {
  const { client, state, server } = setup({ signedIn: false, renew: slowRenew(), handle: () => ({}) })
  await client.get('/desktop/release')
  assert.equal(state.renewals, 0)
  assert.equal(server.seen[0].token, null)
})

test('a wrong password is not treated as an ended session', async () => {
  const { client, state } = setup({ signedIn: false, renew: slowRenew(), handle: () => ({ status: 401 }) })
  await assert.rejects(client.post('/auth/login', { identifier: 'a', password: 'b' }), e => e.response?.status === 401)
  assert.equal(state.renewals, 0)
  assert.equal(state.ended.length, 0)
})

test('a forbidden answer is an authorisation failure, not a reason to renew', async () => {
  const { client, state } = setup({ token: 'good', renew: slowRenew(), handle: () => ({ status: 403 }) })
  await assert.rejects(client.get('/admin/users'), e => e.response?.status === 403)
  assert.equal(state.renewals, 0)
  assert.equal(state.ended.length, 0)
})

test('writes are not repeated while the server is unreachable', async () => {
  const { client, server } = setup({ token: 'good', renew: slowRenew(), handle: () => ({ network: true }) })
  await assert.rejects(client.post('/practice/submit', {}))
  assert.equal(server.seen.length, 1)
})
