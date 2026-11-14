import { test } from 'node:test'
import assert from 'node:assert/strict'
import { buildLcRequest, readLcAnswer } from './lcRequests'

test('builds only the fixed LeetCode paths', () => {
  assert.equal(buildLcRequest('status').path, '/graphql/')
  const run = buildLcRequest('run', { slug: 'two-sum', questionId: 1, lang: 'cpp', code: 'x', input: '[1]\n2' })
  assert.equal(run.path, '/problems/two-sum/interpret_solution/')
  assert.deepEqual(run.body, { lang: 'cpp', question_id: '1', typed_code: 'x', data_input: '[1]\n2' })
  assert.equal(buildLcRequest('submit', { slug: 'two-sum', questionId: '1', lang: 'python3', code: 'x' }).path,
    '/problems/two-sum/submit/')
  assert.equal(buildLcRequest('check', { id: 'runcode_1731600000.12_abc' }).path,
    '/submissions/detail/runcode_1731600000.12_abc/check/')
})

test('refuses anything that would reach another path', () => {
  assert.throws(() => buildLcRequest('submit', { slug: '../accounts', questionId: 1, lang: 'cpp', code: 'x' }))
  assert.throws(() => buildLcRequest('submit', { slug: 'two-sum', questionId: '1; x', lang: 'cpp', code: 'x' }))
  assert.throws(() => buildLcRequest('submit', { slug: 'two-sum', questionId: 1, lang: 'c++', code: 'x' }))
  assert.throws(() => buildLcRequest('check', { id: '1/../../profile' }))
  assert.throws(() => buildLcRequest('run', { slug: 'two-sum', questionId: 1, lang: 'cpp', code: 'x' }))
  assert.throws(() => buildLcRequest('submit', { slug: 'two-sum', questionId: 1, lang: 'cpp', code: 'x'.repeat(200_000) }))
  assert.throws(() => buildLcRequest('graphql' as any, {}))
})

test('reads answers', () => {
  assert.deepEqual(readLcAnswer('status', { data: { userStatus: { isSignedIn: true, username: 'alice' } } }),
    { signedIn: true, username: 'alice' })
  assert.deepEqual(readLcAnswer('status', { data: { userStatus: { isSignedIn: false, username: '' } } }),
    { signedIn: false, username: null })
  assert.deepEqual(readLcAnswer('submit', { submission_id: 42 }), { id: '42' })
  assert.throws(() => readLcAnswer('run', { error: 'nope' }), /nope/)
})
