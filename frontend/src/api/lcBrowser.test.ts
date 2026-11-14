import { afterEach, describe, expect, it, vi } from 'vitest'
import { extensionTooOld, lcRelay, lcRun, lcStatus, lcSubmit, monacoLanguage } from './lcBrowser'

const solution = { slug: 'two-sum', questionId: '1', lang: 'cpp', code: 'class Solution {};' }

afterEach(() => {
  delete (window as any).cpintelDesktop
  document.documentElement.removeAttribute('data-cpintel-lc')
  document.documentElement.removeAttribute('data-cpintel-cf')
  vi.useRealTimers()
})

describe('LeetCode through the desktop app', () => {
  it('submits and polls until LeetCode has judged it', async () => {
    vi.useFakeTimers()
    const call = vi.fn()
      .mockResolvedValueOnce({ result: { id: '987' } })
      .mockResolvedValueOnce({ result: { state: 'STARTED' } })
      .mockResolvedValueOnce({ result: { state: 'SUCCESS', status_msg: 'Accepted', total_correct: 63, total_testcases: 63 } })
    ;(window as any).cpintelDesktop = { lc: { call, signIn: vi.fn(), forget: vi.fn() } }
    expect(lcRelay()).toBe('desktop')

    const pending = lcSubmit(solution)
    await vi.runAllTimersAsync()
    const r = await pending

    expect(r).toMatchObject({ status_msg: 'Accepted', submissionId: '987' })
    expect(call.mock.calls[0]).toEqual(['submit', solution])
    expect(call.mock.calls[1]).toEqual(['check', { id: '987' }])
  })

  it('passes the test input with a run', async () => {
    vi.useFakeTimers()
    const call = vi.fn()
      .mockResolvedValueOnce({ result: { id: 'runcode_1' } })
      .mockResolvedValueOnce({ result: { state: 'SUCCESS', status_msg: 'Accepted', code_answer: ['[0,1]'] } })
    ;(window as any).cpintelDesktop = { lc: { call, signIn: vi.fn(), forget: vi.fn() } }

    const pending = lcRun(solution, '[2,7,11,15]\n9')
    await vi.runAllTimersAsync()
    expect((await pending).code_answer).toEqual(['[0,1]'])
    expect(call.mock.calls[0]).toEqual(['run', { ...solution, input: '[2,7,11,15]\n9' }])
  })

  it('surfaces what the app reports', async () => {
    ;(window as any).cpintelDesktop = { lc: { call: vi.fn().mockResolvedValue({ error: 'LeetCode refused: sign in to LeetCode again.' }) } }
    await expect(lcStatus()).rejects.toThrow('sign in to LeetCode again')
  })
})

describe('LeetCode through the extension', () => {
  it('asks the content script and reads its answer', async () => {
    document.documentElement.setAttribute('data-cpintel-lc', '0.4.0')
    expect(lcRelay()).toBe('extension')
    const sent: any[] = []
    vi.spyOn(window, 'postMessage').mockImplementation(((message: any) => {
      sent.push(message)
      window.dispatchEvent(new MessageEvent('message', {
        source: window, data: { cpintelCf: 'response', id: message.id, result: { signedIn: true, username: 'alice' } } }))
    }) as typeof window.postMessage)

    expect(await lcStatus()).toEqual({ signedIn: true, username: 'alice' })
    expect(sent[0]).toMatchObject({ cpintelCf: 'request', kind: 'lc-call', op: 'status' })
    vi.restoreAllMocks()
  })

  it('knows an extension that predates LeetCode', () => {
    document.documentElement.setAttribute('data-cpintel-cf', '0.3.0')
    expect(lcRelay()).toBeNull()
    expect(extensionTooOld()).toBe(true)
  })
})

it('maps LeetCode languages onto the editor', () => {
  expect(monacoLanguage('python3')).toBe('python')
  expect(monacoLanguage('golang')).toBe('go')
  expect(monacoLanguage('unknownlang')).toBe('plaintext')
})
