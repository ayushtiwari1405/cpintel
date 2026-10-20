import assert from 'node:assert/strict'
import test from 'node:test'
import { isOpenableExternal } from './externalUrl'

test('web addresses open in the system browser', () => {
  assert.equal(isOpenableExternal('https://codeforces.com/contest/1/problem/A'), true)
  assert.equal(isOpenableExternal('http://example.org/'), true)
  assert.equal(isOpenableExternal('HTTPS://EXAMPLE.ORG/path?q=1#x'), true)
})

test('local files and network shares are refused', () => {
  assert.equal(isOpenableExternal('file:///etc/passwd'), false)
  assert.equal(isOpenableExternal('file://C:/Windows/System32/calc.exe'), false)
  assert.equal(isOpenableExternal('smb://attacker.example/share/payload.exe'), false)
  assert.equal(isOpenableExternal('\\\\attacker.example\\share'), false)
})

test("other programs' schemes are refused", () => {
  for (const url of [
    'ms-msdt:/id PCWDiagnostic',
    'search-ms:query=x',
    'vscode://file/etc/passwd',
    'javascript:alert(1)',
    'data:text/html,<script>1</script>',
    'mailto:someone@example.org',
    'ftp://example.org/',
    'zoommtg://join',
  ]) {
    assert.equal(isOpenableExternal(url), false, url)
  }
})

test('a scheme disguised by case, whitespace or a prefix is still read correctly', () => {
  assert.equal(isOpenableExternal('  FILE:///etc/passwd'), false)
  assert.equal(isOpenableExternal('xhttps://example.org'), false)
  assert.equal(isOpenableExternal('file:///https://example.org'), false)
})

test('malformed or empty input is refused', () => {
  assert.equal(isOpenableExternal(''), false)
  assert.equal(isOpenableExternal('not a url'), false)
  assert.equal(isOpenableExternal('https://'), false)
  assert.equal(isOpenableExternal('/relative/path'), false)
})
