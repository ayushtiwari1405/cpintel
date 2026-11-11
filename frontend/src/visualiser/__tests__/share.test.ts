import { describe, expect, it } from 'vitest'
import { decodeShare, encodeShare } from '../share'

describe('share links', () => {
  it('round-trips code, input, notes and breakpoints', async () => {
    const shared = { language: 'cpp' as const, code: 'int main() { return 0; }\n// मान', stdin: '3\n',
      notes: { 4: 'watch lo move' }, breakpoints: [2, 5], step: 4 }
    const text = await encodeShare(shared)
    expect(text).toMatch(/^v1\.[A-Za-z0-9_-]+$/)
    expect(await decodeShare(`#${text}`)).toEqual(shared)
  })

  it('rejects damaged or foreign fragments', async () => {
    expect(await decodeShare('#v1.not-really')).toBeNull()
    expect(await decodeShare('#section-2')).toBeNull()
  })
})
