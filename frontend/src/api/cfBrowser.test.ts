import { afterEach, describe, expect, it, vi } from 'vitest'

const post = vi.fn()
vi.mock('./client', () => ({ apiClient: { post: (...args: unknown[]) => post(...args) } }))

import { browserConnect, browserSubmit, cfContest, isAntiBotRefusal, isSignedIn, olderThan, withVerifiedFields } from './cfBrowser'

describe('Codeforces contest ids', () => {
  it('reads a group contest, keeping its group in the path', () => {
    expect(cfContest('wLuDESxnIs~730484')).toEqual({
      id: 730484, group: 'wLuDESxnIs', path: '/group/wLuDESxnIs/contest/730484' })
  })

  it('leaves a public contest as a number', () => {
    expect(cfContest(2259)).toEqual({ id: 2259, group: null, path: '/contest/2259' })
    expect(cfContest('2259').path).toBe('/contest/2259')
  })
})

describe('the anti-bot check on submitting', () => {
  afterEach(() => {
    delete (window as any).cpintelDesktop
    post.mockReset()
  })

  it('recognises the refusal', () => {
    expect(isAntiBotRefusal('<span class="error">Please complete the anti-bot verification</span>')).toBe(true)
    expect(isAntiBotRefusal('<div>My submissions</div>')).toBe(false)
  })

  it('keeps the submission the server filled in and takes the rest from the live page', () => {
    const merged = withVerifiedFields(
      { csrf_token: 'c', source: 'int main(){}', submittedProblemIndex: 'A', programTypeId: '54', ftaa: 'made-up' },
      { csrf_token: 'other', source: '', submittedProblemIndex: 'B', ftaa: 'real', 'cf-turnstile-response': 'tok' })
    expect(merged).toEqual({ csrf_token: 'c', source: 'int main(){}', submittedProblemIndex: 'A',
      programTypeId: '54', ftaa: 'real', 'cf-turnstile-response': 'tok' })
  })

  it('compares extension versions', () => {
    expect(olderThan('0.1.0', '0.2.0')).toBe(true)
    expect(olderThan('0.2.0', '0.2.0')).toBe(false)
    expect(olderThan('0.10.0', '0.2.0')).toBe(false)
  })

  it('passes the check in the app window and posts again when Codeforces asks for it', async () => {
    const fetch = vi.fn()
      .mockResolvedValueOnce({ status: 200, url: 'u', body: 'submit page' })
      .mockResolvedValueOnce({ status: 200, url: 'u', body: 'Please complete the anti-bot verification' })
      .mockResolvedValueOnce({ status: 200, url: 'u', body: 'My submissions' })
    const verify = vi.fn().mockResolvedValue({ fields: { 'cf-turnstile-response': 'tok' } })
    ;(window as any).cpintelDesktop = { cf: { fetch, verify } }
    post
      .mockResolvedValueOnce({ data: { data: { archiveId: 'a1', url: 'https://codeforces.com/x/submit?csrf_token=c',
        fields: { csrf_token: 'c', source: 'code' } } } })
      .mockResolvedValueOnce({ data: { data: { submissionId: 42 } } })

    const id = await browserSubmit({ contestId: 'wLuDESxnIs~730543', index: 'A', contest: true,
      languageId: '54', source: 'code' })

    expect(id).toBe(42)
    expect(verify).toHaveBeenCalledWith('https://codeforces.com/group/wLuDESxnIs/contest/730543/submit')
    expect(fetch.mock.calls[2][0].form).toEqual({ csrf_token: 'c', source: 'code', 'cf-turnstile-response': 'tok' })
    expect(post.mock.calls[1][1]).toMatchObject({ archiveId: 'a1', html: 'My submissions' })
  })
})

describe('connecting through the extension', () => {
  afterEach(() => {
    document.documentElement.removeAttribute('data-cpintel-cf')
    post.mockReset()
    vi.restoreAllMocks()
  })

  it('reads whether a page is signed in', () => {
    expect(isSignedIn('<a href="/a1b2c3/logout">Logout</a>')).toBe(true)
    expect(isSignedIn('<a href="/enter?back=%2F">Enter</a>')).toBe(false)
  })

  it('opens the sign-in window when this browser is signed out, then connects', async () => {
    document.documentElement.setAttribute('data-cpintel-cf', '0.3.0')
    const pages = ['<a href="/enter">Enter</a>', '<a href="/x/logout">Logout</a>']
    const kinds: string[] = []
    vi.spyOn(window, 'postMessage').mockImplementation(((message: any) => {
      kinds.push(message.kind)
      const reply = message.kind === 'signin' ? { ok: true }
        : { status: 200, url: 'https://codeforces.com/', body: pages.shift() }
      window.dispatchEvent(new MessageEvent('message', {
        source: window, data: { cpintelCf: 'response', id: message.id, ...reply } }))
    }) as typeof window.postMessage)
    post.mockResolvedValue({ data: { data: { connected: true, handle: 'burner' } } })

    const status = await browserConnect()

    expect(kinds).toEqual(['fetch', 'signin', 'fetch'])
    expect(post.mock.calls[0][1]).toEqual({ html: '<a href="/x/logout">Logout</a>' })
    expect(status.handle).toBe('burner')
  })
})
