import { test } from 'node:test'
import assert from 'node:assert/strict'
import { allowedWhileLocked } from './cfConfine'

test('the sign-in page, the home page and the browser check are allowed', () => {
  assert.ok(allowedWhileLocked('https://codeforces.com/enter'))
  assert.ok(allowedWhileLocked('https://codeforces.com/enter?back=%2F'))
  assert.ok(allowedWhileLocked('https://codeforces.com/'))
  assert.ok(allowedWhileLocked('https://codeforces.com'))
  assert.ok(allowedWhileLocked('https://codeforces.com/cdn-cgi/challenge-platform/h/b/orchestrate'))
})

test('submit pages are allowed, for the anti-bot check', () => {
  assert.ok(allowedWhileLocked('https://codeforces.com/group/wLuDESxnIs/contest/730543/submit'))
  assert.ok(allowedWhileLocked('https://codeforces.com/contest/2000/submit'))
  assert.ok(allowedWhileLocked('https://codeforces.com/problemset/submit'))
  assert.equal(allowedWhileLocked('https://codeforces.com/group/wLuDESxnIs/contest/730543/standings'), false)
  assert.equal(allowedWhileLocked('https://codeforces.com/contest/2000/submit/extra'), false)
})

test('the rest of Codeforces and other sites are not', () => {
  assert.equal(allowedWhileLocked('https://codeforces.com/blog/entry/1'), false)
  assert.equal(allowedWhileLocked('https://codeforces.com/problemset/problem/1/A'), false)
  assert.equal(allowedWhileLocked('https://codeforces.com/contest/1/tutorial'), false)
  assert.equal(allowedWhileLocked('https://accounts.google.com/o/oauth2'), false)
  assert.equal(allowedWhileLocked('http://codeforces.com/enter'), false)
  assert.equal(allowedWhileLocked('https://codeforces.com.evil.test/enter'), false)
  assert.equal(allowedWhileLocked('not a url'), false)
})
