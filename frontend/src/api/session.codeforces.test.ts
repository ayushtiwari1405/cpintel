import { describe, expect, it, vi } from 'vitest'
import axios from 'axios'
import { apiClient } from './client'
import { fakeServer } from '@/test/server'
import { signedIn, user } from '@/test/render'

describe('a refused Codeforces session', () => {
  it('is not mistaken for ours: nothing is renewed and the submission is sent once', async () => {
    signedIn(user('USER'))
    const renew = vi.spyOn(axios, 'post')
    const server = fakeServer({
      'POST /practice/submit': { status: 401, body: { code: 'CF_SESSION_INVALID', message: 'Reconnect' } },
    })

    await expect(apiClient.post('/practice/submit', {})).rejects.toMatchObject({ response: { status: 401 } })

    expect(server.calls('POST /practice/submit')).toHaveLength(1)
    expect(renew).not.toHaveBeenCalled()
  })
})
